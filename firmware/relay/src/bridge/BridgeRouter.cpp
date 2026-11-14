#include "BridgeRouter.h"

#include "diagnostics/Logger.h"

namespace {

constexpr uint32_t TASK_STACK_BYTES = 6144;
constexpr UBaseType_t TASK_PRIORITY = 1;  // same as Arduino loop(); BLE host runs on core 0
constexpr BaseType_t TASK_CORE = 1;
// How long the LoRa task waits for a phone packet before polling the radio again.
constexpr TickType_t RX_POLL_TICKS = pdMS_TO_TICKS(5);

uint32_t nowMs() {
	return millis();
}

}

bool BridgeRouter::begin(BleBridge& bleBridge, LoRaManager& loraManager, bool radioAvailable) {
	bridge = &bleBridge;
	loraAvailable = radioAvailable;
	core = new BridgeCore(loraManager, nowMs);  // lives for the whole run

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
	return core != nullptr ? core->stats() : Stats{0, 0, 0, 0};
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
				core->dropWithoutRadio(packet);
			}
		}
	}
	while (true) {
		if (core->receiveForPhone(packet)) {
			char packetId[41];
			formatPacketId(packet.data, packetId);
			if (xQueueSend(toPhone, &packet, 0) != pdTRUE) {
				core->countDropped();
				LORADBG("DROP", "reason=QUEUE_FULL queue=toPhone packetId=%s", packetId);
			} else {
				LORADBG("LORA", "queued for phone packetId=%s", packetId);
			}
		}
		if (bridge->receiveFromPhone(packet, RX_POLL_TICKS)) {
			core->sendFromPhone(packet);
		}
	}
}

void BridgeRouter::notifyTask() {
	BridgePacket packet;
	while (true) {
		if (xQueueReceive(toPhone, &packet, portMAX_DELAY) != pdTRUE) {
			continue;
		}
		// sendToPhone logs NO_PHONE_CONNECTED / PHONE_NOT_SUBSCRIBED / NOTIFY_FAILED itself.
		if (bridge->sendToPhone(packet.data, packet.length)) {
			core->countNotified();
		} else {
			core->countDropped();
		}
	}
}
