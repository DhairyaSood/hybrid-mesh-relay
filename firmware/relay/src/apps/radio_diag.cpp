// RA-02 (SX1278) wiring diagnostic. Build/flash with: pio run -e radiodiag -t upload
//
// Every 5 s, for each candidate pin set, it reads the SX1278 version register
// (0x42, expected 0x12) over raw SPI. Where the chip answers it also checks:
//   rst:  holding RST low must silence the chip, releasing it must bring it back
//   dio0: a LoRa CAD (channel activity detection, receive only) must raise DIO0
// It never transmits. Output lines start with "RADIODIAG".
//
// Independent of the bridge and lora-ping code; uses no RadioLib.
#include <Arduino.h>
#include <SPI.h>

#include "config.h"

namespace {

struct PinSet {
	int sck, miso, mosi, nss, rst, dio0;
	const char* note;
};

const PinSet CANDIDATES[] = {
	{LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_CS_PIN, LORA_RESET_PIN, LORA_DIO0_PIN, "config.h default"},
	{18, 19, 23, 5, 14, 2, "dio0 on 2"},
	{18, 19, 23, 5, 27, 26, "rst on 27"},
	{18, 19, 23, 15, 14, 26, "nss on 15"},
	{18, 19, 23, 4, 14, 26, "nss on 4"},
	{5, 19, 27, 18, 14, 26, "TTGO-style sck 5, mosi 27, nss 18"},
	{LORA_SCK_PIN, LORA_MOSI_PIN, LORA_MISO_PIN, LORA_CS_PIN, LORA_RESET_PIN, LORA_DIO0_PIN, "default with MISO/MOSI swapped"},
};
constexpr size_t CANDIDATE_COUNT = sizeof(CANDIDATES) / sizeof(CANDIDATES[0]);

constexpr uint8_t REG_OP_MODE = 0x01;
constexpr uint8_t REG_IRQ_FLAGS = 0x12;
constexpr uint8_t REG_DIO_MAPPING_1 = 0x40;
constexpr uint8_t REG_VERSION = 0x42;
constexpr uint8_t SX1278_VERSION = 0x12;

constexpr uint8_t MODE_FSK_SLEEP = 0x00;
constexpr uint8_t MODE_LORA_SLEEP = 0x80;
constexpr uint8_t MODE_LORA_STANDBY = 0x81;
constexpr uint8_t MODE_LORA_CAD = 0x87;
constexpr uint8_t DIO0_CAD_DONE = 0x80;

constexpr uint32_t CYCLE_MS = 5000;

SPISettings spiSettings(1000000, MSBFIRST, SPI_MODE0);

// Every pin any candidate uses; all are released to INPUT between candidates so
// one guess never drives a line another guess reads.
const int ALL_PINS[] = {2, 4, 5, 14, 15, 18, 19, 23, 26, 27};

void releaseAllPins() {
	for (int pin : ALL_PINS) {
		pinMode(pin, INPUT);
	}
}

uint8_t readRegister(const PinSet& p, uint8_t reg) {
	SPI.beginTransaction(spiSettings);
	digitalWrite(p.nss, LOW);
	SPI.transfer(reg & 0x7F);
	const uint8_t value = SPI.transfer(0x00);
	digitalWrite(p.nss, HIGH);
	SPI.endTransaction();
	return value;
}

void writeRegister(const PinSet& p, uint8_t reg, uint8_t value) {
	SPI.beginTransaction(spiSettings);
	digitalWrite(p.nss, LOW);
	SPI.transfer(reg | 0x80);
	SPI.transfer(value);
	digitalWrite(p.nss, HIGH);
	SPI.endTransaction();
}

void pulseReset(int rst) {
	pinMode(rst, OUTPUT);
	digitalWrite(rst, LOW);
	delay(1);
	digitalWrite(rst, HIGH);
	delay(10);  // SX1278 needs 5 ms after reset before SPI access
}

// RST is right if holding it low silences the chip and releasing it brings it back.
bool checkReset(const PinSet& p) {
	digitalWrite(p.rst, LOW);
	delay(2);
	const uint8_t held = readRegister(p, REG_VERSION);
	digitalWrite(p.rst, HIGH);
	delay(10);
	return held != SX1278_VERSION && readRegister(p, REG_VERSION) == SX1278_VERSION;
}

// DIO0 is right if a CAD (listen only, never transmits) raises it when done.
bool checkDio0(const PinSet& p) {
	pinMode(p.dio0, INPUT);
	writeRegister(p, REG_OP_MODE, MODE_FSK_SLEEP);
	writeRegister(p, REG_OP_MODE, MODE_LORA_SLEEP);  // LoRa bit can only change in sleep
	writeRegister(p, REG_OP_MODE, MODE_LORA_STANDBY);
	writeRegister(p, REG_DIO_MAPPING_1, DIO0_CAD_DONE);
	writeRegister(p, REG_IRQ_FLAGS, 0xFF);
	const bool lowBefore = digitalRead(p.dio0) == LOW;
	writeRegister(p, REG_OP_MODE, MODE_LORA_CAD);

	bool raised = false;
	const uint32_t start = millis();
	while (millis() - start < 100) {
		if (digitalRead(p.dio0) == HIGH) {
			raised = true;
			break;
		}
		delay(1);
	}
	writeRegister(p, REG_IRQ_FLAGS, 0xFF);
	writeRegister(p, REG_OP_MODE, MODE_LORA_SLEEP);
	return lowBefore && raised;
}

struct Result {
	uint8_t version;
	bool found, rstOk, dio0Ok;
};

Result probe(const PinSet& p) {
	releaseAllPins();
	SPI.begin(p.sck, p.miso, p.mosi, -1);
	pinMode(p.nss, OUTPUT);
	digitalWrite(p.nss, HIGH);
	pulseReset(p.rst);

	Result r{readRegister(p, REG_VERSION), false, false, false};
	r.found = r.version == SX1278_VERSION;
	if (r.found) {
		r.rstOk = checkReset(p);
		r.dio0Ok = checkDio0(p);
	}
	SPI.end();
	releaseAllPins();
	return r;
}

void printPins(const PinSet& p) {
	Serial.printf("sck=%d miso=%d mosi=%d nss=%d rst=%d dio0=%d", p.sck, p.miso, p.mosi, p.nss, p.rst, p.dio0);
}

void runCycle() {
	Serial.println("RADIODIAG ---- scanning candidate pin sets ----");
	int best = -1;
	int bestScore = 0;
	for (size_t i = 0; i < CANDIDATE_COUNT; i++) {
		const PinSet& p = CANDIDATES[i];
		const Result r = probe(p);
		Serial.print("RADIODIAG ");
		printPins(p);
		Serial.printf(" version=0x%02X", r.version);
		if (r.found) {
			Serial.printf(" FOUND rst=%s dio0=%s", r.rstOk ? "ok" : "FAIL", r.dio0Ok ? "ok" : "FAIL");
			const int score = 1 + (r.rstOk ? 1 : 0) + (r.dio0Ok ? 1 : 0);
			if (score > bestScore) {
				bestScore = score;
				best = static_cast<int>(i);
			}
		}
		Serial.printf("  (%s)\n", p.note);
	}

	if (best < 0) {
		Serial.println("RADIODIAG RESULT no chip answered: check 3.3V, GND and wiring");
		return;
	}
	Serial.print("RADIODIAG RESULT pins=");
	printPins(CANDIDATES[best]);
	if (bestScore < 3) {
		Serial.print("  (chip answers, but rst or dio0 did not check out: re-check those wires)");
	}
	Serial.println();
}

}

void setup() {
	Serial.begin(115200);
	delay(500);
	Serial.println("RADIODIAG RA-02 wiring diagnostic, never transmits. Expected version 0x12.");
}

void loop() {
	runCycle();
	delay(CYCLE_MS);
}
