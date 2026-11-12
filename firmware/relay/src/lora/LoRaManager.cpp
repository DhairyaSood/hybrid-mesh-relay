#include "LoRaManager.h"

#include <SPI.h>

#include "config.h"

LoRaManager::LoRaManager()
	: radio(new Module(LORA_CS_PIN, LORA_DIO0_PIN, LORA_RESET_PIN, RADIOLIB_NC)) {}

int16_t LoRaManager::begin() {
	SPI.begin(LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_CS_PIN);
	pinMode(LORA_DIO0_PIN, INPUT);

	const int16_t state = radio.begin(LORA_FREQUENCY_MHZ, LORA_BANDWIDTH_KHZ, LORA_SPREADING_FACTOR,
									  LORA_CODING_RATE, LORA_SYNC_WORD, LORA_TX_POWER_DBM,
									  LORA_PREAMBLE_LENGTH);
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

int16_t LoRaManager::transmit(const uint8_t* data, size_t length) {
	const int16_t state = radio.transmit(data, length);
	const int16_t receiveState = radio.startReceive();
	return state == RADIOLIB_ERR_NONE ? receiveState : state;
}

int16_t LoRaManager::receive(uint8_t* data, size_t capacity, size_t& length) {
	length = 0;
	if (digitalRead(LORA_DIO0_PIN) == LOW) {
		return RADIOLIB_ERR_RX_TIMEOUT;
	}

	const size_t received = radio.getPacketLength();
	int16_t state = RADIOLIB_ERR_PACKET_TOO_LONG;
	if (received > 0 && received <= capacity) {
		state = radio.readData(data, received);
	}
	// startReceive() also clears the IRQ flags, including after a rejected packet.
	const int16_t receiveState = radio.startReceive();
	if (state != RADIOLIB_ERR_NONE) {
		return state;
	}
	length = received;
	return receiveState;
}

uint32_t LoRaManager::timeOnAirMs(size_t length) {
	return static_cast<uint32_t>(radio.getTimeOnAir(length) / 1000);
}

float LoRaManager::lastPacketRssi() {
	return radio.getRSSI();
}

float LoRaManager::lastPacketSnr() {
	return radio.getSNR();
}
