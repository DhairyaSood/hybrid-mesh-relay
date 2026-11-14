#pragma once

#include <cstddef>
#include <cstdint>

#include "bridge/BridgePacket.h"

// Framing on both bridge characteristics (see protocol/packet-format.md):
//   chunkIndex(1) chunkCount(1) data(N)
// Same format as Android's LoRaBridgeFraming. Plain C++ so it runs in host tests.

// Chunks needed for length bytes when each chunk carries chunkData bytes.
size_t bridgeChunkCount(size_t length, size_t chunkData);

// Writes chunk `index` of data into frame (frame needs chunkData + 2 bytes).
// Returns the frame length.
size_t bridgeBuildChunk(const uint8_t* data, size_t length, size_t chunkData, size_t index, uint8_t* frame);

// Reassembles phone writes into whole packets. Logs every rejected chunk with a
// LORADBG DROP line. Not thread-safe: use from one task (the NimBLE host task).
class ChunkAssembler {
public:
	enum class Result { Incomplete, Complete, Dropped };

	// out is filled only when the result is Complete.
	Result onChunk(const uint8_t* data, size_t length, uint32_t nowMs, BridgePacket& out);
	void reset();

	bool inProgress() const { return chunkCount != 0; }
	uint8_t chunksReceived() const { return nextIndex; }
	uint8_t chunksExpected() const { return chunkCount; }

private:
	uint8_t buffer[BRIDGE_MAX_PACKET_BYTES];
	uint16_t length = 0;
	uint16_t discardedBytes = 0;
	uint8_t chunkCount = 0;  // 0 = no transfer in progress
	uint8_t nextIndex = 0;
	bool tooLarge = false;
	uint32_t lastChunkMs = 0;
};
