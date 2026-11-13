#!/usr/bin/env bash
# Open the serial monitor only (no build, no flash).
#
#   ./scripts/monitor.sh                 auto-detect the board
#   ./scripts/monitor.sh /dev/ttyUSB1    pick the port yourself
set -euo pipefail
source "$(dirname "$0")/common.sh"

ENV_NAME=""
find_pio
find_port "${1:-}"

echo ">> Serial monitor on $PORT at 115200 (Ctrl+C to quit)"
exec "$PIO" device monitor -d "$PROJECT_DIR" -p "$PORT" -b 115200
