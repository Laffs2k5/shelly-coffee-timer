#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${AIO_USER:-}" || -z "${AIO_KEY:-}" ]]; then
  echo "Error: AIO_USER and AIO_KEY must be set in .env" >&2
  exit 1
fi

for feed in command config heartbeat; do
  echo "Creating feed: ${feed}"
  curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds" \
    -H "X-AIO-Key: ${AIO_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"feed\": {\"name\": \"${feed}\"}}"
  echo ""
done

echo "Done. Created feeds: command, config, heartbeat."
