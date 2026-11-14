#pragma once

#include <cstddef>
#include <cstdint>

#ifdef ARDUINO
#include <Arduino.h>
#define LORADBG_PRINTF Serial.printf
#else
#include <cstdio>  // host tests print to stdout
#define LORADBG_PRINTF printf
#endif

// Serial trace for the BLE <-> LoRa bridge. Every line starts with
// "LORADBG <STAGE>" so a serial monitor can be filtered by stage:
//   BOOT  startup and configuration
//   BLE   connect/disconnect, MTU, subscribe, write chunks, notify
//   RADIO radio bring-up: per-attempt version/code, then RADIO OK or RADIO FAILED
//   LORA  radio TX/RX with RSSI/SNR
//   STATUS reply to the "status" serial command
//   DROP  anything discarded, always with reason=<CODE>
#define LORADBG(stage, fmt, ...) LORADBG_PRINTF("LORADBG " stage " " fmt "\n", ##__VA_ARGS__)

// Prints "LORADBG <stage> <label> bytes=<n> hex=<..>" on one line.
void loradbgHex(const char* stage, const char* label, const uint8_t* data, size_t length);
