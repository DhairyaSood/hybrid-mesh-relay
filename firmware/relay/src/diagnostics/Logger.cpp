#include "Logger.h"

void loradbgHex(const char* stage, const char* label, const uint8_t* data, size_t length) {
	Serial.printf("LORADBG %s %s bytes=%u hex=", stage, label, static_cast<unsigned>(length));
	for (size_t i = 0; i < length; i++) {
		Serial.printf("%02X", data[i]);
	}
	Serial.println();
}
