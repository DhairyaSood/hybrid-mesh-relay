#!/usr/bin/env python3
"""Capture an ESP32's serial output for a fixed time, optionally sending one line.

Made for AI agents and scripts, which can't drive an interactive monitor.
Run it with PlatformIO's Python, which already has pyserial:

  Mac/Linux: ~/.platformio/penv/bin/python scripts/serial_log.py <port> [options]
  Windows:   %USERPROFILE%\\.platformio\\penv\\Scripts\\python.exe scripts\\serial_log.py COM5 [options]

Examples:
  serial_log.py /dev/cu.usbserial-0001 --reset --seconds 15      restart the board, print boot log
  serial_log.py COM5 --send "send hello from A" --seconds 5      lora-ping: transmit one line
  serial_log.py COM6 --seconds 30 --until "RX from"              wait up to 30 s for a line

--reset pulses EN through the USB-serial RTS line (works on most DevKits, even
without an EN button). If no boot log appears, unplug and replug the board.
Exit code: 0 ok (or --until matched), 2 --until not matched, 1 port error.
Only one program can use the port: close any open serial monitor first.
"""
import argparse
import re
import sys
import time

try:
    import serial
except ImportError:
    sys.exit("ERROR: pyserial missing. Run this with PlatformIO's python (see the top of this file).")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("port")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--seconds", type=float, default=10.0, help="how long to listen (default 10)")
    parser.add_argument("--send", help="line to send once, about 1 s after opening (newline added)")
    parser.add_argument("--until", help="stop as soon as a line matches this regex")
    parser.add_argument("--reset", action="store_true", help="restart the board via RTS before listening")
    args = parser.parse_args()

    port = serial.Serial()
    port.port = args.port
    port.baudrate = args.baud
    port.timeout = 0.2
    # Keep DTR/RTS released on open so the board is not reset or put in download mode.
    port.dtr = False
    port.rts = False
    try:
        port.open()
    except serial.SerialException as error:
        print(f"ERROR: cannot open {args.port}: {error}", file=sys.stderr)
        print("Close any other serial monitor, check the port name, or replug the board.", file=sys.stderr)
        return 1

    if args.reset:
        port.rts = True   # EN low
        time.sleep(0.15)
        port.rts = False  # EN high: normal boot (DTR released keeps GPIO0 high)

    pattern = re.compile(args.until) if args.until else None
    deadline = time.time() + args.seconds
    send_at = time.time() + 1.0 if args.send else None
    buffer = b""
    matched = False
    while time.time() < deadline and not matched:
        if send_at is not None and time.time() >= send_at:
            # One write, so the firmware's 50 ms line timeout never splits the command.
            port.write((args.send + "\n").encode())
            port.flush()
            print(f">> sent: {args.send}", flush=True)
            send_at = None
        buffer += port.read(4096)
        while b"\n" in buffer:
            raw, buffer = buffer.split(b"\n", 1)
            line = raw.decode("utf-8", errors="replace").rstrip("\r")
            print(line, flush=True)
            if pattern and pattern.search(line):
                matched = True
                break
    if buffer and not matched:
        print(buffer.decode("utf-8", errors="replace"), flush=True)
    port.close()

    if pattern and not matched:
        print(f">> no line matched '{args.until}' within {args.seconds:g} s", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
