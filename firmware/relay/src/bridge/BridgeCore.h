#pragma once

#include <atomic>
#include <cstdint>

#include "bridge/BridgePacket.h"
#include "lora/RadioPort.h"
#include "routing/DedupCache.h"

// The bridge's routing decisions, free of FreeRTOS/BLE so they run in host tests:
//   phone packet -> length check -> dedup -> radio TX
//   radio RX     -> length check -> dedup -> packet for the phone
// BridgeRouter owns the tasks and queues around it. Call sendFromPhone() and
// receiveForPhone() from one task only (they share the dedup cache); the
// counters may be read from any task.
class BridgeCore {
public:
	struct Stats {
		uint32_t loraTx;
		uint32_t loraRx;
		uint32_t notified;
		uint32_t dropped;
	};

	using Clock = uint32_t (*)();

	BridgeCore(RadioPort& radio, Clock nowMs) : radio(radio), nowMs(nowMs) {}

	// True when the packet was transmitted over LoRa.
	bool sendFromPhone(const BridgePacket& packet);
	// BLE-only mode: count and log a phone packet that has nowhere to go.
	void dropWithoutRadio(const BridgePacket& packet);
	// Polls the radio once. True when out holds a new packet for the phone.
	bool receiveForPhone(BridgePacket& out);

	void countNotified() { notifiedCount++; }
	void countDropped() { droppedCount++; }
	Stats stats() const;

private:
	RadioPort& radio;
	Clock nowMs;
	DedupCache dedup;

	std::atomic<uint32_t> loraTxCount{0};
	std::atomic<uint32_t> loraRxCount{0};
	std::atomic<uint32_t> notifiedCount{0};
	std::atomic<uint32_t> droppedCount{0};
};
