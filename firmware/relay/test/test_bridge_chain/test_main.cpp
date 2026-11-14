// Host test of the whole bridge path with a mock radio, no board needed:
//   phone chunks -> ChunkAssembler -> BridgeCore -> MockRadio TX
//   MockRadio RX -> BridgeCore -> notification chunks -> phone
// The "phone" side here splits and joins chunks with its own code (not the
// firmware's), following the format in protocol/packet-format.md and Android's
// LoRaBridgeFraming.
#include <unity.h>

#include <cstring>
#include <deque>
#include <vector>

#include "ble/ChunkFraming.h"
#include "bridge/BridgeCore.h"

namespace {

using Bytes = std::vector<uint8_t>;

uint32_t fakeNow = 1000;
uint32_t clockMs() {
	return fakeNow;
}

// A radio whose transmissions land in the inbox of its peer, if any.
class MockRadio : public RadioPort {
public:
	std::vector<Bytes> sent;
	std::deque<Bytes> inbox;
	MockRadio* peer = nullptr;
	int16_t nextTxResult = RADIO_OK;

	int16_t transmit(const uint8_t* data, size_t length) override {
		if (nextTxResult != RADIO_OK) return nextTxResult;
		sent.emplace_back(data, data + length);
		if (peer != nullptr) peer->inbox.emplace_back(data, data + length);
		return RADIO_OK;
	}
	int16_t receive(uint8_t* data, size_t capacity, size_t& length) override {
		length = 0;
		if (inbox.empty()) return RADIO_NO_PACKET;
		Bytes packet = inbox.front();
		inbox.pop_front();
		if (packet.size() > capacity) return -4;  // RADIOLIB_ERR_PACKET_TOO_LONG
		memcpy(data, packet.data(), packet.size());
		length = packet.size();
		return RADIO_OK;
	}
	uint32_t timeOnAirMs(size_t) override { return 0; }
	float lastPacketRssi() override { return -50.0f; }
	float lastPacketSnr() override { return 9.5f; }
};

// A mesh packet: magic "NM", version, type, 16-byte packetId, then payload.
Bytes meshPacket(uint8_t idSeed, size_t totalBytes) {
	Bytes p(totalBytes);
	p[0] = 'N';
	p[1] = 'M';
	p[2] = 2;
	p[3] = 1;
	for (size_t i = 4; i < totalBytes; i++) p[i] = static_cast<uint8_t>(idSeed + i * 7);
	return p;
}

// Phone side, written independently of the firmware: [index][count][data...].
std::vector<Bytes> phoneSplit(const Bytes& packet, size_t chunkData) {
	std::vector<Bytes> chunks;
	const size_t count = (packet.size() + chunkData - 1) / chunkData;
	for (size_t i = 0; i < count; i++) {
		Bytes c = {static_cast<uint8_t>(i), static_cast<uint8_t>(count)};
		const size_t end = std::min(packet.size(), (i + 1) * chunkData);
		c.insert(c.end(), packet.begin() + i * chunkData, packet.begin() + end);
		chunks.push_back(c);
	}
	return chunks;
}

Bytes phoneJoin(const std::vector<Bytes>& chunks) {
	Bytes packet;
	for (size_t i = 0; i < chunks.size(); i++) {
		TEST_ASSERT_EQUAL_UINT8(i, chunks[i][0]);
		TEST_ASSERT_EQUAL_UINT8(chunks.size(), chunks[i][1]);
		packet.insert(packet.end(), chunks[i].begin() + 2, chunks[i].end());
	}
	return packet;
}

// One bridge board: what BleBridge::onWrite + BridgeRouter + sendToPhone do,
// minus FreeRTOS and NimBLE.
struct Bridge {
	MockRadio radio;
	BridgeCore core{radio, clockMs};
	ChunkAssembler assembler;

	// Phone writes these chunks; returns true if a whole packet went out over LoRa.
	bool phoneWrites(const std::vector<Bytes>& chunks) {
		BridgePacket packet;
		bool transmitted = false;
		for (const Bytes& c : chunks) {
			if (assembler.onChunk(c.data(), c.size(), fakeNow, packet) == ChunkAssembler::Result::Complete) {
				transmitted = core.sendFromPhone(packet);
			}
		}
		return transmitted;
	}

