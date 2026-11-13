# Hardware test: two ESP32 + RA-02 boards

This runbook takes you from loose parts to a working **phone → BLE → ESP32 → LoRa → ESP32 → BLE → phone** link.
You need about an hour. No firmware knowledge needed: every step says exactly what to type and what you should see.

**Order:** wire both boards → flash → **Phase A** (radio only) → **Phase B** (full bridge) → fill in the results table.
Do not skip Phase A: if the radios can't talk to each other, Phase B can't work either.

## What you need

| Item | Qty | Notes |
|---|---|---|
| ESP32 DevKit (ESP32-WROOM-32, 30 or 38 pin) | 2 | |
| AI-Thinker RA-02 (SX1278, 433 MHz) | 2 | |
| 433 MHz antenna | 2 | **Must be screwed on before power-up** |
| USB **data** cable | 2 | Charge-only cables show no port |
| Jumper wires | 16 | Short is better |
| Android or iPhone with **nRF Connect** (Nordic, free) | 1-2 | Phase B only |
| Multimeter with a beep (continuity) mode | 1 | Optional but saves hours |

---

## 1. Wiring (do this for BOTH boards)

| RA-02 pin | ESP32 pin | What it is |
|---|---|---|
| 3.3V | **3V3** | Power. **3.3 V only. Never 5V / VIN**: 5 V destroys the RA-02. |
| GND | GND | Ground (any GND pin) |
| SCK | GPIO **18** | SPI clock |
| MISO | GPIO **19** | SPI data, radio → ESP32 |
| MOSI | GPIO **23** | SPI data, ESP32 → radio |
| NSS | GPIO **5** | SPI chip select |
| RST | GPIO **14** | Radio reset |
| DIO0 | GPIO **26** | "Packet done" interrupt line |

These are the values in [`include/config.h`](../include/config.h). DIO1-DIO5 stay unconnected.

**Before you plug in USB:**

1. **Antenna on.** Transmitting without an antenna can permanently damage the RA-02.
2. **Continuity test** (multimeter in beep mode, board **unpowered**): put one probe on the ESP32 pin and the other on the RA-02 pad, for all 8 wires. Every pair must beep. Then probe **3V3 against GND**: it must **not** beep. If it does, you have a short; find it before powering up.
3. Pin labels on cheap DevKits are sometimes printed on the back, or shifted by one. Count pins from a known one (GND, 3V3) if unsure.

---

## 2. Setup (once per computer)

1. Install **VS Code** and its **PlatformIO IDE** extension. Wait until the PlatformIO install finishes (bottom status bar).
2. Install the USB driver for your board's chip if the board shows no port:
   **CP210x** (Silicon Labs, square chip near USB) or **CH340/CH9102** (WCH).
3. Get the code:
   ```
   git fetch origin
   git checkout feature/esp32-ble-lora-bridge
   git pull
   cd firmware/relay
   ```

## 3. Flashing: one command

From `firmware/relay`:

| You are on | Command |
|---|---|
| Mac / Linux | `./scripts/flash.sh <env> [port]` |
| Windows (PowerShell) | `.\scripts\flash.ps1 <env> [port]` |

`<env>` is one of:

| env | What it does |
|---|---|
| `lora-ping` | Radio-only test (Phase A). Type `send hello` on one board, the other prints it. |
| `bridge` | The real NEYRA-LORA BLE ↔ LoRa bridge (Phase B). |
| `radiodiag` | Finds out whether the radio is wired right (see section 7). Never transmits. |

The script builds, flashes, then opens the serial monitor at 115200. **Ctrl+C** quits the monitor.
To open the monitor only, without flashing: `./scripts/monitor.sh [port]` or `.\scripts\monitor.ps1 [port]`.

- **One board plugged in:** leave out the port; the script finds it.
- **Two boards plugged in:** the script lists both ports and stops. Run it again with the port, e.g.
  `./scripts/flash.sh bridge /dev/cu.usbserial-0001` or `.\scripts\flash.ps1 bridge COM6`.
  Tip: plug in one board, note its port, then plug in the other.
- **"Failed to connect to ESP32" / "Timed out waiting for packet header":** run again and **hold the BOOT button** until `Writing at 0x...` appears, then release.
- **Windows "running scripts is disabled":** `powershell -ExecutionPolicy Bypass -File .\scripts\flash.ps1 bridge`
- **No output in the monitor after flashing:** press **EN** (reset). If your board has **no reset button, unplug and replug USB**, then run `monitor.sh` / `monitor.ps1` again.
- Some garbage characters right after a reset are normal (the ESP32 boot ROM talks at a different speed).

---

## 4. Phase A: radio test with `lora-ping`

