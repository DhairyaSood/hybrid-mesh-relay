#pragma once

#include <cstdint>

#include "config.h"

// One whole, opaque mesh packet as written by the phone or received over LoRa.
struct BridgePacket {
	uint16_t length;
	uint8_t data[BRIDGE_MAX_PACKET_BYTES];
};
