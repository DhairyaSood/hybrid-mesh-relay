#include "Logger.h"

#include <cstdio>

void loradbgHex(const char* stage, const char* label, const uint8_t* data, size_t length) {
	// Built into one buffer and written once so lines from different tasks do not interleave.
	static constexpr char HEX_DIGITS[] = "0123456789ABCDEF";
	char line[96 + 2 * 256];
	int used = snprintf(line, sizeof(line), "LORADBG %s %s bytes=%u hex=", stage, label,
						static_cast<unsigned>(length));
	for (size_t i = 0; i < length && used + 3 < static_cast<int>(sizeof(line)); i++) {
		line[used++] = HEX_DIGITS[data[i] >> 4];
		line[used++] = HEX_DIGITS[data[i] & 0x0F];
	}
	line[used++] = '\n';
#ifdef ARDUINO
	Serial.write(reinterpret_cast<const uint8_t*>(line), used);
#else
	fwrite(line, 1, used, stdout);
#endif
}
