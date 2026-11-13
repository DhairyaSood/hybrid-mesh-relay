# Shared helpers for flash.sh and monitor.sh. Not meant to be run directly.

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALID_ENVS="bridge lora-ping radiodiag"
# USB-serial chips found on ESP32 DevKit boards (CP210x, CH340/CH9102, FTDI).
USB_SERIAL_PATTERN='cp210|ch340|ch910|ch34|usb-serial|usb serial|uart|10c4:ea60|1a86:|0403:6001|usbserial|wchusbserial'

fail() {
	echo "ERROR: $*" >&2
	exit 1
}

# Sets PIO to the PlatformIO CLI: $PIO if set, then PATH, then the standard install location.
find_pio() {
	if [ -n "${PIO:-}" ] && [ -x "${PIO}" ]; then return; fi
	if command -v pio >/dev/null 2>&1; then PIO="$(command -v pio)"; return; fi
	if command -v platformio >/dev/null 2>&1; then PIO="$(command -v platformio)"; return; fi
	local core="${PLATFORMIO_CORE_DIR:-$HOME/.platformio}"
	if [ -x "$core/penv/bin/pio" ]; then PIO="$core/penv/bin/pio"; return; fi
	fail "PlatformIO not found. Install the PlatformIO extension in VS Code (it creates ~/.platformio/penv/bin/pio), or: pip install platformio"
}

# Sets PORT: the given port, or the only ESP32 USB-serial port that is plugged in.
find_port() {
	if [ -n "${1:-}" ]; then PORT="$1"; return; fi

	local ports
	ports="$("$PIO" device list 2>/dev/null | awk -v pat="$USB_SERIAL_PATTERN" '
		/^[^ \t-]/ && !/^(Hardware ID|Description):/ { port = $0; next }
		/^(Hardware ID|Description):/ { if (tolower($0) ~ pat && !(port in seen)) { seen[port] = 1; print port } }
	')"
	# macOS lists each board as /dev/cu.* and /dev/tty.*; cu.* is the one to use.
	if [ "$(uname)" = "Darwin" ]; then
		ports="$(printf '%s\n' "$ports" | grep -v '^/dev/tty\.' || true)"
	fi
	ports="$(printf '%s\n' "$ports" | sed '/^$/d')"

	local count
	count="$(printf '%s\n' "$ports" | sed '/^$/d' | wc -l | tr -d ' ')"
	if [ "$count" = "0" ]; then
		echo "ERROR: no ESP32 board found." >&2
		echo "  - Plug the board in with a DATA USB cable (some cables are charge-only)." >&2
		echo "  - Install the USB driver: CP210x (Silicon Labs) or CH340 (WCH)." >&2
		echo "  - Linux: add yourself to the dialout group, then log out and in." >&2
		echo "  Ports PlatformIO can see:" >&2
		"$PIO" device list >&2 || true
		exit 1
	fi
	if [ "$count" != "1" ]; then
		echo "More than one ESP32 board is plugged in. Pick one and pass it as the port:" >&2
		printf '  %s\n' $ports >&2
		echo "Example: $0 ${ENV_NAME:-bridge} $(printf '%s\n' "$ports" | head -1)" >&2
		exit 1
	fi
	PORT="$ports"
}