	// Polls the radio once; notification chunks for the phone (empty if none).
	std::vector<Bytes> pollForPhone(size_t chunkData) {
		BridgePacket packet;
		std::vector<Bytes> chunks;
		if (!core.receiveForPhone(packet)) return chunks;
		uint8_t frame[BRIDGE_MAX_PACKET_BYTES + BLE_BRIDGE_CHUNK_HEADER_BYTES];
		const size_t count = bridgeChunkCount(packet.length, chunkData);
		for (size_t i = 0; i < count; i++) {
			const size_t n = bridgeBuildChunk(packet.data, packet.length, chunkData, i, frame);
			chunks.emplace_back(frame, frame + n);
		}
		core.countNotified();
		return chunks;
	}
};

constexpr size_t MTU_23_CHUNK = 23 - 3 - 2;    // smallest BLE MTU
constexpr size_t MTU_247_CHUNK = 247 - 3 - 2;  // BLE_BRIDGE_TARGET_MTU

void setUp() {
	fakeNow = 1000;
}
void tearDown() {}

void phoneChunksReachRadioAsRawPacketBytes() {
	Bridge a;
	const Bytes packet = meshPacket(1, 120);
	TEST_ASSERT_TRUE(a.phoneWrites(phoneSplit(packet, MTU_23_CHUNK)));  // 7 chunks
	TEST_ASSERT_EQUAL(1, a.radio.sent.size());
	TEST_ASSERT_EQUAL(packet.size(), a.radio.sent[0].size());
	TEST_ASSERT_EQUAL_UINT8_ARRAY(packet.data(), a.radio.sent[0].data(), packet.size());
	TEST_ASSERT_EQUAL(1, a.core.stats().loraTx);
}

void radioPacketReachesPhoneAsNotificationChunks() {
	Bridge b;
	const Bytes packet = meshPacket(2, 200);
	b.radio.inbox.push_back(packet);
	const std::vector<Bytes> chunks = b.pollForPhone(MTU_23_CHUNK);
	TEST_ASSERT_EQUAL(12, chunks.size());  // ceil(200 / 18)
	const Bytes joined = phoneJoin(chunks);
	TEST_ASSERT_EQUAL(packet.size(), joined.size());
	TEST_ASSERT_EQUAL_UINT8_ARRAY(packet.data(), joined.data(), packet.size());
	TEST_ASSERT_EQUAL(1, b.core.stats().loraRx);
}

void phoneToPhoneAcrossTwoBridges() {
	Bridge a, b;
	a.radio.peer = &b.radio;
	b.radio.peer = &a.radio;

	const Bytes packet = meshPacket(3, BRIDGE_MAX_PACKET_BYTES);  // 255, the LoRa limit
	TEST_ASSERT_TRUE(a.phoneWrites(phoneSplit(packet, MTU_247_CHUNK)));
	const Bytes received = phoneJoin(b.pollForPhone(MTU_247_CHUNK));
	TEST_ASSERT_EQUAL(packet.size(), received.size());
	TEST_ASSERT_EQUAL_UINT8_ARRAY(packet.data(), received.data(), packet.size());

	// And the reply goes back the other way.
	const Bytes reply = meshPacket(4, 60);
	TEST_ASSERT_TRUE(b.phoneWrites(phoneSplit(reply, MTU_23_CHUNK)));
	const Bytes replyReceived = phoneJoin(a.pollForPhone(MTU_23_CHUNK));
	TEST_ASSERT_EQUAL_UINT8_ARRAY(reply.data(), replyReceived.data(), reply.size());
}

void packetEchoedBackByPhoneIsNotRetransmitted() {
	Bridge b;
	const Bytes packet = meshPacket(5, 80);
	b.radio.inbox.push_back(packet);
	TEST_ASSERT_FALSE(b.pollForPhone(MTU_247_CHUNK).empty());
	// Phone B's mesh relays the same packet back to its bridge.
	fakeNow += 500;
	TEST_ASSERT_FALSE(b.phoneWrites(phoneSplit(packet, MTU_247_CHUNK)));
	TEST_ASSERT_EQUAL(0, b.radio.sent.size());
	TEST_ASSERT_EQUAL(1, b.core.stats().dropped);
}

void phoneRetryAfterDedupWindowGoesOutAgain() {
	Bridge a;
	const Bytes packet = meshPacket(6, 40);
	TEST_ASSERT_TRUE(a.phoneWrites(phoneSplit(packet, MTU_247_CHUNK)));
	fakeNow += BRIDGE_DEDUP_WINDOW_MS;
	TEST_ASSERT_TRUE(a.phoneWrites(phoneSplit(packet, MTU_247_CHUNK)));
	TEST_ASSERT_EQUAL(2, a.radio.sent.size());
}

void oversizePacketIsDroppedNotTruncated() {
	Bridge a;
	TEST_ASSERT_FALSE(a.phoneWrites(phoneSplit(meshPacket(7, BRIDGE_MAX_PACKET_BYTES + 1), MTU_23_CHUNK)));
	TEST_ASSERT_EQUAL(0, a.radio.sent.size());
}

void packetWithoutPacketIdIsDropped() {
	Bridge a, b;
	TEST_ASSERT_FALSE(a.phoneWrites(phoneSplit(meshPacket(8, BRIDGE_MIN_PACKET_BYTES - 1), MTU_247_CHUNK)));
	TEST_ASSERT_EQUAL(0, a.radio.sent.size());

	b.radio.inbox.push_back(Bytes{'h', 'i'});  // e.g. a lora-ping text message
	TEST_ASSERT_TRUE(b.pollForPhone(MTU_247_CHUNK).empty());
	TEST_ASSERT_EQUAL(1, b.core.stats().dropped);
}

void outOfOrderChunksAreDropped() {
	Bridge a;
	std::vector<Bytes> chunks = phoneSplit(meshPacket(9, 60), MTU_23_CHUNK);
	std::swap(chunks[1], chunks[2]);
	TEST_ASSERT_FALSE(a.phoneWrites(chunks));
	TEST_ASSERT_EQUAL(0, a.radio.sent.size());
}

void radioTxFailureIsCountedAsDrop() {
	Bridge a;
	a.radio.nextTxResult = -2;
	TEST_ASSERT_FALSE(a.phoneWrites(phoneSplit(meshPacket(10, 40), MTU_247_CHUNK)));
	TEST_ASSERT_EQUAL(1, a.core.stats().dropped);
	TEST_ASSERT_EQUAL(0, a.core.stats().loraTx);
}

void bleOnlyModeDropsPhonePacketsWithoutRadio() {
	Bridge a;
	BridgePacket packet;
	const Bytes bytes = meshPacket(11, 40);
	packet.length = bytes.size();
	memcpy(packet.data, bytes.data(), bytes.size());
	a.core.dropWithoutRadio(packet);
	TEST_ASSERT_EQUAL(0, a.radio.sent.size());
	TEST_ASSERT_EQUAL(1, a.core.stats().dropped);
}

}

int main() {
	UNITY_BEGIN();
	RUN_TEST(phoneChunksReachRadioAsRawPacketBytes);
	RUN_TEST(radioPacketReachesPhoneAsNotificationChunks);
	RUN_TEST(phoneToPhoneAcrossTwoBridges);
	RUN_TEST(packetEchoedBackByPhoneIsNotRetransmitted);
	RUN_TEST(phoneRetryAfterDedupWindowGoesOutAgain);
	RUN_TEST(oversizePacketIsDroppedNotTruncated);
	RUN_TEST(packetWithoutPacketIdIsDropped);
	RUN_TEST(outOfOrderChunksAreDropped);
	RUN_TEST(radioTxFailureIsCountedAsDrop);
	RUN_TEST(bleOnlyModeDropsPhonePacketsWithoutRadio);
	return UNITY_END();
}