Goal: prove the two radios hear each other, with no phones involved.

1. Flash `lora-ping` to **both** boards (one at a time, or with explicit ports).
   Optional: give board B its own name so the log is easier to read:
   Mac/Linux `PLATFORMIO_BUILD_FLAGS='-DLORA_NODE_ID=\"node-b\"' ./scripts/flash.sh lora-ping`
2. Open a monitor on each board (two terminals). Each should print:
   ```
   RA-02 LoRa link test, node node-a, 433.0 MHz
   Commands: send <text>, help
   LoRa ready
   ```
   If you see `LoRa init failed, RadioLib code -2` instead, the radio isn't detected: go to **section 7** (radiodiag).
3. Put the boards **1-3 m apart**. On board A, send `send hello`.
   > **Paste the command, don't type it letter by letter.** The firmware reads a line with a 50 ms gap timeout,
   > so slow typing arrives as fragments (`Unknown command`). Type `send hello` in any text editor, copy it,
   > paste it into the monitor, then press Enter. One extra `Unknown command` line after Enter is harmless.
4. Expected:
   - Board A: `TX ok: node-a:1:hello`
   - Board B: `RX from node-a:1:hello | RSSI -45.0 dBm | SNR 9.5 dB`
5. Now the other direction: `send hello` on **board B**; board A must print `RX from ...`.
6. Write both RSSI/SNR values in the results table (section 9).

**Phase A passes** when both directions print `RX from`. Typical indoor RSSI at 1-3 m: -30 to -70 dBm; SNR above 0 dB.

---

## 5. Phase B: bridge test with nRF Connect

Goal: a packet written by a phone into board A comes out of board B as a BLE notification.

1. Flash `bridge` to **both** boards and keep both monitors open. Each must print, among others:
   ```
   LORADBG BOOT NEYRA-LORA bridge node=...
   LORADBG BOOT router started loraAvailable=1 ...
   LORADBG BLE advertising name=NEYRA-LORA ... addr=xx:xx:xx:xx:xx:xx
   ```
   `loraAvailable=0` means the radio wasn't detected (see section 7). The bridge still runs BLE-only, but nothing reaches LoRa.
   **Write down each board's `addr=`**: both boards are called NEYRA-LORA, and the address tells them apart.
2. On the phone, open **nRF Connect → Scanner**. You should see two devices named **NEYRA-LORA**.
   Connect to **both** (nRF Connect keeps one tab per connection). The board's monitor prints `LORADBG BLE connected ...`.
   (Android shows the address; on iPhone, connect to one, check which monitor says `connected`, then the other.)
3. **Board B tab** (receiver): open the service `7f6d2001-...`. On characteristic **`7f6d2003-...`**, tap the **subscribe** icon (triple arrow).
   Board B prints `LORADBG BLE notify subscribed`.
4. **Board A tab** (sender): on characteristic **`7f6d2002-...`**, tap the **write** icon (up arrow), choose **BYTE ARRAY**, and enter:
   ```
   0001544553542D303030312D4E455952412D4C4F52412D4F4B21
   ```
   That is a 2-byte chunk header `00 01` ("chunk 0 of 1") followed by the 24-byte test packet `TEST-0001-NEYRA-LORA-OK!`.
   Write type: the default (**Request**). The MTU can stay at the default.
   If the write fails or board A prints nothing, use the menu → **Request MTU** → 247, then write again.
5. Check the logs against the table in section 6, then check the **board B tab**: `7f6d2003` shows the received value.
   - MTU ≥ 29 (one notification): `00-01-54-45-53-54-2D-...-4F-4B-21`
   - Default MTU 23 (two notifications, the last one stays on screen): first `00-02-54-45-53-54-2D-...-4C-4F` (chunk 0 of 2: `TEST-0001-NEYRA-LO`), then `01-02-52-41-2D-4F-4B-21` (chunk 1 of 2: `RA-OK!`).
6. **Sending the same packet again within 20 seconds is dropped on purpose** (`reason=DUPLICATE`). Wait 20 s, or change one of bytes 4-19 (e.g. `30303031` → `30303032`).
7. Repeat in the other direction: subscribe on board A, write on board B.

**Phase B passes** when both directions end with `LORADBG BLE notify sent` on the receiving board and the value appears in nRF Connect.

---

## 6. Reading the LORADBG log

Every bridge log line starts with `LORADBG <STAGE>`: `BOOT`, `BLE`, `LORA` or `DROP`.
Anything thrown away is **always** logged as `LORADBG DROP reason=<CODE>`.

### Expected lines per hop (Phase B, test packet)

