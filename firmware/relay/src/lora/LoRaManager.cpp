#include "LoRaManager.h"

#include <SPI.h>

#include "config.h"

LoRaManager::LoRaManager()
	: radio(new Module(LORA_CS_PIN, LORA_DIO0_PIN, LORA_RESET_PIN, RADIOLIB_NC)) {}

int16_t LoRaManager::begin() {
	SPI.begin(LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_CS_PIN);
	pinMode(LORA_DIO0_PIN, INPUT);

	const int16_t state = radio.begin(LORA_FREQUENCY_MHZ);
	return state == RADIOLIB_ERR_NONE ? radio.startReceive() : state;
}

int16_t LoRaManager::transmit(const String& message) {
	const int16_t state = radio.transmit(message.c_str());
	const int16_t receiveState = radio.startReceive();
	return state == RADIOLIB_ERR_NONE ? receiveState : state;
}

int16_t LoRaManager::receive(String& message) {
	if (digitalRead(LORA_DIO0_PIN) == LOW) {
		return RADIOLIB_ERR_RX_TIMEOUT;
	}

	const int16_t state = radio.readData(message);
	const int16_t receiveState = radio.startReceive();
	if (state != RADIOLIB_ERR_NONE) {
		return state;
	}
	return receiveState;
}

float LoRaManager::lastPacketRssi() {
	return radio.getRSSI();
}

float LoRaManager::lastPacketSnr() {
	return radio.getSNR();
}
