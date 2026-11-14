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

// Proven settings: identical to the divanshi branch, which ran LoRa <-> LoRa on
// the team's boards. divanshi calls radio.begin(LORA_FREQUENCY_MHZ) and takes
// RadioLib's SX1278::begin() defaults for the rest; these are exactly those
// defaults (RadioLib 7.8.1, resolved from ^7.1.2 in both branches), written out
// so all radio settings live in this one file. lora-ping and bridge both use
// LoRaManager, so they are interchangeable on the same pair of boards.
#define LORA_BANDWIDTH_KHZ 125.0
#define LORA_SPREADING_FACTOR 9
#define LORA_CODING_RATE 7          // 4/7
#define LORA_SYNC_WORD 0x12         // private-network sync word
#define LORA_TX_POWER_DBM 10
#define LORA_PREAMBLE_LENGTH 8

static_assert(LORA_FREQUENCY_MHZ >= LORA_MODULE_MIN_MHZ && LORA_FREQUENCY_MHZ <= LORA_MODULE_MAX_MHZ,
			  "LORA_FREQUENCY_MHZ is outside the RA-02 (SX1278) range; see the note above");

// RA-02 wiring to the ESP32 DevKit (VSPI). Same as divanshi since its commit
// 9c4ce35 "fixed pins config" (NSS moved 27 -> 5). A board wired to the older
// pinout (NSS on 27) fails with code -2; radiodiag tests that pin set too.
#define LORA_SCK_PIN 18
#define LORA_MISO_PIN 19
#define LORA_MOSI_PIN 23
#define LORA_CS_PIN 5
#define LORA_RESET_PIN 14
#define LORA_DIO0_PIN 26

// ---------------------------------------------------------------------------
// BLE bridge (phone <-> ESP32). Deliberately separate from the discovery UUID
// 7f6d0001 and the phone-to-phone mesh service 7f6d1001, so existing phones
// never treat the bridge as a mesh peer. Android copies these into
// BleBridgeConstants.
// ---------------------------------------------------------------------------
#define BLE_BRIDGE_DEVICE_NAME "NEYRA-LORA"
#define BLE_BRIDGE_SERVICE_UUID "7f6d2001-7b8e-4b5a-9d11-4c2a8e6f1001"
#define BLE_BRIDGE_WRITE_CHAR_UUID "7f6d2002-7b8e-4b5a-9d11-4c2a8e6f1001"   // phone -> bridge
#define BLE_BRIDGE_NOTIFY_CHAR_UUID "7f6d2003-7b8e-4b5a-9d11-4c2a8e6f1001"  // bridge -> phone
#define BLE_BRIDGE_TARGET_MTU 247
#define BLE_BRIDGE_CHUNK_HEADER_BYTES 2       // chunkIndex(1) + chunkCount(1)
#define BLE_BRIDGE_REASSEMBLY_TIMEOUT_MS 2000
#define BLE_BRIDGE_FROM_PHONE_QUEUE_DEPTH 8

// Largest whole mesh packet the bridge carries: the SX127x LoRa payload limit.
// Larger packets are rejected with a LORADBG log line, never truncated.
#define BRIDGE_MAX_PACKET_BYTES 255

// Mesh packet fields the bridge reads. Everything else stays opaque.
// packetId is bytes 4-19 (after magic "NM", version, type); see protocol/packet-format.md.
#define MESH_PACKET_ID_OFFSET 4
#define MESH_PACKET_ID_BYTES 16
#define BRIDGE_MIN_PACKET_BYTES (MESH_PACKET_ID_OFFSET + MESH_PACKET_ID_BYTES)  // 20

// Dedup: a packetId seen in either direction is not forwarded again within this
// window. Shorter than the phone's 30 s delivery-ACK timeout, so a phone's retry
// of the same packet still goes out over LoRa.
#define BRIDGE_DEDUP_WINDOW_MS 20000
#define BRIDGE_DEDUP_CAPACITY 32

#define BRIDGE_TO_PHONE_QUEUE_DEPTH 8
