// NEYRA-LORA BLE bridge, BLE side only. LoRa forwarding is wired in separately.
//
// Serial commands (115200 baud):
//   notify <hex>   send bytes to the subscribed phone, e.g. "notify 48 45 4C 4C 4F"
//   status         connection and subscription state
//   help
#include <Arduino.h>

#include "ble/BleBridge.h"
#include "config.h"
#include "diagnostics/Logger.h"
#include "identity/NodeId.h"

namespace {

BleBridge bridge;
String nodeId;

int hexValue(char c) {
	if (c >= '0' && c <= '9') return c - '0';
	if (c >= 'a' && c <= 'f') return c - 'a' + 10;
	if (c >= 'A' && c <= 'F') return c - 'A' + 10;
	return -1;
}

// Parses hex digits, ignoring spaces. Returns false on a bad digit, odd digit count or overflow.
bool parseHex(const String& text, uint8_t* out, size_t capacity, size_t& length) {
	length = 0;
	int high = -1;
	for (size_t i = 0; i < text.length(); i++) {
		const char c = text[i];
		if (c == ' ') continue;
		const int value = hexValue(c);
		if (value < 0) return false;
		if (high < 0) {
			high = value;
			continue;
		}
		if (length >= capacity) return false;
		out[length++] = static_cast<uint8_t>((high << 4) | value);
		high = -1;
	}
	return high < 0 && length > 0;
}

void printHelp() {
	Serial.println("Commands: notify <hex>, status, help");
}

void handleSerialCommand() {
	if (!Serial.available()) {
		return;
	}
	String command = Serial.readStringUntil('\n');
	command.trim();

	if (command == "help") {
		printHelp();
	} else if (command == "status") {
		LORADBG("BOOT", "node=%s connected=%d subscribed=%d", nodeId.c_str(),
				bridge.isConnected(), bridge.isSubscribed());
	} else if (command.startsWith("notify ")) {
		uint8_t bytes[BRIDGE_MAX_PACKET_BYTES + 1];
		size_t length = 0;
		if (!parseHex(command.substring(7), bytes, sizeof(bytes), length)) {
			Serial.println("notify: need an even number of hex digits, at most 255 bytes");
			return;
		}
		bridge.sendToPhone(bytes, length);
	} else if (command.length() > 0) {
		Serial.println("Unknown command. Type help.");
	}
}

}

void setup() {
	Serial.begin(115200);
	Serial.setTimeout(50);
	delay(500);

	nodeId = bridgeNodeId();
	LORADBG("BOOT", "NEYRA-LORA bridge node=%s", nodeId.c_str());
	printHelp();

	if (!bridge.begin(nodeId)) {
		LORADBG("BOOT", "BLE bridge init failed, halting");
		while (true) {
			delay(1000);
		}
	}
}

void loop() {
	BridgePacket packet;
	while (bridge.receiveFromPhone(packet)) {
		loradbgHex("BLE", "packet from phone", packet.data, packet.length);
	}
	handleSerialCommand();
	delay(5);
}
