#!/usr/bin/env bash
# Build, flash and open the serial monitor in one command.
#
#   ./scripts/flash.sh bridge                 auto-detect the board
#   ./scripts/flash.sh lora-ping /dev/ttyUSB0 pick the port yourself
#   ./scripts/flash.sh radiodiag
#
# Close any other serial monitor first: only one program can use the port.
# Stop the monitor with Ctrl+C.
set -euo pipefail
source "$(dirname "$0")/common.sh"

ENV_NAME="${1:-}"
case " $VALID_ENVS " in
	*" $ENV_NAME "*) ;;
	*) fail "usage: $0 <bridge|lora-ping|radiodiag> [port]" ;;
esac

find_pio
find_port "${2:-}"

echo ">> Flashing '$ENV_NAME' to $PORT (PlatformIO: $PIO)"
if ! "$PIO" run -d "$PROJECT_DIR" -e "$ENV_NAME" -t upload --upload-port "$PORT"; then
	echo "" >&2
	echo "ERROR: upload failed." >&2
	echo "  'Failed to connect to ESP32': run the command again and HOLD the BOOT button" >&2
	echo "  until you see 'Writing at 0x...', then let go." >&2
	echo "  'Permission denied' / 'port busy': close other serial monitors (Arduino, VS Code)." >&2
	exit 1
fi

echo ">> Opening serial monitor on $PORT at 115200 (Ctrl+C to quit)"
echo ">> No output? Press EN/RST on the board, or unplug and replug USB if it has no reset button."
exec "$PIO" device monitor -d "$PROJECT_DIR" -p "$PORT" -b 115200
