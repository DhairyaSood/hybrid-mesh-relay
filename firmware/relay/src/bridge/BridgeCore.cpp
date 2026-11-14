#include "BridgeCore.h"

#include "diagnostics/Logger.h"

namespace {

bool isPrintableText(const uint8_t* data, size_t length) {
	for (size_t i = 0; i < length; i++) {
		if (data[i] < 0x20 || data[i] > 0x7E) return false;
	}
	return length > 0;
}

}

BridgeCore::Stats BridgeCore::stats() const {
	return Stats{loraTxCount.load(), loraRxCount.load(), notifiedCount.load(), droppedCount.load()};
}

void BridgeCore::dropWithoutRadio(const BridgePacket& packet) {
	droppedCount++;
	loradbgHex("BLE", "packet from phone", packet.data, packet.length);
	if (packet.length >= BRIDGE_MIN_PACKET_BYTES) {
		char packetId[41];
		formatPacketId(packet.data, packetId);
		LORADBG("DROP", "reason=LORA_UNAVAILABLE direction=toLoRa bytes=%u packetId=%s", packet.length, packetId);
	} else {
		LORADBG("DROP", "reason=LORA_UNAVAILABLE direction=toLoRa bytes=%u", packet.length);
	}
}

bool BridgeCore::sendFromPhone(const BridgePacket& packet) {
	if (packet.length < BRIDGE_MIN_PACKET_BYTES) {
		droppedCount++;
		LORADBG("DROP", "reason=TOO_SHORT_FOR_PACKET_ID direction=toLoRa bytes=%u min=%u",
				packet.length, BRIDGE_MIN_PACKET_BYTES);
		return false;
	}

	char packetId[41];
	formatPacketId(packet.data, packetId);
	if (dedup.checkAndRecord(packet.data + MESH_PACKET_ID_OFFSET, nowMs())) {
		droppedCount++;
		LORADBG("DROP", "reason=DUPLICATE direction=toLoRa packetId=%s", packetId);
		return false;
	}

	LORADBG("LORA", "tx start bytes=%u packetId=%s airtimeMs~%lu", packet.length, packetId,
			static_cast<unsigned long>(radio.timeOnAirMs(packet.length)));
	const uint32_t startedAt = nowMs();
	const int16_t state = radio.transmit(packet.data, packet.length);
	if (state != RADIO_OK) {
		droppedCount++;
		LORADBG("DROP", "reason=LORA_TX_FAILED code=%d packetId=%s", state, packetId);
		return false;
	}
	loraTxCount++;
	LORADBG("LORA", "tx ok bytes=%u packetId=%s tookMs=%lu", packet.length, packetId,
			static_cast<unsigned long>(nowMs() - startedAt));
	return true;
}

bool BridgeCore::receiveForPhone(BridgePacket& out) {
	size_t length = 0;
	const int16_t state = radio.receive(out.data, sizeof(out.data), length);
	if (state == RADIO_NO_PACKET) {
		return false;
	}

	const float rssi = radio.lastPacketRssi();
	const float snr = radio.lastPacketSnr();
	if (state != RADIO_OK) {
		droppedCount++;
		LORADBG("DROP", "reason=%s code=%d rssi=%.1f snr=%.1f",
				state == RADIO_CRC_MISMATCH ? "LORA_CRC_MISMATCH" : "LORA_RX_FAILED", state, rssi, snr);
		return false;
	}
	out.length = static_cast<uint16_t>(length);
	loraRxCount++;
	LORADBG("LORA", "rx bytes=%u rssi=%.1f snr=%.1f", out.length, rssi, snr);
	loradbgHex("LORA", "rx data", out.data, out.length);
	if (isPrintableText(out.data, out.length)) {
		// e.g. a "send" from another bridge or a lora-ping board
		LORADBG("LORA", "rx text=%.*s", static_cast<int>(out.length), reinterpret_cast<const char*>(out.data));
	}

	if (out.length < BRIDGE_MIN_PACKET_BYTES) {
		droppedCount++;
		LORADBG("DROP", "reason=TOO_SHORT_FOR_PACKET_ID direction=toPhone bytes=%u min=%u",
				out.length, BRIDGE_MIN_PACKET_BYTES);
		return false;
	}

	char packetId[41];
	formatPacketId(out.data, packetId);
	if (dedup.checkAndRecord(out.data + MESH_PACKET_ID_OFFSET, nowMs())) {
		droppedCount++;
		LORADBG("DROP", "reason=DUPLICATE direction=toPhone packetId=%s", packetId);
		return false;
	}
	return true;
}
