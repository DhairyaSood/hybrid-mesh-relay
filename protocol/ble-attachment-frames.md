# BLE attachment frames

Attachment application frames travel inside the reliable GATT data/ACK transport. The frame header is `NYA1`, a one-byte attachment protocol version, a one-byte frame type, and an eight-byte transfer ID. The transfer ID stays constant across BEGIN, CHUNK, and FINISH for one BLE attachment attempt.

## Version 1

Version 1 carries the attachment manifest in BEGIN and is retained for older peers. A receiver resolves the incoming BLE address through its discovered peer table. This works when the receiver has observed the sender, but cannot recover when discovery missed that device.

## Version 2

Discovery version 4 uses capability bit `0x04` to advertise support for hop-aware attachment frames. A sender uses frame version 2 only when that bit is present; otherwise it sends version 1. Older scanners ignore the additional capability bit, and older senders continue to send version 1.

Version 2 adds `immediateHopSenderNodeId` to BEGIN. The existing manifest `senderNodeId` remains the original attachment creator. At every relay, the relay's own node ID is sent as the immediate hop identity, while the original sender and recipient remain unchanged and `hopCount` advances as before. CHUNK and FINISH use version 2 and the same transfer ID.

The receiver validates the claimed node ID format, checks it against any identity already associated with the BLE address, and checks that a direct hop's identity matches the manifest sender. It then binds the identity and protocol version to the address, message ID, and transfer ID after BEGIN is accepted. Later frames must match that binding. This lets transfers proceed when discovery missed the peer without allowing identity to change midway through a transfer.

The node ID is a routing identity, not cryptographic proof. This protocol change does not add key-based peer authentication.
