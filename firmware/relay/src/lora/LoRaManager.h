#pragma once

#include <Arduino.h>
#include <RadioLib.h>

#include "RadioPort.h"

// Radio init and settings match the divanshi branch (proven LoRa <-> LoRa on the
// team's boards): same pins, same SPI.begin(), same RadioLib begin() values.
class LoRaManager : public RadioPort {
public:
	LoRaManager();

	int16_t begin();
	// Diagnostic probe, run BEFORE begin(): pulses RST (LOW 10 ms, HIGH 10 ms),
	// waits 100 ms, then reads SX1278 register 0x42 over raw SPI (expect 0x12).
	// Resets the chip, so never call it while the radio is in use.
	uint8_t probeVersion();
	int16_t transmit(const String& message);
	int16_t receive(String& message);

	// Binary packets for the BLE bridge. receive() returns RADIOLIB_ERR_RX_TIMEOUT
	// when no packet is waiting (DIO0 low) and never blocks.
	int16_t transmit(const uint8_t* data, size_t length) override;
	int16_t receive(uint8_t* data, size_t capacity, size_t& length) override;
	uint32_t timeOnAirMs(size_t length) override;
	float lastPacketRssi() override;
	float lastPacketSnr() override;

private:
	SX1278 radio;
};
