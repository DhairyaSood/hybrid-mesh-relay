#pragma once

#include <Arduino.h>
#include <atomic>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "ble/BleBridge.h"
#include "bridge/BridgeCore.h"
#include "bridge/RadioBringUp.h"
#include "lora/LoRaManager.h"

// Moves opaque mesh packets between the phone (BleBridge) and LoRa.
//
//   BLE write -> BleBridge fromPhone queue -> LoRa task: BridgeCore (dedup) -> LoRa TX
//   LoRa RX (DIO0) -> LoRa task: BridgeCore (dedup) -> toPhone queue -> notify task -> BLE notify
//
// The routing decisions live in BridgeCore (host-tested); this class owns the
// FreeRTOS tasks and queues. Only the LoRa task touches the radio: serial
// commands (loracheck, send) are queued to it. If the radio is down the bridge
// runs BLE-only and the LoRa task retries bring-up every BRIDGE_RADIO_RETRY_MS.
class BridgeRouter {
public:
	using Stats = BridgeCore::Stats;

	struct RadioState {
		bool available;
		uint8_t version;
		int16_t code;
		uint32_t nextRetryInMs;  // 0 when the radio is up
	};

	// bootRadio is the result of radioBringUp() in setup(). sendLabel prefixes
	// "send" messages: "<label>:<seq>:<text>", like lora-ping.
	bool begin(BleBridge& bridge, LoRaManager& lora, const RadioBringUpResult& bootRadio, const String& sendLabel);
	Stats stats() const;
	RadioState radioState() const;
	bool isLoRaAvailable() const { return loraAvailable.load(); }

	// Thread-safe; executed by the LoRa task. False if the command queue is full
	// or the text is too long.
	bool requestLoraCheck();
	bool requestSend(const String& text);

	static constexpr size_t MAX_SEND_TEXT = 200;

private:
	enum class CommandType : uint8_t { LoraCheck, Send };
	struct Command {
		CommandType type;
		char text[MAX_SEND_TEXT + 1];
	};

	static void loraTaskEntry(void* self);
	static void notifyTaskEntry(void* self);
	void loraTask();
	void notifyTask();
	void handleCommands();
	void runBringUp();
	void applyRadioResult(const RadioBringUpResult& result);
	void sendRaw(const char* text);

	BleBridge* bridge = nullptr;
	LoRaManager* lora = nullptr;
	BridgeCore* core = nullptr;
	QueueHandle_t toPhone = nullptr;
	QueueHandle_t commands = nullptr;
	String label;
	uint32_t sendSequence = 1;

	// Written by the LoRa task, read by loop() for "status".
	std::atomic<bool> loraAvailable{false};
	std::atomic<uint8_t> radioVersion{0};
	std::atomic<int16_t> radioCode{0};
	std::atomic<uint32_t> nextRetryAtMs{0};
};
