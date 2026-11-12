#pragma once

#include <Arduino.h>

// Serial trace for the BLE <-> LoRa bridge. Every line starts with
// "LORADBG <STAGE>" so a serial monitor can be filtered by stage:
//   BOOT  startup and configuration
//   BLE   connect/disconnect, MTU, subscribe, write chunks, notify
//   LORA  radio TX/RX with RSSI/SNR
//   DROP  anything discarded, always with reason=<CODE>
#define LORADBG(stage, fmt, ...) Serial.printf("LORADBG " stage " " fmt "\n", ##__VA_ARGS__)

// Prints "LORADBG <stage> <label> bytes=<n> hex=<..>" on one line.
void loradbgHex(const char* stage, const char* label, const uint8_t* data, size_t length);
