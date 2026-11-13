# TEAM_HANDOFF: LoRa bridge hardware test (for the AI agent)

This file is written **for the AI coding agent** (Claude Code, Copilot agent mode, ...) running on a teammate's laptop.
Humans: open your agent in this repo and say **"Read TEAM_HANDOFF.md and do it. I'm Person A (Node 1)."**
(or **"I'm Person B (Node 2)."**). The agent does the rest.

---

## a. Agent: read this first

- The human tells you **"I'm Person A (Node 1)"** or **"I'm Person B (Node 2)"**. Remember `N` (1 or 2). Person A = Node 1, Person B = Node 2.
- **Setup:** two people, two laptops, each with exactly ONE ESP32 + RA-02 board on USB and a phone. You cannot talk to the other laptop's agent: the humans relay short `SYNC` lines over their team chat.
- **Do everything below yourself by running terminal commands.** Never ask the human to type a command.
  Before each command, explain it in one line. If a command fails, diagnose it, fix it and continue.
- **Read serial output only with** `firmware/relay/scripts/serial_log.py` (non-interactive; `--reset`, `--seconds`, `--until`, `--send`). Never open an interactive monitor; you can't read it.
- Also read `firmware/relay/CLAUDE.md` (project context) and `firmware/relay/docs/HARDWARE_TEST.md` (log meanings, drop reasons).
- Only ask the human for the **HUMAN ACTIONS** in section b.
- **Rules:**
  - Never `git push --force` (or `-f`, `--force-with-lease`), never `git reset --hard`, never `git clean`.
  - Don't edit existing code files. The **only** exception: the pin constants in `firmware/relay/include/config.h` in step 4.
  - Don't commit to `main`. Work on `feature/android-lora-transport`.
  - Report honestly: show the log line that proves each PASS. Never mark something PASS without it.

### Commands you will use (define these once)

Run everything from the repo root. `<PIO>` = PlatformIO CLI, `<PY>` = PlatformIO's Python (has pyserial), `<PORT>` = the board's port.

| Purpose | Command |
|---|---|
| Flash an env | `<PIO> run -d firmware/relay -e <env> -t upload --upload-port <PORT>` (envs: `lora-ping`, `bridge`, `radiodiag`) |
| Restart board + read boot log | `<PY> firmware/relay/scripts/serial_log.py <PORT> --reset --seconds 12` |
| Wait for a line | `<PY> firmware/relay/scripts/serial_log.py <PORT> --seconds 90 --until "<regex>"` |
| Send one serial line | `<PY> firmware/relay/scripts/serial_log.py <PORT> --send "<text>" --seconds 5` |

Notes: flashing resets the board before you can listen, so always read the boot log with `--reset`. Only one program can hold the port: never run two `serial_log.py` at once, and close any monitor first.
If `--reset` prints nothing at all, print `ACTION NEEDED: unplug the board's USB cable, wait 3 seconds, plug it back in, then tell me "done"` and run the `--reset` command again after "done".

---

## b. HUMAN ACTIONS (the only things the human does)

1. Plug in the board with a USB **data** cable, and wire the RA-02 (see the wiring table in `firmware/relay/docs/HARDWARE_TEST.md` §1).
2. Hold the **BOOT** button while flashing if it says `Failed to connect`.
3. Unplug and replug the USB cable to restart the board.
4. Use the phone: **nRF Connect**, or the **Neyra app** (including the **LoRa bridge** switch in Profile). Plug the phone in by USB for installing the app.
5. Relay the `SYNC` lines to the other person over chat, and pass their replies back to you.

When you need one of these, print a line starting with **`ACTION NEEDED:`** that says exactly what to do, then stop and wait for the human's reply.

---

## c. Agent steps (in order)

Keep a running results table (step, PASS/FAIL, proof line) for step 10.

### 1. Sync the code and find PlatformIO

