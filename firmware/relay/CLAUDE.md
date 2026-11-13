# CLAUDE.md: Neyra ESP32 BLE-LoRa bridge firmware

Context for AI coding assistants (Claude Code, Copilot, Cursor, ...) helping a teammate test or fix this firmware.
The people you're helping are often new to embedded work and git: explain what you do and why, one step at a time.

## What this is

**Neyra** (repo `hybrid-mesh-relay`) is an offline Android messaging app. Phones form a mesh over BLE and Wi-Fi Direct.
This firmware turns an **ESP32 + AI-Thinker RA-02 (SX1278, 433 MHz)** into a **NEYRA-LORA bridge** so mesh packets can
cross long distances over LoRa:

```
phone A --BLE--> ESP32 bridge A --LoRa 433 MHz--> ESP32 bridge B --BLE--> phone B
```

The bridge does not parse or change mesh packets. It only reads the packetId (bytes 4-19) for dedup and logs.
Packet format: `protocol/packet-format.md` at the repo root.

## PlatformIO environments (`platformio.ini`)

| env | Source | Purpose |
|---|---|---|
| `bridge` | `src/apps/bridge_main.cpp` + all modules | The real BLE ↔ LoRa bridge, advertises as **NEYRA-LORA**. Runs BLE-only if the radio isn't detected. |
| `lora-ping` | `src/apps/lora_ping.cpp` + `src/lora/` | Radio-only test: serial `send <text>` on one board, the other prints `RX from ...`. |
| `radiodiag` | `src/apps/radio_diag.cpp` only | Wiring diagnostic: reads SX1278 version reg 0x42 (expect 0x12) on 7 candidate pin sets, checks RST and DIO0, prints `RADIODIAG RESULT ...`. Never transmits. |
| `native` | `src/routing/DedupCache.cpp` + `test/` | Host unit tests, no board: `pio test -e native`. |

## Layout

- `include/config.h`: **all** settings: pins, LoRa parameters, BLE UUIDs, limits.
- `src/ble/BleBridge.*`: NimBLE GATT server, chunk reassembly (phone → bridge), chunked notify (bridge → phone).
- `src/bridge/BridgeRouter.*`: FreeRTOS tasks + queues between BLE and LoRa, dedup, counters.
- `src/lora/LoRaManager.*`: RadioLib SX1278 wrapper (non-blocking receive via DIO0).
- `src/routing/DedupCache.*`: packetId dedup (20 s window, 32 entries); `formatPacketId` prints `HMR-xxxxxxxx-...` like Android.
- `src/diagnostics/Logger.*`: the `LORADBG` macro.
- `scripts/flash.sh|ps1`, `scripts/monitor.sh|ps1`: one-command flash + monitor with port auto-detection.
- `scripts/serial_log.py`: **for agents**: non-interactive serial capture (`--seconds`, `--until REGEX`, `--send LINE`, `--reset`). Use it instead of the interactive monitor when you need to read the log yourself.
- `docs/HARDWARE_TEST.md`: the step-by-step hardware runbook (wiring, Phase A/B, log table, troubleshooting, results).

## BLE interface (must match Android `BleBridgeConstants`)

| | UUID | Direction |
|---|---|---|
| Service | `7f6d2001-7b8e-4b5a-9d11-4c2a8e6f1001` | advertised, device name `NEYRA-LORA` |
| Write | `7f6d2002-7b8e-4b5a-9d11-4c2a8e6f1001` | phone → bridge (WRITE and WRITE_NR) |
| Notify | `7f6d2003-7b8e-4b5a-9d11-4c2a8e6f1001` | bridge → phone |

Deliberately separate from the phone mesh UUIDs (discovery `7f6d0001`, mesh service `7f6d1001`) so phones never treat the bridge as a mesh peer.

