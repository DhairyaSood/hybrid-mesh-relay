#include "BridgeRouter.h"

#include "diagnostics/Logger.h"

namespace {

constexpr uint32_t TASK_STACK_BYTES = 6144;
constexpr UBaseType_t TASK_PRIORITY = 1;  // same as Arduino loop(); BLE host runs on core 0
constexpr BaseType_t TASK_CORE = 1;
// How long the LoRa task waits for a phone packet before polling the radio again.
constexpr TickType_t RX_POLL_TICKS = pdMS_TO_TICKS(5);

}

bool BridgeRouter::begin(BleBridge& bleBridge, LoRaManager& loraManager, bool radioAvailable) {
	bridge = &bleBridge;
	lora = &loraManager;
	loraAvailable = radioAvailable;

	toPhone = xQueueCreate(BRIDGE_TO_PHONE_QUEUE_DEPTH, sizeof(BridgePacket));
	if (toPhone == nullptr) {
		LORADBG("DROP", "reason=QUEUE_ALLOC_FAILED queue=toPhone");
		return false;
	}
	if (xTaskCreatePinnedToCore(loraTaskEntry, "lora", TASK_STACK_BYTES, this, TASK_PRIORITY, nullptr, TASK_CORE) != pdPASS ||
		xTaskCreatePinnedToCore(notifyTaskEntry, "bleNotify", TASK_STACK_BYTES, this, TASK_PRIORITY, nullptr, TASK_CORE) != pdPASS) {
		LORADBG("DROP", "reason=TASK_CREATE_FAILED");
		return false;
	}
	LORADBG("BOOT", "router started loraAvailable=%d dedupWindowMs=%d minPacket=%d maxPacket=%d",
			loraAvailable, BRIDGE_DEDUP_WINDOW_MS, BRIDGE_MIN_PACKET_BYTES, BRIDGE_MAX_PACKET_BYTES);
	return true;
}

BridgeRouter::Stats BridgeRouter::stats() const {
	return Stats{loraTxCount.load(), loraRxCount.load(), notifiedCount.load(), droppedCount.load()};
}

void BridgeRouter::loraTaskEntry(void* self) {
	static_cast<BridgeRouter*>(self)->loraTask();
}

void BridgeRouter::notifyTaskEntry(void* self) {
	static_cast<BridgeRouter*>(self)->notifyTask();
}

void BridgeRouter::loraTask() {
	BridgePacket packet;
	if (!loraAvailable) {
		// BLE-only mode: never touch the radio; drain phone packets so the queue
		// cannot fill, and say why each one goes nowhere.
		while (true) {
			if (bridge->receiveFromPhone(packet, portMAX_DELAY)) {
				dropWithoutLoRa(packet);
			}
		}
	}
	while (true) {
		receiveFromLoRa();
		if (bridge->receiveFromPhone(packet, RX_POLL_TICKS)) {
			transmitFromPhone(packet);
		}
	}
}

void BridgeRouter::dropWithoutLoRa(const BridgePacket& packet) {
	droppedCount++;
	loradbgHex("BLE", "packet from phone", packet.data, packet.length);
	if (packet.length >= BRIDGE_MIN_PACKET_BYTES) {
		char packetId[41];
		formatPacketId(packet.data, packetId);
		LORADBG("DROP", "reason=LORA_UNAVAILABLE direction=toLoRa bytes=%u packetId=%s", packet.length, packetId);
	} else {
		LORADBG("DROP", "reason=LORA_UNAVAILABLE direction=toLoRa bytes=%u", packet.length);
	}
}

void BridgeRouter::transmitFromPhone(const BridgePacket& packet) {
	if (packet.length < BRIDGE_MIN_PACKET_BYTES) {
		droppedCount++;
		LORADBG("DROP", "reason=TOO_SHORT_FOR_PACKET_ID direction=toLoRa bytes=%u min=%u",
				packet.length, BRIDGE_MIN_PACKET_BYTES);
		return;
	}

	char packetId[41];
	formatPacketId(packet.data, packetId);
	if (dedup.checkAndRecord(packet.data + MESH_PACKET_ID_OFFSET, millis())) {
		droppedCount++;
		LORADBG("DROP", "reason=DUPLICATE direction=toLoRa packetId=%s", packetId);
		return;
	}

	LORADBG("LORA", "tx start bytes=%u packetId=%s airtimeMs~%lu", packet.length, packetId,
			static_cast<unsigned long>(lora->timeOnAirMs(packet.length)));
	const uint32_t startedAt = millis();
	const int16_t state = lora->transmit(packet.data, packet.length);
	if (state != RADIOLIB_ERR_NONE) {
		droppedCount++;
		LORADBG("DROP", "reason=LORA_TX_FAILED code=%d packetId=%s", state, packetId);
		return;
	}
	loraTxCount++;
	LORADBG("LORA", "tx ok bytes=%u packetId=%s tookMs=%lu", packet.length, packetId,
			static_cast<unsigned long>(millis() - startedAt));
}

void BridgeRouter::receiveFromLoRa() {
	BridgePacket packet;
	size_t length = 0;
	const int16_t state = lora->receive(packet.data, sizeof(packet.data), length);
	if (state == RADIOLIB_ERR_RX_TIMEOUT) {
		return;  // nothing waiting
	}

	const float rssi = lora->lastPacketRssi();
	const float snr = lora->lastPacketSnr();
	if (state != RADIOLIB_ERR_NONE) {
		droppedCount++;
		LORADBG("DROP", "reason=%s code=%d rssi=%.1f snr=%.1f",
				state == RADIOLIB_ERR_CRC_MISMATCH ? "LORA_CRC_MISMATCH" : "LORA_RX_FAILED", state, rssi, snr);
		return;
	}
	packet.length = length;
	loraRxCount++;
	LORADBG("LORA", "rx bytes=%u rssi=%.1f snr=%.1f", packet.length, rssi, snr);
	loradbgHex("LORA", "rx data", packet.data, packet.length);

	if (packet.length < BRIDGE_MIN_PACKET_BYTES) {
		droppedCount++;
		LORADBG("DROP", "reason=TOO_SHORT_FOR_PACKET_ID direction=toPhone bytes=%u min=%u",
				packet.length, BRIDGE_MIN_PACKET_BYTES);
		return;
	}

	char packetId[41];
	formatPacketId(packet.data, packetId);
	if (dedup.checkAndRecord(packet.data + MESH_PACKET_ID_OFFSET, millis())) {
		droppedCount++;
		LORADBG("DROP", "reason=DUPLICATE direction=toPhone packetId=%s", packetId);
		return;
	}
	if (xQueueSend(toPhone, &packet, 0) != pdTRUE) {
		droppedCount++;
		LORADBG("DROP", "reason=QUEUE_FULL queue=toPhone packetId=%s", packetId);
		return;
	}
	LORADBG("LORA", "queued for phone packetId=%s", packetId);
}

void BridgeRouter::notifyTask() {
	BridgePacket packet;
	while (true) {
		if (xQueueReceive(toPhone, &packet, portMAX_DELAY) != pdTRUE) {
			continue;
		}
		// sendToPhone logs NO_PHONE_CONNECTED / PHONE_NOT_SUBSCRIBED / NOTIFY_FAILED itself.
		if (bridge->sendToPhone(packet.data, packet.length)) {
			notifiedCount++;
		} else {
			droppedCount++;
		}
	}
}
