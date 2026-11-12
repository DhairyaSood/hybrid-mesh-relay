#pragma once

// Label used by the lora-ping test in its text messages. Override per board with
//   PLATFORMIO_BUILD_FLAGS='-DLORA_NODE_ID=\"node-b\"' pio run -e lora-ping -t upload
#ifndef LORA_NODE_ID
#define LORA_NODE_ID "node-a"
#endif

// ---------------------------------------------------------------------------
// LoRa radio parameters. BOTH boards must be flashed with identical values,
// otherwise they cannot hear each other.
// ---------------------------------------------------------------------------

// Single frequency setting for the whole firmware.
//
// India: 433 MHz is not in the licence-free band; 865-867 MHz is the usual
// licence-free LoRa band. The AI-Thinker RA-02 (SX1278) tunes only 410-525 MHz
// and cannot operate at 865-867 MHz; that needs an SX1276 module (e.g. RA-01H)
// and LORA_MODULE_MAX_MHZ raised accordingly.
#define LORA_FREQUENCY_MHZ 433.0

// RA-02 / SX1278 supported range; checked at compile time below.
#define LORA_MODULE_MIN_MHZ 410.0
#define LORA_MODULE_MAX_MHZ 525.0

// These equal RadioLib's SX1278::begin() defaults, which the ping test used
// implicitly before they were written out here.
#define LORA_BANDWIDTH_KHZ 125.0
#define LORA_SPREADING_FACTOR 9
#define LORA_CODING_RATE 7          // 4/7
#define LORA_SYNC_WORD 0x12         // private-network sync word
#define LORA_TX_POWER_DBM 10
#define LORA_PREAMBLE_LENGTH 8

static_assert(LORA_FREQUENCY_MHZ >= LORA_MODULE_MIN_MHZ && LORA_FREQUENCY_MHZ <= LORA_MODULE_MAX_MHZ,
			  "LORA_FREQUENCY_MHZ is outside the RA-02 (SX1278) range; see the note above");

// RA-02 wiring to the ESP32 DevKit (VSPI).
#define LORA_SCK_PIN 18
#define LORA_MISO_PIN 19
#define LORA_MOSI_PIN 23
#define LORA_CS_PIN 5
#define LORA_RESET_PIN 14
#define LORA_DIO0_PIN 26
