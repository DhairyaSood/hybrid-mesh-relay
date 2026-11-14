# Neyra / Hybrid Mesh Relay

Neyra is an Android-first resilient mesh messaging prototype built around a long-lived background service and a transport-agnostic mesh runtime. The actual implementation in this repository is centered on the Android app under `android/`: it discovers peers over Bluetooth LE and Wi‑Fi Direct, maintains a queued message store, and routes packets through a mesh engine that can retry, store-and-forward, and observe runtime diagnostics.

This repo is still under active development. The code makes it clear that the current shipping logic is the Android app, while the firmware and hardware directories are still scaffolding rather than a finished relay implementation.

## Current implementation status

What is active in the code right now:

- Android app with Compose UI and a foreground/background mesh service
- Bluetooth LE runtime for advertising, scanning, and GATT service setup
- Wi‑Fi Direct as an optional secondary mesh transport
- Persistent local message state using Room + repository models
- Mesh packet forwarding, duplicate suppression, and peer tracking
- Diagnostics and status screens for mesh, network, and device state

What is not implemented yet:

- A real LoRa transport for the relay layer
- Fully working ESP32 firmware relay runtime
- Production-grade long-range relay-to-relay communication

The code explicitly reserves LoRa as a future transport: `MeshTransportKind.LORA` exists in `android/app/src/main/java/com/hybridmesh/relay/messaging/mesh/MeshTransport.kt` with the comment that it is a reserved stable code for the future LoRa transport and is not implemented yet.

## Repository structure

- `android/` — the actual Android application and Kotlin implementation
- `firmware/relay/` — ESP32/embedded relay skeleton; files are currently empty placeholders
- `hardware/` — hardware notes, BOM, pinout, wiring, and enclosure references
- `protocol/` — protocol and packet-format documentation
- `docs/` — design and engineering notes, but treat these as secondary to the current code
- `website/` — landing page / visual site for the project
- `testing/` — planning and validation notes
- `.github/workflows/` — Android CI/CD workflow files

## Code map

The core runtime is in the Android app:

- `android/app/src/main/java/com/hybridmesh/relay/MainActivity.kt`  
  App shell, permission flow, and the Compose navigation state.

- `android/app/src/main/java/com/hybridmesh/relay/service/HybridMeshService.kt`  
  Starts the long-lived foreground mesh runtime and keeps the service alive in the background.

- `android/app/src/main/java/com/hybridmesh/relay/network/NetworkManager.kt`  
  Owns BLE runtime lifecycle, scan/advertise state, permission checks, and connectivity updates.

- `android/app/src/main/java/com/hybridmesh/relay/messaging/MessagingManager.kt`  
  Coordinates message lifecycle, identity sync, and the durable store-and-forward system.

- `android/app/src/main/java/com/hybridmesh/relay/messaging/mesh/MeshEngine.kt`  
  Main mesh delivery loop: queue processing, forwarding, transport fan-out, attachment handling, and maintenance.

- `android/app/src/main/java/com/hybridmesh/relay/messaging/mesh/MeshPeerDirectory.kt`  
  Maintains the transport-independent peer map across BLE and Wi‑Fi Direct observations.

- `android/app/src/main/java/com/hybridmesh/relay/messaging/mesh/MeshTransport.kt`  
  Stable transport enum and transport abstractions. BLE and Wi‑Fi Direct are implemented; LoRa is reserved only.

- `android/app/src/main/java/com/hybridmesh/relay/data/` and `.../messaging/data/`  
  Room entities, DAOs, repositories, and message queue tracking.

- `android/app/src/main/java/com/hybridmesh/relay/ui/`  
  Compose screens for Home, Messages, Network, Devices, Diagnostics, and profile views.

- `android/app/src/main/java/com/hybridmesh/relay/ble/` and `.../wifi/`  
  BLE and Wi‑Fi Direct transport implementations.

## Architecture at a glance

The project follows a layered runtime model:

1. `HybridMeshService` starts a background service and prepares the runtime.
2. `NetworkManager` handles radio state, permissions, and discovery conditions.
3. `MessagingManager` resets in-flight work and keeps the mesh runtime alive.
4. `MeshEngine` takes queued outgoing messages and fans them out over whichever transport is available.
5. `MeshPeerDirectory` merges Bluetooth and Wi‑Fi observations under a stable node ID.
6. `Room` and repository classes persist queued messages, forward state, and attachment data.
7. The Compose UI reads runtime state from the same services so the interface reflects live mesh health.

## How the app behaves today

The active design is really a hybrid offline communication system that balances a consumer-friendly Android app with a low-level mesh runtime:

- The app name is `Neyra` (`android/app/src/main/res/values/strings.xml`).
- The UI presents a mesh status card, peer listing, topology summary, and diagnostics without requiring a dedicated terminal-like interface.
- Nearby discovery is driven by Bluetooth and Wi‑Fi Direct, with the runtime able to recover from permission or radio state changes.
- Messages are not merely local chat entries—they are queued and forwarded through the mesh layer in the background.
- Attachment transfer and fan-out are part of the architecture, even though the exact production hardening is still evolving.

## Build and run

### Android app

Requirements:

- Android Studio with the Android SDK installed
- JDK 17 or newer for the Android toolchain
- A device or emulator running Android 8.0+ (`minSdk = 26`)

From the repo root:

```bash
cd android
./gradlew assembleDebug
```

On Windows:

```powershell
cd android
.\gradlew.bat assembleDebug
```

Then install the APK on an emulator or physical Android device.

### Permissions the app expects

The app requests and checks Bluetooth, Wi‑Fi Direct, location, and notification-related permissions through `PermissionManager` and the runtime startup flow. If a device is missing permission, the app surfaces recovery steps instead of silently failing.

## LoRa roadmap

LoRa is explicitly planned as a future transport and should be treated as a roadmap item, not a current feature.

The code clearly shows the intended future architecture:

- `MeshTransportKind.LORA` is already reserved as enum value `3`
- the `firmware/relay/src/lora/` directory exists as a placement for the long-range relay implementation
- the `website/` assets describe the project as a hybrid system where LoRa provides a longer-range relay-to-relay link

Recommended future direction:

- Add a dedicated LoRa transport implementation under the Android mesh abstraction
- Build a relay firmware layer on ESP32 + SX1278 / RA-02 style hardware
- Use LoRa only for long-range relay-to-relay communication, while BLE/Wi‑Fi Direct remain the primary short-range local transports
- Add packet fragmentation, duty-cycle, and channel management once the relay topology is stable

## Summary

This project is best understood as an Android-based resilient mesh communication platform with a current implementation centered on BLE + Wi‑Fi Direct, and a clearly stated future extension toward LoRa-based relay infrastructure. The app is already structured around device identity, peer discovery, queue-based message forwarding, and runtime diagnostics—so it is much more than a static concept or mockup.
