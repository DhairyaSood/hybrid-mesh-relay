#include "ChunkFraming.h"

#include <cstring>

#include "diagnostics/Logger.h"

size_t bridgeChunkCount(size_t length, size_t chunkData) {
	return (length + chunkData - 1) / chunkData;
}

size_t bridgeBuildChunk(const uint8_t* data, size_t length, size_t chunkData, size_t index, uint8_t* frame) {
	const size_t offset = index * chunkData;
	const size_t n = length - offset < chunkData ? length - offset : chunkData;
	frame[0] = static_cast<uint8_t>(index);
	frame[1] = static_cast<uint8_t>(bridgeChunkCount(length, chunkData));
	memcpy(frame + BLE_BRIDGE_CHUNK_HEADER_BYTES, data + offset, n);
	return n + BLE_BRIDGE_CHUNK_HEADER_BYTES;
}

void ChunkAssembler::reset() {
	length = 0;
	discardedBytes = 0;
	chunkCount = 0;
	nextIndex = 0;
	tooLarge = false;
}

ChunkAssembler::Result ChunkAssembler::onChunk(const uint8_t* data, size_t size, uint32_t nowMs, BridgePacket& out) {
	if (size < BLE_BRIDGE_CHUNK_HEADER_BYTES + 1) {
		LORADBG("DROP", "reason=CHUNK_TOO_SHORT bytes=%u (need index, count and data)",
				static_cast<unsigned>(size));
		return Result::Dropped;
	}

	const uint8_t index = data[0];
	const uint8_t count = data[1];
	const uint8_t* payload = data + BLE_BRIDGE_CHUNK_HEADER_BYTES;
	const uint16_t payloadLength = static_cast<uint16_t>(size - BLE_BRIDGE_CHUNK_HEADER_BYTES);

	if (count == 0 || index >= count) {
		LORADBG("DROP", "reason=BAD_CHUNK_HEADER index=%u count=%u", index, count);
		reset();
		return Result::Dropped;
	}

	if (chunkCount != 0 && nowMs - lastChunkMs > BLE_BRIDGE_REASSEMBLY_TIMEOUT_MS) {
		LORADBG("DROP", "reason=REASSEMBLY_TIMEOUT got=%u/%u", nextIndex, chunkCount);
		reset();
	}

	if (index == 0) {
		if (chunkCount != 0) {
			LORADBG("DROP", "reason=TRANSFER_RESTARTED got=%u/%u", nextIndex, chunkCount);
		}
		reset();
		chunkCount = count;
	} else if (chunkCount == 0) {
		LORADBG("DROP", "reason=MISSING_FIRST_CHUNK index=%u count=%u", index, count);
		return Result::Dropped;
	} else if (count != chunkCount || index != nextIndex) {
		LORADBG("DROP", "reason=OUT_OF_ORDER expected=%u/%u got=%u/%u", nextIndex, chunkCount, index, count);
		reset();
		return Result::Dropped;
	}

	lastChunkMs = nowMs;
	nextIndex = index + 1;
	const bool last = nextIndex == chunkCount;

	if (!tooLarge && length + payloadLength > BRIDGE_MAX_PACKET_BYTES) {
		tooLarge = true;
		discardedBytes = length;
	}
	if (tooLarge) {
		discardedBytes += payloadLength;
		if (!last) {
			return Result::Incomplete;
		}
		LORADBG("DROP", "reason=PACKET_TOO_LARGE bytes=%u max=%u (not truncated, whole packet dropped)",
				discardedBytes, BRIDGE_MAX_PACKET_BYTES);
		reset();
		return Result::Dropped;
	}

	memcpy(buffer + length, payload, payloadLength);
	length += payloadLength;
	LORADBG("BLE", "write chunk=%u/%u bytes=%u total=%u", nextIndex, chunkCount, payloadLength, length);

	if (!last) {
		return Result::Incomplete;
	}
	out.length = length;
	memcpy(out.data, buffer, length);
	reset();
	return Result::Complete;
}
