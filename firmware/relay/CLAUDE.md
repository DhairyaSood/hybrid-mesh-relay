# CLAUDE.md: Neyra ESP32 BLE-LoRa bridge

**Goal:** `phone A --BLE--> ESP32 A --LoRa 433 MHz--> ESP32 B --BLE--> phone B`. The bridge carries raw mesh packets
(20-255 bytes) unchanged; it only reads the packetId (bytes 4-19) for dedup. Hardware: ESP32 DevKit + AI-Thinker RA-02 (SX1278).
**Known-good LoRa-to-LoRa reference: the `divanshi` branch.** Its radio code, pins and settings are what this firmware uses.

**Envs:** `bridge` (BLE↔LoRa, advertises `NEYRA-LORA`, BLE-only if no radio) · `lora-ping` (radio-only test, `send <text>`; = divanshi's `main.cpp`)
· `radiodiag` (no-radio fallback: probes 8 pin sets via version reg 0x42, prints `RADIODIAG RESULT`, never transmits) · `native` (host tests).

**BLE (must match Android `BleBridgeConstants`):** service `7f6d2001-7b8e-4b5a-9d11-4c2a8e6f1001`, write (phone→bridge) `7f6d2002-…`,
notify (bridge→phone) `7f6d2003-…`. Every write/notify is `[chunkIndex][chunkCount][data]`, data per chunk = MTU−5, target MTU 247.

**Radio (`include/config.h`, both boards identical):** 433.0 MHz, SF9, BW 125 kHz, CR 4/7, sync 0x12, 10 dBm, preamble 8;
pins SCK 18, MISO 19, MOSI 23, NSS 5, RST 14, DIO0 26. RadioLib 7.x. Never change pins/settings on one board only; ask first.

**Bridge boot log to expect** (every line starts `LORADBG <BOOT|RADIO|BLE|LORA|DROP|STATUS>`):
```
LORADBG BOOT NEYRA-LORA bridge commit=<hash> built=<date time> node=HMR-...
LORADBG BOOT lora spi sck=18 miso=19 mosi=23 nss=5 rst=14 dio0=26
LORADBG RADIO attempt=1 version=0x12 code=0     <- raw 0x42 read after RST pulse, then RadioLib begin()
LORADBG RADIO OK version=0x12 attempts=1        <- or: RADIO FAILED ... (0x00/0xFF = no SPI answer: wiring/power, radiodiag)
LORADBG BLE advertising name=NEYRA-LORA ...
```
Radio failure never halts: BLE-only, retry every 30 s. Serial commands: `loracheck` (re-run bring-up), `status`,
`send <text>` (raw `bridge-xxxxxx:<seq>:<text>` over LoRa like lora-ping, no dedup), `notify <hex>` (bytes to the phone).
Packet path: `BLE write chunk` → `BLE packet from phone complete` → `LORA tx ok` ... `LORA rx bytes=` → `LORA queued for phone` → `BLE notify sent`.
Anything discarded: `LORADBG DROP reason=<CODE>` (meanings in `docs/HARDWARE_TEST.md` §6).

**Flash / test** (from `firmware/relay`): `./scripts/flash.sh <bridge|lora-ping|radiodiag> [port]` (Windows `scripts\flash.ps1`),
or `pio run -e <env> -t upload --upload-port <port>`. Read logs non-interactively: `<penv python> scripts/serial_log.py <port> --reset --seconds 15`.
`pio run` builds all; `pio test -e native` runs host tests. Never transmit without an antenna.

| Verified | Not verified |
|---|---|
| Phone ↔ ESP32 over BLE (hardware) | BLE → LoRa → BLE between two bridge boards |
| LoRa ↔ LoRa on hardware (divanshi branch = `lora-ping`) | Android app over the bridge on real phones |
| Builds; host tests incl. full chain with a mock radio | `radiodiag`, boot bring-up/retry, `loracheck`/`send` on hardware |

Rules: explain each git command in one line; never force-push or `reset --hard`; work on `feature/integrated-lora-bridge`, not `main`.