- `git fetch origin`: download the latest branches.
- `git checkout feature/android-lora-transport`: switch to the test branch.
- `git pull`: update it.
- Check that `firmware/relay/platformio.ini` exists. If not, stop and report.
- Find PlatformIO, in this order:
  1. `pio` or `platformio` on PATH
  2. Mac/Linux `~/.platformio/penv/bin/pio`; Windows `%USERPROFILE%\.platformio\penv\Scripts\pio.exe`
  3. Not found: install it with `python3 -m pip install --user platformio` (Windows: `py -m pip install --user platformio`), then find it again (`python3 -m platformio --version` works as `<PIO>` = `python3 -m platformio`).
- `<PY>`: the Python next to `<PIO>` (`~/.platformio/penv/bin/python`, `%USERPROFILE%\.platformio\penv\Scripts\python.exe`), or the Python you installed PlatformIO with. Check: `<PY> -c "import serial"` must succeed (otherwise `<PY> -m pip install pyserial`).
- The first build downloads the ESP32 toolchain (several minutes). That is normal.

### 2. Find this laptop's ESP32 port

- `<PIO> device list --json-output`: list serial ports.
- Pick the one whose description/hwid matches **CP210x / CP2102 / CH340 / CH9102 / USB-SERIAL / 10C4:EA60 / 1A86:**. Ignore Bluetooth and debug ports. On macOS use the `/dev/cu.*` name.
- None: `ACTION NEEDED: the board is not detected. Try a different USB data cable (charge-only cables don't work) or another USB port, then tell me "done".` On Windows also check that the CP210x or CH340 driver is installed. Repeat.
- More than one: list them and ask the human which board is theirs (`ACTION NEEDED: unplug the board for a moment and tell me "unplugged"`, then see which port disappeared).

### 3. Radio test (lora-ping)

LoRa ↔ LoRa already works on the team's boards (verified with the `divanshi` branch, same code and pins).
This step only re-confirms that *this* board and wiring are fine before the bridge test. If it fails here
but worked before, suspect a loose wire or a different board, not the code.

- Flash: Node 1 normally. **Node 2** with a label so the other side can tell who sent:
  - Mac/Linux: `PLATFORMIO_BUILD_FLAGS='-DLORA_NODE_ID=\"node-2\"' <PIO> run -d firmware/relay -e lora-ping -t upload --upload-port <PORT>`
  - Windows PowerShell: `$env:PLATFORMIO_BUILD_FLAGS='-DLORA_NODE_ID=\"node-2\"'; <PIO> run ...; Remove-Item Env:PLATFORMIO_BUILD_FLAGS`
  - Node 1 uses `node-1` the same way.
- Read the boot log (`--reset --seconds 12`) and look at the first 15 lines.
  - **RADIO OK:** `LoRa ready`.
  - **RADIO FAILED:** `LoRa init failed, RadioLib code -2` (or any other code). Go to step 4.
  - Nothing at all: replug fallback (section a), then retry.

### 4. Only if RADIO FAILED: radiodiag

- Flash `radiodiag`, read 15 s (`--reset --seconds 15`). Lines look like:
  `RADIODIAG sck=18 miso=19 mosi=23 nss=5 rst=14 dio0=26 version=0x12 FOUND rst=ok dio0=ok (config.h default)`
  and end with `RADIODIAG RESULT ...`.
- **`RESULT pins=` the config.h default** (18,19,23,5,14,26): wiring is fine; the radio answered here. Reflash lora-ping and recheck; if still failing, report it (possibly a bad module).
- **`RESULT pins=` a different set:** change **only** the six pin constants `LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_CS_PIN, LORA_RESET_PIN, LORA_DIO0_PIN` in `firmware/relay/include/config.h` to that set (nss → `LORA_CS_PIN`). Show the human the diff. Reflash lora-ping (step 3) and recheck. Record the change: the other board may need the same.
- **`RESULT no chip answered`:** `ACTION NEEDED: unplug the board. With a multimeter in continuity (beep) mode, check 3.3V and GND, then each signal wire from the ESP32 pin to the RA-02 pad: SCK-18, MISO-19, MOSI-23, NSS-5, RST-14, DIO0-26. Also check 3V3 and GND do NOT beep together. Fix any wire that doesn't beep, plug back in and tell me "done".` Then rerun radiodiag.
- `... (chip answers, but rst or dio0 did not check out ...)`: same ACTION NEEDED, focused on the RST and DIO0 wires.