**Chunk framing** (both directions): every BLE write/notification is `[chunkIndex: 1 byte][chunkCount: 1 byte][data]`.
A packet is split into `chunkCount` chunks sent in order 0..count-1; data per chunk = `MTU - 3 - 2`. Target MTU 247.
Reassembly drops the packet on out-of-order chunks, a missing first chunk, or >2 s between chunks.
Whole packet max **255 bytes** (SX127x LoRa payload limit), min 20 bytes (must contain the packetId); larger is dropped, never truncated.

## LoRa settings (`include/config.h`; BOTH boards must match)

433.0 MHz, SF9, BW 125 kHz, CR 4/7, sync word 0x12, 10 dBm, preamble 8.
Note: 433 MHz is not licence-free in India (865-867 MHz is); the RA-02 can't tune there. Bench testing only.

## Logs

Every bridge line starts with `LORADBG <STAGE>`: `BOOT`, `BLE`, `LORA`, `DROP`. Anything discarded is logged as
`LORADBG DROP reason=<CODE>`. To debug, ask the user for **both boards'** monitor output and find the first hop with
no line; the per-hop table and every drop reason are in `docs/HARDWARE_TEST.md` section 6.
The Android app logs with the prefix `MESHDBG`.

## How to flash, monitor, test

```
cd firmware/relay
./scripts/flash.sh <bridge|lora-ping|radiodiag> [port]     # Mac/Linux
.\scripts\flash.ps1 <bridge|lora-ping|radiodiag> [port]    # Windows
./scripts/monitor.sh [port]                                # monitor only
pio run                                                    # build all images
pio test -e native                                         # host unit tests
```
Agents reading logs themselves (no interactive monitor; flash with `pio run -e <env> -t upload --upload-port <port>`):
```
~/.platformio/penv/bin/python scripts/serial_log.py <port> --reset --seconds 15           # boot log
~/.platformio/penv/bin/python scripts/serial_log.py <port> --send "send hello" --seconds 5
~/.platformio/penv/bin/python scripts/serial_log.py <port> --seconds 30 --until "RX from"
```
(Windows: `%USERPROFILE%\.platformio\penv\Scripts\python.exe`.) If `--reset` shows no boot log, ask the human to unplug and replug USB.
Serial input: firmware reads lines with a 50 ms timeout, so tell users to **paste** commands, not type them slowly (`serial_log.py --send` is always safe).
Hardware test procedure: `docs/HARDWARE_TEST.md` (Phase A lora-ping, Phase B nRF Connect).

## Verified vs not verified

| Path | Status |
|---|---|
| Phone ↔ ESP32 over BLE (connect, MTU, subscribe, write, `notify <hex>`) | **Verified on hardware** |
| Firmware builds for all envs; dedup unit tests | Verified (CI/host) |
| RA-02 radio detection + LoRa ↔ LoRa (`lora-ping`) | **Verified on hardware** (with the `divanshi` branch's `main.cpp`, which is byte-identical to `src/apps/lora_ping.cpp`, same pins) |
| BLE → LoRa → BLE between two bridge boards (Phase B) | **Not yet verified** |
| Android app using the bridge as a mesh transport | **Not yet verified on phones** |
| `radiodiag` | Builds; not yet run on hardware |

## Rules for AI assistants

1. **Never `git push --force`** (or `-f`, `--force-with-lease`) and **never `git reset --hard`**. If push is rejected: `git pull`, resolve, push.
2. **Never edit pin numbers in `include/config.h` without asking the user first.** Run `radiodiag` and show the user the `RESULT` line. Changing pins affects everyone's boards.
3. Don't change LoRa parameters on one board only; both must match. Ask before changing any of them.
4. **Explain every git command in one line before running it.** Don't commit to `main`; work on the feature branch.
5. Don't change the BLE UUIDs or chunk framing without changing Android's `BleBridgeConstants` and `protocol/packet-format.md` in the same change.
6. Before saying something works, show the log lines that prove it. Say clearly when something is untested on hardware.
7. Never transmit (lora-ping `send`, bridge with LoRa) without an antenna attached; remind the user.
