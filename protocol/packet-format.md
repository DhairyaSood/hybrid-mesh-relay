# Packet format

## BLE bridge framing (phone <-> NEYRA-LORA ESP32)

The ESP32 LoRa bridge is a BLE GATT peripheral. It is separate from the
phone-to-phone mesh: it does **not** advertise the discovery UUID `7f6d0001-…`
or expose the mesh service `7f6d1001-…`, so phones running the BLE mesh never
treat it as a mesh peer.

| Item | Value |
|---|---|
| Advertised name (scan response) | `NEYRA-LORA` |
| Advertised service UUID | `7f6d2001-7b8e-4b5a-9d11-4c2a8e6f1001` |
| Write characteristic (phone -> bridge) | `7f6d2002-7b8e-4b5a-9d11-4c2a8e6f1001`, Write and Write Without Response |
| Notify characteristic (bridge -> phone) | `7f6d2003-7b8e-4b5a-9d11-4c2a8e6f1001`, Notify (subscribe via CCCD `0x2902`) |
| MTU | the bridge requests 247; chunks use whatever MTU was negotiated |
| Max packet | 255 bytes (the SX127x LoRa payload limit) |

Firmware source of truth: `firmware/relay/include/config.h`.

### Chunk layout

Every write to `7f6d2002` and every notification from `7f6d2003` is one chunk:

```
offset  size  field
0       1     chunkIndex   0-based, 0 .. chunkCount-1
1       1     chunkCount   total chunks in this packet, >= 1
2       N     data         next N bytes of the packet, N >= 1
```

- A packet is the concatenation of `data` from chunks `0 .. chunkCount-1`, sent in order.
- Each chunk is at most `MTU - 3` bytes (the ATT header takes 3), so it carries up to
  `MTU - 5` packet bytes: 242 at MTU 247, 18 at the default MTU 23.
- The packet bytes are opaque. The bridge does not decode or re-encode them.

Receiver rules (as implemented on the bridge; each drop logs `LORADBG DROP reason=<CODE>`):

| Condition | Result |
|---|---|
| chunk shorter than 3 bytes | `CHUNK_TOO_SHORT`, chunk ignored |
| `chunkCount == 0` or `chunkIndex >= chunkCount` | `BAD_CHUNK_HEADER`, transfer reset |
| `chunkIndex == 0` while a transfer is in progress | `TRANSFER_RESTARTED`, old transfer dropped, new one starts |
| first chunk missing | `MISSING_FIRST_CHUNK` |
| wrong index or changed count | `OUT_OF_ORDER`, transfer reset |
| more than 2000 ms between chunks | `REASSEMBLY_TIMEOUT`, transfer reset |
| reassembled packet over 255 bytes | `PACKET_TOO_LARGE`, whole packet dropped (never truncated) |
| disconnect mid-transfer | `DISCONNECTED_MID_TRANSFER` |

Example: the 5-byte packet `48 45 4C 4C 4F` ("HELLO") in one chunk is written as
`00 01 48 45 4C 4C 4F`. (Framing example only: at 5 bytes it is below the
20-byte forwarding minimum below.)

### LoRa frame and forwarding rules

One LoRa packet carries exactly one whole mesh packet, byte for byte, with no
chunk header and no extra framing. The bridge forwards in both directions:

- phone write (reassembled) -> LoRa transmit
- LoRa receive -> notify to the subscribed phone (re-chunked by the phone's MTU)

| Rule | Value | Drop reason |
|---|---|---|
| Minimum packet | 20 bytes, so packetId (bytes 4-19) exists | `TOO_SHORT_FOR_PACKET_ID` |
| Maximum packet | 255 bytes | `PACKET_TOO_LARGE` |
| Dedup | a packetId seen in either direction is not forwarded again for 20 s | `DUPLICATE` |
| LoRa CRC | bad CRC is dropped | `LORA_CRC_MISMATCH` |
| No phone connected / not subscribed | LoRa packet is dropped, not stored | `NO_PHONE_CONNECTED` / `PHONE_NOT_SUBSCRIBED` |

The dedup window is shorter than the phone's 30 s delivery-ACK timeout, so a
phone's retry of the same packet is transmitted again. The bridge reads only
bytes 4-19 of the packet and never modifies it.