### 5. SYNC 1

Print `SYNC 1: Node <N> radio OK`, then
`ACTION NEEDED: send "SYNC 1: Node <N> radio OK" to the other person, and tell me when they have sent you their SYNC 1.`
Wait. Don't continue until the human confirms the other node also has SYNC 1.

### 6. Radio-to-radio test

Boards 1-3 m apart, both antennas on, both still running lora-ping. Round 1: **A → B**. Round 2: **B → A**.

- **Receiver** (Node 2 in round 1, Node 1 in round 2): print
  `ACTION NEEDED: send "Node <N> listening, send now" to the other person. I'm listening for 90 seconds.`
  then immediately run `serial_log.py <PORT> --seconds 90 --until "RX from"`.
  **PASS** = a line `RX from node-<other>:...:hello from <A|B> | RSSI ... | SNR ...`. Report it and ask the human to send `"Node <N> received: <that line>"` to the other person.
- **Sender** (Node 1 in round 1, Node 2 in round 2): `ACTION NEEDED: tell me when the other person says they are listening.` Then send it yourself:
  `serial_log.py <PORT> --send "send hello from <A|B>" --seconds 5`. **Sent** = `TX ok: node-<N>:...`.
  (Fallback only if you can't run the script: `ACTION NEEDED: paste "send hello from A" into the lora-ping monitor in one go and press Enter`. Typing letter by letter fails.)
- FAIL = no `RX from` within 90 s. See the stuck table. Report PASS/FAIL per direction with the proof line.

### 7. Bridge firmware

- Flash `bridge`, read the boot log (`--reset --seconds 12`). All three must appear:
  - `LORADBG BOOT lora freq=433.0MHz bw=125.0kHz sf=9 cr=4/7 sync=0x12 ...`
  - `LORADBG BOOT router started loraAvailable=1 ...` (**must say `loraAvailable=1`**; `=0` means the radio wasn't detected: go back to step 4)
  - `LORADBG BLE advertising name=NEYRA-LORA ... addr=<addr>`
- Print `SYNC 2: Node <N> bridge ready, BLE addr <addr>` and
  `ACTION NEEDED: send "SYNC 2: Node <N> bridge ready, BLE addr <addr>" to the other person, and tell me their SYNC 2 line.`

### 8. nRF Connect phone test

Print:
`ACTION NEEDED: on your phone open nRF Connect, scan, and connect to the NEYRA-LORA with address <addr> (yours, not the other person's). Open service 7f6d2001, tap the subscribe icon on 7f6d2003. Tell me "subscribed".`

Then run the round. Round 1: **A sends, B receives**; round 2: swap.

- **Receiver:** print `ACTION NEEDED: tell the other person "go". I'm listening for 120 seconds.` and run
  `serial_log.py <PORT> --seconds 120 --until "BLE notify sent"`.
- **Sender:** print `ACTION NEEDED: when the other person says "go", tap the write icon on 7f6d2002, choose BYTE ARRAY, enter 0001544553542D303030312D4E455952412D4C4F52412D4F4B21 and send. I'm listening for 120 seconds.` and run
  `serial_log.py <PORT> --seconds 120 --until "LORA tx ok|DROP"`.
- Report every new `LORADBG` line. Expected:
  - Sender: `BLE write chunk=...`, `BLE packet from phone complete bytes=24`, `LORA tx start ...`, `LORA tx ok ...`
  - Receiver: `LORA rx bytes=24 rssi=... snr=...`, `LORA rx data ... hex=544553542D...`, `LORA queued for phone ...`, `BLE notify sent bytes=24 ...`
  - The receiver's phone shows the value on 7f6d2003 (at the default MTU as two notifications; the last one ends `...4F-4B-21`).
- Wait 20 s before round 2, or the bridge drops the repeated test packet as `DUPLICATE` (on purpose).
- First hop with no line = where it broke; look up the `DROP reason=` in `firmware/relay/docs/HARDWARE_TEST.md` §6.

### 9. Neyra app test (only after step 8 passes both ways)

- `ACTION NEEDED: on your phone enable Developer options and USB debugging, plug the phone into this laptop, accept "Allow USB debugging", and tell me "phone connected". In nRF Connect, disconnect from NEYRA-LORA first (the bridge accepts one phone at a time).`
- Build and install: `cd android && ./gradlew installDebug` (Windows: `gradlew.bat installDebug`). Needs a JDK and the Android SDK (Android Studio provides both). If `SDK location not found`, find the SDK (`~/Library/Android/sdk`, `~/Android/Sdk`, `%LOCALAPPDATA%\Android\Sdk`) and create `android/local.properties` with `sdk.dir=<path>` (escape `\` as `\\` on Windows). This file is gitignored; never commit it.
- `ACTION NEEDED: open Neyra, finish setup, go to Profile and turn on "LoRa bridge". Wait until it says "Connected". Tell me when it does.`
- `ACTION NEEDED: Person A: send a short message (under ~80 characters) to Person B's contact. Person B: tell me whether it arrived.` Swap roles afterwards.
- While they do this, capture the bridge log (`serial_log.py <PORT> --seconds 120`) and afterwards the app log: `adb logcat -d -s MESHDBG | tail -150` (adb is in the SDK `platform-tools`). Keep lines with `LORA`, `SEND`, `RX`, `ROUTE`, `PEER`.
  - App side, look for: `LORA bridge ready addr=...`, `PEER lora gateway up`, `SEND lora handed to bridge ...`, on the receiver `LORA rx packet ...` and `RX received ... via=LORA`.
  - The message only goes over LoRa if the two phones can't reach each other directly. Keep the phones far apart (different rooms/floors, out of BLE range) and turn off **Wi-Fi Direct** in Neyra's Profile. Leave Neyra's **Bluetooth LE** switch and Android Bluetooth **on**: the bridge link needs Bluetooth, and with both mesh switches off the app treats all transports as disabled.
- Record what happened, including failures. This step is not verified by anyone yet.

### 10. Record results (your own file)

- Create `firmware/relay/docs/results/node<N>-<YYYY-MM-DD>.md` (**your own file, never a shared one**) with: node, person, board/USB chip, port, BLE address, a PASS/FAIL table for steps 3-9 with proof lines, any `config.h` change, and the raw `LORADBG`/`RADIODIAG`/`MESHDBG` lines in code blocks.
- `git add firmware/relay/docs/results/node<N>-<date>.md` (plus `firmware/relay/include/config.h` only if step 4 changed it): stage only these.
- `git commit -m "Add Node <N> hardware test results"`: save them.
  (If git asks for a name/email: `git config user.name "<name>"`, `git config user.email "<email>"`; ask the human for both.)
- `git pull --rebase`: put your commit after the other person's. Separate files mean no conflicts.
- `git push origin feature/android-lora-transport`: upload. If rejected, `git pull --rebase` again and push. **Never force-push.**

### 11. RESULT block

Print this for the human to paste into the team chat:

```
RESULT Node <N> (Person <A|B>)
radio (lora-ping):      PASS/FAIL
radio A->B / B->A:      PASS/FAIL / PASS/FAIL   (RSSI .. dBm)
bridge boot:            PASS/FAIL   BLE addr <addr>
nRF bridge A->B / B->A: PASS/FAIL / PASS/FAIL
Neyra app over LoRa:    PASS/FAIL/NOT RUN
config.h change:        none | <new pins>
results file:           firmware/relay/docs/results/node<N>-<date>.md (pushed)
```

---

## d. Stuck?

| Symptom | Fix |
|---|---|
| **No port** / board not in `device list` | Different USB **data** cable or port (HUMAN). Install the CP210x (Silicon Labs) or CH340 (WCH) driver. Linux: `sudo usermod -aG dialout $USER`, log out/in. Close Arduino IDE / other monitors. |
| **`Failed to connect to ESP32`** / `Timed out waiting for packet header` | Run the flash again and `ACTION NEEDED: hold the BOOT button until you see "Writing at 0x...", then let go`. |
| **Port busy / access denied / `could not open port`** | Another program has the port. Stop other `serial_log.py` or monitors; replug (HUMAN). |
| **Garbled serial** (`⸮⸮⸮`) | A little after reset is normal (boot ROM speed). Constant garbage: baud isn't 115200; use `serial_log.py` (115200). |
| **Reset loop** / `Brownout detector was triggered` | Not enough USB power: another USB port (not a hub), shorter cable (HUMAN). Check the RA-02 is on **3.3 V**, never 5 V. |
| **`LoRa init failed, RadioLib code -2`** | Radio not detected over SPI: wiring/power. Step 4 (radiodiag). |
| **`loraAvailable=0`** / **`DROP reason=LORA_UNAVAILABLE`** | Same as code -2: the bridge runs BLE-only. Step 4. |
| **Nothing arrives** (`TX ok` but no `RX from`, or `tx ok` but no `LORA rx`) | Both boards must be flashed from the same commit (both print `433.0 MHz`, sf=9, sync=0x12). Antennas on both. 1-3 m apart, not touching. If step 4 changed `config.h` on one node only, check the other. `LORA_CRC_MISMATCH`: move them apart a bit. |
| `DROP reason=NO_PHONE_CONNECTED` / `PHONE_NOT_SUBSCRIBED` | Receiver's phone isn't connected / didn't tap subscribe on 7f6d2003 (HUMAN). |
| `DROP reason=DUPLICATE` | Same test packet within 20 s. Wait 20 s. |
| No NEYRA-LORA in nRF Connect | Bridge not flashed, or another phone/app is connected to it (one at a time). Phone Bluetooth **and** Location on. |
| `send` in lora-ping prints `Unknown command` | Typed slowly. Use `serial_log.py --send`. |
| `SDK location not found` / Gradle JDK errors | See step 9: `android/local.properties` with `sdk.dir`; use Android Studio's bundled JDK (`JAVA_HOME`). |
| `git push` rejected | `git pull --rebase`, then push. Never force. |
| A `.ps1` script fails on Windows | They were never run on Windows. Use the `<PIO>` commands above directly. |

Who to send logs to: post your **RESULT block** plus the raw `LORADBG` / `RADIODIAG` / `MESHDBG` lines (they're in your results file) in the team chat for the repo owner.

---

## e. What is verified and what is not

| Path | Status |
|---|---|
| Phone ↔ ESP32 over BLE (connect, subscribe, write, notify) | **Verified on hardware** |
| Firmware builds (bridge, lora-ping, radiodiag), host unit tests, Android unit tests (incl. LoRa chunk framing) | Verified (builds/tests) |
| RA-02 detection and LoRa ↔ LoRa (lora-ping) | **Verified on hardware** by the team using the `divanshi` branch; its radio code is byte-identical to `lora-ping`, same pins. Steps 3-6 are a quick re-check of *this* setup, not a first test. |
| BLE → LoRa → BLE between two bridges | **Not verified** |
| Android LoRa client on real phones (scan, connect, MTU, subscribe, send/receive) | **Not verified** |
| Reconnect after the bridge disconnects | **Not verified** |
| Delivery ACK coming back over LoRa | **Not verified** |
| `radiodiag` on real hardware | **Not verified** |
| Windows `.ps1` scripts | **Never run**; use `<PIO>` commands directly if one fails |

Known limits: one LoRa packet is at most 255 bytes, so only short text messages (about 80 characters) go over LoRa; longer ones and attachments stay on BLE/Wi-Fi. "Sent over LoRa" means the bridge accepted it; real delivery is confirmed only by the delivery ACK.

---

## f. When everything passes

Tell both humans: **open a pull request from `feature/android-lora-transport` into `main`** on GitHub for the repo owner to review and merge. Don't open it before steps 3-8 pass on both nodes, and don't merge it yourselves.
