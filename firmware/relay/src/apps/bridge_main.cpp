// NEYRA-LORA bridge: phone <-> BLE <-> this ESP32 <-> LoRa. Packets written to
// 7f6d2002 are transmitted over LoRa; LoRa packets are notified on 7f6d2003.
//
// Serial commands (115200 baud):
//   loracheck      re-run radio bring-up now (RST pulse, version read, RadioLib begin)
//   status         radio state, connection, subscription and packet counters
//   send <text>    raw LoRa text "<label>:<seq>:<text>" like lora-ping (no dedup, not a mesh packet)
//   notify <hex>   send bytes to the subscribed phone, e.g. "notify 48 45 4C 4C 4F"
//   help
//
// The radio never halts the bridge: if bring-up fails it runs BLE-only and
// retries every BRIDGE_RADIO_RETRY_MS.
#include <Arduino.h>

#include "ble/BleBridge.h"
#include "bridge/BridgeRouter.h"
#include "config.h"
#include "diagnostics/Logger.h"
#include "identity/NodeId.h"
#include "lora/LoRaManager.h"

// Set by scripts/git_version.py at build time.
#ifndef BRIDGE_GIT_COMMIT
#define BRIDGE_GIT_COMMIT "unknown"
#endif

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
	Serial.println("Commands: loracheck, status, send <text>, notify <hex>, help");
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
		const BridgeRouter::RadioState radio = router.radioState();
		LORADBG("STATUS", "commit=%s node=%s radio=%s version=0x%02X code=%d nextRetryS=%lu",
				BRIDGE_GIT_COMMIT, nodeId.c_str(), radio.available ? "OK" : "FAILED", radio.version, radio.code,
				static_cast<unsigned long>(radio.nextRetryInMs / 1000));
		LORADBG("STATUS", "connected=%d subscribed=%d loraTx=%lu loraRx=%lu notified=%lu dropped=%lu",
				bridge.isConnected(), bridge.isSubscribed(),
				static_cast<unsigned long>(stats.loraTx), static_cast<unsigned long>(stats.loraRx),
				static_cast<unsigned long>(stats.notified), static_cast<unsigned long>(stats.dropped));
	} else if (command == "loracheck") {
		if (!router.requestLoraCheck()) {
			Serial.println("loracheck: busy, try again");
		}
	} else if (command.startsWith("send ")) {
		if (!router.requestSend(command.substring(5))) {
			Serial.printf("send: need 1-%u characters (or busy, try again)\n",
						  static_cast<unsigned>(BridgeRouter::MAX_SEND_TEXT));
		}
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
	LORADBG("BOOT", "NEYRA-LORA bridge commit=%s built=%s %s node=%s", BRIDGE_GIT_COMMIT, __DATE__, __TIME__,
			nodeId.c_str());
	printHelp();

	LORADBG("BOOT", "lora freq=%.1fMHz bw=%.1fkHz sf=%d cr=4/%d sync=0x%02X power=%ddBm preamble=%d",
			LORA_FREQUENCY_MHZ, LORA_BANDWIDTH_KHZ, LORA_SPREADING_FACTOR, LORA_CODING_RATE,
			LORA_SYNC_WORD, LORA_TX_POWER_DBM, LORA_PREAMBLE_LENGTH);
	LORADBG("BOOT", "lora spi sck=%d miso=%d mosi=%d nss=%d rst=%d dio0=%d",
			LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_CS_PIN, LORA_RESET_PIN, LORA_DIO0_PIN);
	// Radio first, before BLE, exactly like lora-ping. A failure never stops the
	// bridge: BLE still comes up and the LoRa task retries in the background.
	const RadioBringUpResult radio = radioBringUp(lora);

	if (!bridge.begin(nodeId)) {
		halt("BLE bridge init failed");
	}
	// Label for "send": last 6 hex digits of the BT MAC, so two boards differ.
	const String sendLabel = "bridge-" + nodeId.substring(nodeId.length() - 6);
	if (!router.begin(bridge, lora, radio, sendLabel)) {
		halt("router start failed");
	}
}

void loop() {
	handleSerialCommand();
	delay(20);
}
