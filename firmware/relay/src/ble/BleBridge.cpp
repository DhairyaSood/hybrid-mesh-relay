#include "BleBridge.h"

#include <NimBLEDevice.h>

#include "diagnostics/Logger.h"

namespace {

constexpr uint16_t ATT_NOTIFY_HEADER_BYTES = 3;
constexpr uint16_t MIN_ATT_MTU = 23;
constexpr int NOTIFY_ATTEMPTS = 5;
constexpr uint32_t NOTIFY_RETRY_MS = 10;

class ServerCallbacks : public NimBLEServerCallbacks {
public:
	explicit ServerCallbacks(BleBridge& bridge) : bridge(bridge) {}

	void onConnect(NimBLEServer*, NimBLEConnInfo& info) override {
		bridge.onConnect(info.getConnHandle(), info.getAddress().toString().c_str(), info.getMTU());
	}

	void onDisconnect(NimBLEServer*, NimBLEConnInfo& info, int reason) override {
		bridge.onDisconnect(info.getConnHandle(), reason);
	}

	void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
		bridge.onMtuChange(mtu);
	}

private:
	BleBridge& bridge;
};

class WriteCallbacks : public NimBLECharacteristicCallbacks {
public:
	explicit WriteCallbacks(BleBridge& bridge) : bridge(bridge) {}

	void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
		const NimBLEAttValue value = characteristic->getValue();
		bridge.onWrite(value.data(), value.size());
	}

private:
	BleBridge& bridge;
};

class NotifyCallbacks : public NimBLECharacteristicCallbacks {
public:
	explicit NotifyCallbacks(BleBridge& bridge) : bridge(bridge) {}

	void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo&, uint16_t subValue) override {
		bridge.onSubscribe(subValue);
	}

private:
	BleBridge& bridge;
};

}

