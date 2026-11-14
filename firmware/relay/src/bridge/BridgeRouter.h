#pragma once

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "ble/BleBridge.h"
#include "bridge/BridgeCore.h"
#include "lora/LoRaManager.h"

// Moves opaque mesh packets between the phone (BleBridge) and LoRa.
//
//   BLE write -> BleBridge fromPhone queue -> LoRa task: BridgeCore (dedup) -> LoRa TX
//   LoRa RX (DIO0) -> LoRa task: BridgeCore (dedup) -> toPhone queue -> notify task -> BLE notify
//
// The routing decisions live in BridgeCore (host-tested); this class owns the
// FreeRTOS tasks and queues. Only the LoRa task touches the radio and the dedup
// cache. BLE callbacks never block on LoRa; a full queue drops the packet with a
// LORADBG line.
class BridgeRouter {
public:
	using Stats = BridgeCore::Stats;

	// radioAvailable=false runs BLE-only: the radio is never touched and phone
	// packets are dropped with reason=LORA_UNAVAILABLE.
	bool begin(BleBridge& bridge, LoRaManager& lora, bool radioAvailable);
	Stats stats() const;
	bool isLoRaAvailable() const { return loraAvailable; }

private:
	static void loraTaskEntry(void* self);
	static void notifyTaskEntry(void* self);
	void loraTask();
	void notifyTask();

	BleBridge* bridge = nullptr;
	BridgeCore* core = nullptr;
	QueueHandle_t toPhone = nullptr;
	bool loraAvailable = false;
};