| Hop | Board | Line you should see |
|---|---|---|
| 1. Phone → board A over BLE | A | `LORADBG BLE write chunk=1/1 bytes=24 total=24` |
| | A | `LORADBG BLE packet from phone complete bytes=24` |
| 2. Board A → air over LoRa | A | `LORADBG LORA tx start bytes=24 packetId=HMR-2d303030-312d-4e45-5952-412d4c4f5241 airtimeMs~...` |
| | A | `LORADBG LORA tx ok bytes=24 packetId=HMR-2d303030-... tookMs=...` |
| 3. Air → board B over LoRa | B | `LORADBG LORA rx bytes=24 rssi=-45.0 snr=9.5` |
| | B | `LORADBG LORA rx data bytes=24 hex=544553542D303030312D4E455952412D4C4F52412D4F4B21` |
| | B | `LORADBG LORA queued for phone packetId=HMR-2d303030-...` |
| 4. Board B → phone over BLE | B | `LORADBG BLE notify sent bytes=24 chunks=1 mtu=247` (or `chunks=2 mtu=23`) |

The **first hop with no line** is where it broke. Look for a `DROP` line right there.

### Drop reasons

| `reason=` | Meaning | What to do |
|---|---|---|
| `LORA_UNAVAILABLE` | The radio was not detected at boot; the bridge runs BLE-only. | Section 7 (radiodiag). |
| `DUPLICATE` | Same packetId (bytes 4-19) seen in the last 20 s. Deliberate. | Wait 20 s or change bytes 4-19. |
| `NO_PHONE_CONNECTED` | Board B got the packet over LoRa but no phone is connected to it. | Connect nRF Connect to board B. |
| `PHONE_NOT_SUBSCRIBED` | Phone connected to board B but didn't subscribe to 7f6d2003. | Tap the subscribe icon on 7f6d2003. |
| `TOO_SHORT_FOR_PACKET_ID` | Packet shorter than 20 bytes, so it has no packetId. | Write the full test packet; don't forget it's 24 bytes after the `0001` header. |
| `PACKET_TOO_LARGE` | Packet over 255 bytes (LoRa limit). Dropped whole, never cut. | Send a smaller packet. |
| `CHUNK_TOO_SHORT` | A write under 3 bytes (header without data). | Include the `0001` header **and** data. |
| `BAD_CHUNK_HEADER` | Index ≥ count, or count 0. | First two bytes must be `00 01` for a single chunk. |
| `MISSING_FIRST_CHUNK`, `OUT_OF_ORDER`, `TRANSFER_RESTARTED`, `REASSEMBLY_TIMEOUT` | Multi-chunk write broken: chunks missing, out of order, or more than 2 s apart. | Send chunks 0..n-1 in order without pauses. |
| `DISCONNECTED_MID_TRANSFER` | Phone disconnected while sending chunks. | Reconnect and resend. |
| `QUEUE_FULL` | Packets arriving faster than LoRa can send (each takes ~100-300 ms of airtime). | Send slower. |
| `LORA_TX_FAILED code=N` | Radio refused to transmit. | Send the line to the team. |
| `LORA_CRC_MISMATCH`, `LORA_RX_FAILED` | A LoRa packet arrived corrupted. | Move the boards apart (≥ 1 m) or closer if far; check antennas. |
| `NOTIFY_FAILED` | BLE notification to the phone failed 5 times. | Reconnect the phone. |

Serial commands on the bridge (paste them, see Phase A): `status` prints counters (`loraTx`, `loraRx`, `notified`, `dropped`), `notify <hex>` pushes bytes to the connected phone without any radio (good for testing the phone side alone).

---

## 7. Radio not detected? Run `radiodiag`

Symptoms: `LoRa init failed, RadioLib code -2` (lora-ping), `LoRa unavailable code=-2, running BLE-only` or `loraAvailable=0` (bridge).
Code -2 means **the ESP32 can't talk to the radio chip over SPI**: almost always wiring or power.

1. Flash it: `./scripts/flash.sh radiodiag` (Windows: `.\scripts\flash.ps1 radiodiag`). It **never transmits**.
2. Every 5 s it tries 7 common pin sets and prints one line per set:
   ```
   RADIODIAG sck=18 miso=19 mosi=23 nss=5 rst=14 dio0=26 version=0x12 FOUND rst=ok dio0=ok  (config.h default)
   RADIODIAG sck=18 miso=19 mosi=23 nss=5 rst=14 dio0=2 version=0x12 FOUND rst=ok dio0=FAIL  (dio0 on 2)
   ...
   RADIODIAG RESULT pins=sck=18 miso=19 mosi=23 nss=5 rst=14 dio0=26
   ```
   `version=0x12` means the SX1278 answered. `0x00` or `0xFF` means nobody answered on that pin set.
   For a found chip it also checks RST (holding it low must silence the chip) and DIO0 (a listen-only CAD must raise it).
