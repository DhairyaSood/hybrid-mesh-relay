#pragma once

#include <cstddef>
#include <cstdint>

// The few radio operations the bridge needs. LoRaManager implements it on the
// ESP32; host tests implement it with a mock, so the bridge logic runs on a PC.
//
// Status codes are RadioLib's own values (checked with static_assert in
// LoRaManager.cpp), so on hardware they are passed through unchanged.
constexpr int16_t RADIO_OK = 0;             // RADIOLIB_ERR_NONE
constexpr int16_t RADIO_NO_PACKET = -6;     // RADIOLIB_ERR_RX_TIMEOUT: nothing waiting
constexpr int16_t RADIO_CRC_MISMATCH = -7;  // RADIOLIB_ERR_CRC_MISMATCH

class RadioPort {
public:
	virtual ~RadioPort() = default;

	virtual int16_t transmit(const uint8_t* data, size_t length) = 0;
	// Never blocks: returns RADIO_NO_PACKET when nothing has been received.
	virtual int16_t receive(uint8_t* data, size_t capacity, size_t& length) = 0;
	virtual uint32_t timeOnAirMs(size_t length) = 0;
	virtual float lastPacketRssi() = 0;
	virtual float lastPacketSnr() = 0;
};
