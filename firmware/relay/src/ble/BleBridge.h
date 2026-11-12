#pragma once

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "config.h"

class NimBLEServer;
class NimBLECharacteristic;

// One whole, opaque mesh packet as written by the phone or received over LoRa.
struct BridgePacket {
	uint16_t length;
	uint8_t data[BRIDGE_MAX_PACKET_BYTES];
};

// BLE GATT peripheral that carries raw mesh packet bytes between a phone and
// this board. Framing on both characteristics (see protocol/packet-format.md):
//   chunkIndex(1) chunkCount(1) data(N)
// The bridge never decodes or re-encodes the packet bytes.
//
// Threading: write/connect callbacks run on the NimBLE host task and only
// reassemble and enqueue. Consumers call receiveFromPhone()/sendToPhone() from
// their own task.
class BleBridge {
public:
	bool begin(const String& nodeId);

	// Next complete packet the phone wrote. Waits up to waitTicks.
	bool receiveFromPhone(BridgePacket& out, TickType_t waitTicks = 0);

	// Chunks by the negotiated MTU and notifies the subscribed phone.
	// Blocks briefly when the BLE stack is out of buffers; never call from a BLE callback.
	bool sendToPhone(const uint8_t* data, size_t length);

	bool isConnected() const { return connHandle != NO_CONNECTION; }
	bool isSubscribed() const { return subscribed; }

	// Called from NimBLE callbacks.
	void onConnect(uint16_t handle, const char* address, uint16_t mtu);
	void onDisconnect(uint16_t handle, int reason);
	void onMtuChange(uint16_t mtu);
	void onSubscribe(uint16_t subValue);
	void onWrite(const uint8_t* data, size_t length);

private:
	static constexpr uint16_t NO_CONNECTION = 0xFFFF;

	void resetReassembly();

	NimBLEServer* server = nullptr;
	NimBLECharacteristic* notifyCharacteristic = nullptr;
	QueueHandle_t fromPhone = nullptr;
	volatile uint16_t connHandle = NO_CONNECTION;
	volatile bool subscribed = false;

	// Reassembly state; touched only on the NimBLE host task.
	uint8_t rxBuffer[BRIDGE_MAX_PACKET_BYTES];
	uint16_t rxLength = 0;
	uint16_t rxDiscardedBytes = 0;
	uint8_t rxChunkCount = 0;     // 0 = no transfer in progress
	uint8_t rxNextIndex = 0;
	bool rxTooLarge = false;
	uint32_t rxLastChunkMs = 0;
};
