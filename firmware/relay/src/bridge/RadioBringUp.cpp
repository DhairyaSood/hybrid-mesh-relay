#include "RadioBringUp.h"

#include "config.h"
#include "diagnostics/Logger.h"

namespace {

const char* failureHint(uint8_t version) {
	if (version == 0x00 || version == 0xFF) {
		return "no SPI answer: check wiring/power, flash radiodiag";
	}
	if (version == 0x12) {
		return "chip answers but RadioLib init failed";
	}
	return "unexpected version: wrong chip or noisy SPI";
}

}

RadioBringUpResult radioBringUp(LoRaManager& lora) {
	RadioBringUpResult result{false, 0, 0, 0};
	for (int attempt = 1; attempt <= BRIDGE_RADIO_INIT_ATTEMPTS; attempt++) {
		result.attempts = attempt;
		result.version = lora.probeVersion();
		result.code = lora.begin();
		LORADBG("RADIO", "attempt=%d version=0x%02X code=%d", attempt, result.version, result.code);
		if (result.code == RADIOLIB_ERR_NONE) {
			result.ok = true;
			break;
		}
	}

	if (result.ok) {
		LORADBG("RADIO", "OK version=0x%02X attempts=%d", result.version, result.attempts);
	} else {
		LORADBG("RADIO", "FAILED version=0x%02X code=%d attempts=%d (%s); BLE-only, retry every %d s",
				result.version, result.code, result.attempts, failureHint(result.version),
				BRIDGE_RADIO_RETRY_MS / 1000);
	}
	return result;
}
