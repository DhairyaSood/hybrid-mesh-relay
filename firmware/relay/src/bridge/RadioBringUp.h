#pragma once

#include <cstdint>

#include "lora/LoRaManager.h"

struct RadioBringUpResult {
	bool ok;
	uint8_t version;  // last raw read of register 0x42 (0x12 = SX1278 answered)
	int16_t code;     // last RadioLib begin() result (0 = ok, -2 = chip not found)
	int attempts;
};

// Up to BRIDGE_RADIO_INIT_ATTEMPTS times: probeVersion() (RST pulse + raw SPI
// read of 0x42), then RadioLib begin(). Logs one line per attempt
//   LORADBG RADIO attempt=n version=0x.. code=..
// and a summary: "LORADBG RADIO OK ..." or "LORADBG RADIO FAILED ...".
// Call only from the code that owns the radio (setup() or the LoRa task).
RadioBringUpResult radioBringUp(LoRaManager& lora);
