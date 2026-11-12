#pragma once

#include <Arduino.h>
#include <atomic>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "ble/BleBridge.h"
#include "lora/LoRaManager.h"
#include "routing/DedupCache.h"

// Moves opaque mesh packets between the phone (BleBridge) and LoRa.
//
//   BLE write -> BleBridge fromPhone queue -> LoRa task: dedup -> LoRa TX
//   LoRa RX (DIO0) -> LoRa task: dedup -> toPhone queue -> notify task -> BLE notify
//
// Only the LoRa task touches the radio and the dedup cache. BLE callbacks never
// block on LoRa; a full queue drops the packet with a LORADBG line.
class BridgeRouter {
public:
	struct Stats {
		uint32_t loraTx;
		uint32_t loraRx;
		uint32_t notified;
		uint32_t dropped;
	};

	bool begin(BleBridge& bridge, LoRaManager& lora);
	Stats stats() const;

private:
	static void loraTaskEntry(void* self);
	static void notifyTaskEntry(void* self);
	void loraTask();
	void notifyTask();
	void transmitFromPhone(const BridgePacket& packet);
	void receiveFromLoRa();

	BleBridge* bridge = nullptr;
	LoRaManager* lora = nullptr;
	QueueHandle_t toPhone = nullptr;
	DedupCache dedup;

	// Updated by the LoRa and notify tasks, read by loop().
	std::atomic<uint32_t> loraTxCount{0};
	std::atomic<uint32_t> loraRxCount{0};
	std::atomic<uint32_t> notifiedCount{0};
	std::atomic<uint32_t> droppedCount{0};
};
