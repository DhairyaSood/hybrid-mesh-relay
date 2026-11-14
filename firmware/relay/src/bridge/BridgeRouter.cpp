#include "BridgeRouter.h"

#include "diagnostics/Logger.h"

namespace {

constexpr uint32_t TASK_STACK_BYTES = 6144;
constexpr UBaseType_t TASK_PRIORITY = 1;  // same as Arduino loop(); BLE host runs on core 0
constexpr BaseType_t TASK_CORE = 1;
// How long the LoRa task waits for a phone packet before polling the radio again.
constexpr TickType_t RX_POLL_TICKS = pdMS_TO_TICKS(5);
// BLE-only mode: how long to wait for a phone packet before checking commands and the retry timer.
constexpr TickType_t BLE_ONLY_POLL_TICKS = pdMS_TO_TICKS(50);
constexpr UBaseType_t COMMAND_QUEUE_DEPTH = 4;

uint32_t nowMs() {
	return millis();
}

}

bool BridgeRouter::begin(BleBridge& bleBridge, LoRaManager& loraManager, const RadioBringUpResult& bootRadio,
						 const String& sendLabel) {
	bridge = &bleBridge;
	lora = &loraManager;
	label = sendLabel;
	core = new BridgeCore(loraManager, nowMs);  // lives for the whole run
	applyRadioResult(bootRadio);

	toPhone = xQueueCreate(BRIDGE_TO_PHONE_QUEUE_DEPTH, sizeof(BridgePacket));
	commands = xQueueCreate(COMMAND_QUEUE_DEPTH, sizeof(Command));
	if (toPhone == nullptr || commands == nullptr) {
		LORADBG("DROP", "reason=QUEUE_ALLOC_FAILED queue=%s", toPhone == nullptr ? "toPhone" : "commands");
		return false;
	}
	if (xTaskCreatePinnedToCore(loraTaskEntry, "lora", TASK_STACK_BYTES, this, TASK_PRIORITY, nullptr, TASK_CORE) != pdPASS ||
		xTaskCreatePinnedToCore(notifyTaskEntry, "bleNotify", TASK_STACK_BYTES, this, TASK_PRIORITY, nullptr, TASK_CORE) != pdPASS) {
		LORADBG("DROP", "reason=TASK_CREATE_FAILED");
		return false;
	}
	LORADBG("BOOT", "router started loraAvailable=%d dedupWindowMs=%d minPacket=%d maxPacket=%d",
			loraAvailable.load(), BRIDGE_DEDUP_WINDOW_MS, BRIDGE_MIN_PACKET_BYTES, BRIDGE_MAX_PACKET_BYTES);
	return true;
}

BridgeRouter::Stats BridgeRouter::stats() const {
	return core != nullptr ? core->stats() : Stats{0, 0, 0, 0};
}

BridgeRouter::RadioState BridgeRouter::radioState() const {
	const bool available = loraAvailable.load();
	const int32_t untilRetry = static_cast<int32_t>(nextRetryAtMs.load() - millis());
	return RadioState{available, radioVersion.load(), radioCode.load(),
					  available || untilRetry < 0 ? 0u : static_cast<uint32_t>(untilRetry)};
}

bool BridgeRouter::requestLoraCheck() {
	Command command{CommandType::LoraCheck, {0}};
	return commands != nullptr && xQueueSend(commands, &command, 0) == pdTRUE;
}

bool BridgeRouter::requestSend(const String& text) {
	if (text.length() == 0 || text.length() > MAX_SEND_TEXT) {
		return false;
	}
	Command command{CommandType::Send, {0}};
	strncpy(command.text, text.c_str(), MAX_SEND_TEXT);
	return commands != nullptr && xQueueSend(commands, &command, 0) == pdTRUE;
}

void BridgeRouter::applyRadioResult(const RadioBringUpResult& result) {
	const bool wasAvailable = loraAvailable.load();
	radioVersion = result.version;
	radioCode = result.code;
	nextRetryAtMs = millis() + BRIDGE_RADIO_RETRY_MS;
	loraAvailable = result.ok;
	if (core != nullptr && result.ok != wasAvailable) {
		LORADBG("RADIO", "%s", result.ok ? "radio up: bridging BLE <-> LoRa" : "radio down: BLE-only");
	}
}

void BridgeRouter::runBringUp() {
	applyRadioResult(radioBringUp(*lora));
}

void BridgeRouter::loraTaskEntry(void* self) {
	static_cast<BridgeRouter*>(self)->loraTask();
}

void BridgeRouter::notifyTaskEntry(void* self) {
	static_cast<BridgeRouter*>(self)->notifyTask();
}

void BridgeRouter::loraTask() {
	BridgePacket packet;
	while (true) {
		handleCommands();

		if (!loraAvailable) {
			// BLE-only mode: never touch the radio except to retry bring-up; drain
			// phone packets so the queue cannot fill, and say why each one goes nowhere.
			if (bridge->receiveFromPhone(packet, BLE_ONLY_POLL_TICKS)) {
				core->dropWithoutRadio(packet);
			}
			if (static_cast<int32_t>(millis() - nextRetryAtMs.load()) >= 0) {
				LORADBG("RADIO", "background retry");
				runBringUp();
			}
			continue;
		}

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

void BridgeRouter::handleCommands() {
	Command command;
	while (xQueueReceive(commands, &command, 0) == pdTRUE) {
		switch (command.type) {
			case CommandType::LoraCheck:
				LORADBG("RADIO", "loracheck: re-running bring-up");
				runBringUp();
				break;
			case CommandType::Send:
				sendRaw(command.text);
				break;
		}
	}
}

// Like lora-ping's send: "<label>:<seq>:<text>" as raw bytes, no dedup, not a mesh packet.
void BridgeRouter::sendRaw(const char* text) {
	if (!loraAvailable) {
		LORADBG("DROP", "reason=LORA_UNAVAILABLE command=send");
		return;
	}
	const String message = label + ":" + String(sendSequence++) + ":" + text;
	const int16_t state = lora->transmit(reinterpret_cast<const uint8_t*>(message.c_str()), message.length());
	if (state != RADIOLIB_ERR_NONE) {
		LORADBG("DROP", "reason=LORA_TX_FAILED code=%d command=send", state);
		return;
	}
	LORADBG("LORA", "send tx ok bytes=%u text=%s", message.length(), message.c_str());
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
