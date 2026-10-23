#pragma once

#include <Arduino.h>
#include <RadioLib.h>

class LoRaManager {
public:
	LoRaManager();

	int16_t begin();
	int16_t transmit(const String& message);
	int16_t receive(String& message);
	float lastPacketRssi();
	float lastPacketSnr();

private:
	SX1278 radio;
};
