#include <unity.h>

#include <cstring>

#include "routing/DedupCache.h"

namespace {

uint8_t id(uint8_t seed, uint8_t out[MESH_PACKET_ID_BYTES]) {
	for (int i = 0; i < MESH_PACKET_ID_BYTES; i++) out[i] = static_cast<uint8_t>(seed + i);
	return seed;
}

void firstSightingIsNotDuplicate() {
	DedupCache cache;
	uint8_t a[16];
	id(1, a);
	TEST_ASSERT_FALSE(cache.checkAndRecord(a, 1000));
}

void echoWithinWindowIsDuplicate() {
	DedupCache cache;
	uint8_t a[16];
	id(1, a);
	cache.checkAndRecord(a, 1000);
	TEST_ASSERT_TRUE(cache.checkAndRecord(a, 1000 + BRIDGE_DEDUP_WINDOW_MS - 1));
}

void retryAfterWindowIsForwarded() {
	DedupCache cache;
	uint8_t a[16];
	id(1, a);
	cache.checkAndRecord(a, 1000);
	TEST_ASSERT_FALSE(cache.checkAndRecord(a, 1000 + BRIDGE_DEDUP_WINDOW_MS));
}

void differentIdsAreIndependent() {
	DedupCache cache;
	uint8_t a[16], b[16];
	id(1, a);
	id(2, b);
	cache.checkAndRecord(a, 1000);
	TEST_ASSERT_FALSE(cache.checkAndRecord(b, 1001));
}

void windowSurvivesMillisWrap() {
	DedupCache cache;
	uint8_t a[16];
	id(1, a);
	cache.checkAndRecord(a, 0xFFFFFF00u);
	TEST_ASSERT_TRUE(cache.checkAndRecord(a, 0x00000100u));
}

void oldestEntryIsEvictedWhenFull() {
	DedupCache cache;
	uint8_t first[16];
	id(0, first);
	cache.checkAndRecord(first, 1000);
	for (int i = 1; i <= BRIDGE_DEDUP_CAPACITY; i++) {
		uint8_t other[16];
		id(static_cast<uint8_t>(i), other);  // seeds 1..32 differ from seed 0 in byte 0
		cache.checkAndRecord(other, 1000);
	}
	TEST_ASSERT_FALSE(cache.checkAndRecord(first, 1001));
}

void packetIdIsFormattedFromBytes4To19() {
	uint8_t packet[20] = {'N', 'M', 2, 1};
	for (int i = 0; i < 16; i++) packet[4 + i] = static_cast<uint8_t>(0xA0 + i);
	char text[41];
	formatPacketId(packet, text);
	TEST_ASSERT_EQUAL_STRING("HMR-a0a1a2a3-a4a5-a6a7-a8a9-aaabacadaeaf", text);
}

}

int main() {
	UNITY_BEGIN();
	RUN_TEST(firstSightingIsNotDuplicate);
	RUN_TEST(echoWithinWindowIsDuplicate);
	RUN_TEST(retryAfterWindowIsForwarded);
	RUN_TEST(differentIdsAreIndependent);
	RUN_TEST(windowSurvivesMillisWrap);
	RUN_TEST(oldestEntryIsEvictedWhenFull);
	RUN_TEST(packetIdIsFormattedFromBytes4To19);
	return UNITY_END();
}
