#include "DedupCache.h"

#include <cstdio>
#include <cstring>

bool DedupCache::checkAndRecord(const uint8_t* packetId, uint32_t nowMs) {
	for (const Entry& entry : entries) {
		// Unsigned subtraction stays correct across the millis() wrap.
		if (entry.used && nowMs - entry.seenAtMs < BRIDGE_DEDUP_WINDOW_MS &&
			memcmp(entry.id, packetId, MESH_PACKET_ID_BYTES) == 0) {
			return true;
		}
	}

	Entry& slot = entries[next];
	memcpy(slot.id, packetId, MESH_PACKET_ID_BYTES);
	slot.seenAtMs = nowMs;
	slot.used = true;
	next = (next + 1) % BRIDGE_DEDUP_CAPACITY;
	return false;
}

void formatPacketId(const uint8_t* packet, char* out) {
	const uint8_t* id = packet + MESH_PACKET_ID_OFFSET;
	snprintf(out, 41,
			 "HMR-%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
			 id[0], id[1], id[2], id[3], id[4], id[5], id[6], id[7],
			 id[8], id[9], id[10], id[11], id[12], id[13], id[14], id[15]);
}
