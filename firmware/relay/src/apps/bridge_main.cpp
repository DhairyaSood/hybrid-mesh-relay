// NEYRA-LORA bridge: phone <-> BLE <-> this ESP32 <-> LoRa. Packets written to
// 7f6d2002 are transmitted over LoRa; LoRa packets are notified on 7f6d2003.
//
// Serial commands (115200 baud):
//   notify <hex>   send bytes to the subscribed phone, e.g. "notify 48 45 4C 4C 4F"
//   status         connection, subscription and packet counters
//   help
#include <Arduino.h>

#include "ble/BleBridge.h"
#include "bridge/BridgeRouter.h"
#include "config.h"
#include "diagnostics/Logger.h"
#include "identity/NodeId.h"
#include "lora/LoRaManager.h"

namespace {

BleBridge bridge;
LoRaManager lora;
BridgeRouter router;
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
		const BridgeRouter::Stats stats = router.stats();
		LORADBG("BOOT", "node=%s loraAvailable=%d connected=%d subscribed=%d loraTx=%lu loraRx=%lu notified=%lu dropped=%lu",
				nodeId.c_str(), router.isLoRaAvailable(), bridge.isConnected(), bridge.isSubscribed(),
				static_cast<unsigned long>(stats.loraTx), static_cast<unsigned long>(stats.loraRx),
				static_cast<unsigned long>(stats.notified), static_cast<unsigned long>(stats.dropped));
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

void halt(const char* what) {
	LORADBG("BOOT", "%s, halting", what);
	while (true) {
		delay(1000);
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

	LORADBG("BOOT", "lora freq=%.1fMHz bw=%.1fkHz sf=%d cr=4/%d sync=0x%02X power=%ddBm preamble=%d",
			LORA_FREQUENCY_MHZ, LORA_BANDWIDTH_KHZ, LORA_SPREADING_FACTOR, LORA_CODING_RATE,
			LORA_SYNC_WORD, LORA_TX_POWER_DBM, LORA_PREAMBLE_LENGTH);
	LORADBG("BOOT", "lora spi sck=%d miso=%d mosi=%d nss=%d rst=%d dio0=%d",
			LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_CS_PIN, LORA_RESET_PIN, LORA_DIO0_PIN);
	const int16_t loraState = lora.begin();
	const bool loraAvailable = loraState == RADIOLIB_ERR_NONE;
	LORADBG("BOOT", "lora init code=%d (%s)", loraState,
			loraAvailable ? "ok"
			: loraState == RADIOLIB_ERR_CHIP_NOT_FOUND ? "CHIP_NOT_FOUND: no SPI answer, check wiring/power"
													   : "failed, see RadioLib TypeDef.h");
	const uint8_t version = lora.readVersionRegister();
	LORADBG("BOOT", "lora chip version=0x%02X (%s)", version,
			version == 0x12 ? "SX1278 answered"
			: version == 0x00 || version == 0xFF ? "no answer: wiring/power, flash radiodiag"
												 : "unexpected value: wrong chip or noisy SPI");
	if (!loraAvailable) {
		// Keep BLE up so the phone side (and "notify <hex>") can be tested without a radio.
		LORADBG("BOOT", "LoRa unavailable code=%d, running BLE-only", loraState);
	}
	if (!bridge.begin(nodeId)) {
		halt("BLE bridge init failed");
	}
	if (!router.begin(bridge, lora, loraAvailable)) {
		halt("router start failed");
	}
}

void loop() {
	handleSerialCommand();
	delay(20);
}
