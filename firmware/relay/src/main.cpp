#include <Arduino.h>

#include "config.h"
#include "lora/LoRaManager.h"

namespace {

LoRaManager lora;
uint32_t nextSequence = 1;

void printHelp() {
	Serial.println("Commands: send <text>, help");
}

void handleSerialCommand() {
	if (!Serial.available()) {
		return;
	}

	String command = Serial.readStringUntil('\n');
	command.trim();

	if (command == "help") {
		printHelp();
		return;
	}

	if (!command.startsWith("send ")) {
		Serial.println("Unknown command. Type help.");
		return;
	}

	String message = String(LORA_NODE_ID) + ":" + String(nextSequence++) + ":" + command.substring(5);
	const int16_t state = lora.transmit(message);
	if (state == RADIOLIB_ERR_NONE) {
		Serial.println("TX ok: " + message);
	} else {
		Serial.printf("TX failed, RadioLib code %d\n", state);
	}
}

void receivePacket() {
	String message;
	const int16_t state = lora.receive(message);

	if (state == RADIOLIB_ERR_NONE) {
		Serial.printf("RX from %s | RSSI %.1f dBm | SNR %.1f dB\n",
					  message.c_str(), lora.lastPacketRssi(), lora.lastPacketSnr());
	} else if (state != RADIOLIB_ERR_RX_TIMEOUT) {
		Serial.printf("RX failed, RadioLib code %d\n", state);
	}
}

}

void setup() {
	Serial.begin(115200);
	Serial.setTimeout(50);
	delay(500);

	Serial.printf("RA-02 LoRa link test, node %s, %.1f MHz\n",
				  LORA_NODE_ID, LORA_FREQUENCY_MHZ);
	printHelp();

	const int16_t state = lora.begin();
	if (state != RADIOLIB_ERR_NONE) {
		Serial.printf("LoRa init failed, RadioLib code %d\n", state);
		while (true) {
			delay(1000);
		}
	}

	Serial.println("LoRa ready");
}

void loop() {
	handleSerialCommand();
	receivePacket();
}