bool BleBridge::begin(const String& nodeId) {
	fromPhone = xQueueCreate(BLE_BRIDGE_FROM_PHONE_QUEUE_DEPTH, sizeof(BridgePacket));
	if (fromPhone == nullptr) {
		LORADBG("DROP", "reason=QUEUE_ALLOC_FAILED queue=fromPhone");
		return false;
	}

	NimBLEDevice::init(BLE_BRIDGE_DEVICE_NAME);
	NimBLEDevice::setMTU(BLE_BRIDGE_TARGET_MTU);

	server = NimBLEDevice::createServer();
	server->setCallbacks(new ServerCallbacks(*this));
	server->advertiseOnDisconnect(true);

	NimBLEService* service = server->createService(BLE_BRIDGE_SERVICE_UUID);
	NimBLECharacteristic* writeCharacteristic = service->createCharacteristic(
		BLE_BRIDGE_WRITE_CHAR_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
	writeCharacteristic->setCallbacks(new WriteCallbacks(*this));
	notifyCharacteristic = service->createCharacteristic(BLE_BRIDGE_NOTIFY_CHAR_UUID, NIMBLE_PROPERTY::NOTIFY);
	notifyCharacteristic->setCallbacks(new NotifyCallbacks(*this));
	// No service->start(): NimBLE 2.x starts the GATT server when advertising starts.

	// Only the bridge service UUID and the name. The 128-bit UUID fills most of
	// the 31-byte advertisement, so the name goes in the scan response.
	NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
	advertising->enableScanResponse(true);
	advertising->addServiceUUID(BLE_BRIDGE_SERVICE_UUID);
	advertising->setName(BLE_BRIDGE_DEVICE_NAME);
	if (!advertising->start()) {
		LORADBG("DROP", "reason=ADVERTISING_START_FAILED");
		return false;
	}

	LORADBG("BLE", "advertising name=%s service=%s node=%s addr=%s mtu=%u",
			BLE_BRIDGE_DEVICE_NAME, BLE_BRIDGE_SERVICE_UUID, nodeId.c_str(),
			NimBLEDevice::getAddress().toString().c_str(), BLE_BRIDGE_TARGET_MTU);
	return true;
}

bool BleBridge::receiveFromPhone(BridgePacket& out, TickType_t waitTicks) {
	return fromPhone != nullptr && xQueueReceive(fromPhone, &out, waitTicks) == pdTRUE;
}

void BleBridge::onConnect(uint16_t handle, const char* address, uint16_t mtu) {
	connHandle = handle;
	subscribed = false;
	assembler.reset();
	LORADBG("BLE", "connected addr=%s handle=%u mtu=%u", address, handle, mtu);
}

void BleBridge::onDisconnect(uint16_t handle, int reason) {
	if (assembler.inProgress()) {
		LORADBG("DROP", "reason=DISCONNECTED_MID_TRANSFER got=%u/%u", assembler.chunksReceived(),
				assembler.chunksExpected());
	}
	connHandle = NO_CONNECTION;
	subscribed = false;
	assembler.reset();
	LORADBG("BLE", "disconnected handle=%u reason=0x%X (advertising again)", handle, reason);
}

void BleBridge::onMtuChange(uint16_t mtu) {
	LORADBG("BLE", "mtu=%u notify_chunk_data=%u", mtu,
			mtu - ATT_NOTIFY_HEADER_BYTES - BLE_BRIDGE_CHUNK_HEADER_BYTES);
}

void BleBridge::onSubscribe(uint16_t subValue) {
	subscribed = (subValue & 0x0001) != 0;
	LORADBG("BLE", "notify %s", subscribed ? "subscribed" : "unsubscribed");
}

void BleBridge::onWrite(const uint8_t* data, size_t length) {
	BridgePacket packet;
	if (assembler.onChunk(data, length, millis(), packet) != ChunkAssembler::Result::Complete) {
		return;  // more chunks to come, or already logged as a DROP
	}
	if (xQueueSend(fromPhone, &packet, 0) != pdTRUE) {
		LORADBG("DROP", "reason=QUEUE_FULL queue=fromPhone bytes=%u", packet.length);
		return;
	}
	LORADBG("BLE", "packet from phone complete bytes=%u", packet.length);
}

bool BleBridge::sendToPhone(const uint8_t* data, size_t length) {
	if (length == 0 || length > BRIDGE_MAX_PACKET_BYTES) {
		LORADBG("DROP", "reason=PACKET_TOO_LARGE direction=toPhone bytes=%u max=%u",
				static_cast<unsigned>(length), BRIDGE_MAX_PACKET_BYTES);
		return false;
	}
	const uint16_t handle = connHandle;
	if (handle == NO_CONNECTION) {
		LORADBG("DROP", "reason=NO_PHONE_CONNECTED bytes=%u", static_cast<unsigned>(length));
		return false;
	}
	if (!subscribed) {
		LORADBG("DROP", "reason=PHONE_NOT_SUBSCRIBED bytes=%u", static_cast<unsigned>(length));
		return false;
	}

	uint16_t mtu = server->getPeerMTU(handle);
	if (mtu < MIN_ATT_MTU) {
		mtu = MIN_ATT_MTU;
	}
	const size_t chunkData = mtu - ATT_NOTIFY_HEADER_BYTES - BLE_BRIDGE_CHUNK_HEADER_BYTES;
	const size_t count = bridgeChunkCount(length, chunkData);  // <= 15 even at MTU 23

	uint8_t frame[BLE_BRIDGE_TARGET_MTU];
	for (size_t i = 0; i < count; i++) {
		const size_t frameLength = bridgeBuildChunk(data, length, chunkData, i, frame);

		bool sent = false;
		for (int attempt = 0; attempt < NOTIFY_ATTEMPTS && !sent; attempt++) {
			sent = notifyCharacteristic->notify(frame, frameLength, handle);
			if (!sent) {
				vTaskDelay(pdMS_TO_TICKS(NOTIFY_RETRY_MS));
			}
		}
		if (!sent) {
			LORADBG("DROP", "reason=NOTIFY_FAILED chunk=%u/%u bytes=%u",
					static_cast<unsigned>(i + 1), static_cast<unsigned>(count), static_cast<unsigned>(length));
			return false;
		}
	}

	LORADBG("BLE", "notify sent bytes=%u chunks=%u mtu=%u",
			static_cast<unsigned>(length), static_cast<unsigned>(count), mtu);
	return true;
}