3. Read the **RESULT** line:
   - **`RESULT pins=` equal to the config.h default** (18,19,23,5,14,26) and no "re-check" note: the wiring is right. If lora-ping still fails, swap the radio module or the ESP32.
   - **`RESULT pins=` a different set:** the board is wired differently from config.h. **Send the RESULT line to the team** before changing anything. Either rewire to the table in section 1, or (with the team's OK) change only the pin constants in `include/config.h` and re-run Phase A.
   - **`... (chip answers, but rst or dio0 did not check out ...)`:** SPI works but the RST or DIO0 wire is wrong or loose.
   - **`RESULT no chip answered: check 3.3V, GND and wiring`:** check 3.3 V at the RA-02 with a multimeter (should read 3.2-3.4 V), GND, then redo the continuity test. Also check the RA-02's solder joints if it came on a breakout.

---

## 8. Troubleshooting

| Problem | Likely cause | Fix |
|---|---|---|
| No **NEYRA-LORA** in nRF Connect | Board not running `bridge`, still connected to another phone, or phone Bluetooth/location off | Check the monitor shows `LORADBG BLE advertising`. Disconnect other phones (a connected board stops advertising). Turn on Bluetooth **and** Location (Android needs it for scanning). Pull down to rescan. |
| Garbled serial text (`⸮⸮⸮`) | Wrong baud rate, or the boot ROM's own output | The scripts use 115200. A little garbage right after reset is normal. Constant garbage: wrong baud in another tool. |
| No serial port / "no ESP32 board found" | Charge-only cable, missing driver, port open elsewhere | Try another cable; install CP210x or CH340 driver; close Arduino IDE / other monitors. Linux: `sudo usermod -aG dialout $USER`, log out/in. |
| `LoRa init failed, RadioLib code -2` / `loraAvailable=0` | Radio not detected (SPI) | Section 7 (radiodiag). |
| Other negative `RadioLib code` | Radio detected but refused a setting | Send the exact line to the team. |
| Board resets by itself, `Brownout detector was triggered` | Not enough current over USB | Use a different USB port (not a hub), a shorter cable, or a powered hub. A 100 µF capacitor across the RA-02's 3.3 V and GND helps. |
| `Failed to connect to ESP32` when flashing | Board not in download mode | Hold **BOOT** while flashing until `Writing at 0x...`. |
| `send hello` prints `Unknown command` | Typed slowly, arrived in pieces | Paste the whole line (see Phase A step 3). |
| TX ok but the other board prints nothing | Different firmware/settings, antenna missing, or boards too close/too far | Flash both from the same commit; both must say `433.0 MHz`; antennas on; 1-3 m apart. |
| Phase B: hop 1 lines missing on board A | Wrote to the wrong board/characteristic, or no BYTE ARRAY | Write to `7f6d2002` on the board whose monitor said `connected`; choose BYTE ARRAY. |
| `git push` rejected (`non-fast-forward` / `fetch first`) | Someone pushed before you | `git pull`, fix any conflict, then `git push`. **Never** `git push --force`. |
| `git pull` says "local changes would be overwritten" | You have uncommitted edits | `git stash`, `git pull`, `git stash pop`. Or commit first. |
| Merge conflict in this file's results table | Two people added rows at once | Keep **both** rows, delete the `<<<<<<<`, `=======`, `>>>>>>>` lines, `git add` the file, `git commit`. |
| `Authentication failed` on push | Not logged in to GitHub | `gh auth login`, or use a personal access token as the password. |

---

## 9. Results (fill in and commit)

Add **one row per test** at the bottom of the table. Get the firmware commit with `git rev-parse --short HEAD`.
Then: `git pull`, `git add firmware/relay/docs/HARDWARE_TEST.md`, `git commit -m "Add hardware test results (<your name>)"`, `git push`.

| Date | Tester | Commit | Board pair (A / B) | Test | Direction | Result (PASS/FAIL) | RSSI / SNR | Distance | Notes (failing hop, DROP reason, radiodiag RESULT) |
|---|---|---|---|---|---|---|---|---|---|
| 2026-11-13 | example | 6cecd18 | A=desk-1 / B=desk-2 | Phase A lora-ping | A → B | PASS | -48 / 9.8 | 2 m | example row, delete or keep |
| | | | | Phase A lora-ping | B → A | | | | |
| | | | | Phase B bridge | A → B | | | | |
| | | | | Phase B bridge | B → A | | | | |
