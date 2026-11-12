#include "NodeId.h"

#include <esp_mac.h>

String bridgeNodeId() {
	uint8_t mac[6] = {0};
	esp_read_mac(mac, ESP_MAC_BT);

	char id[64];
	snprintf(id, sizeof(id), "HMR-4e455952-4c4f-8000-8000-%02x%02x%02x%02x%02x%02x",
			 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
	return String(id);
}
