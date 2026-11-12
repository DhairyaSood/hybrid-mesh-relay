#pragma once

#include <cstddef>
#include <cstdint>

#include "config.h"

// Remembers recent mesh packetIds so a packet echoed back over LoRa (or written
// back by the phone) is not forwarded again. Fixed-size ring; the oldest entry
// is overwritten when full. Not thread-safe: use from a single task.
class DedupCache {
public:
	// True if packetId was recorded within the last BRIDGE_DEDUP_WINDOW_MS.
	// Otherwise records it at nowMs and returns false.
	bool checkAndRecord(const uint8_t* packetId, uint32_t nowMs);

private:
	struct Entry {
		uint8_t id[MESH_PACKET_ID_BYTES];
		uint32_t seenAtMs;
		bool used;
	};

	Entry entries[BRIDGE_DEDUP_CAPACITY] = {};
	size_t next = 0;
};

// Formats packet bytes 4-19 as "HMR-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
// matching the packetId Android logs. out must hold 41 bytes.
void formatPacketId(const uint8_t* packet, char* out);
