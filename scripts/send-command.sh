#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${AIO_USER:-}" || -z "${AIO_KEY:-}" ]]; then
  echo "Error: AIO_USER and AIO_KEY must be set in .env" >&2
  exit 1
fi

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <command>  (e.g. t90, off, on)" >&2
  exit 1
fi

CMD="$1"
TS=$(date +%s)
PAYLOAD="{\"c\":\"${CMD}\",\"ts\":${TS}}"

echo "Sending command: ${PAYLOAD}"
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"value\": $(echo "$PAYLOAD" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}"
echo ""
echo "Done."
