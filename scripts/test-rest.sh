#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${AIO_USER:-}" || -z "${AIO_KEY:-}" ]]; then
  echo "Error: AIO_USER and AIO_KEY must be set in .env" >&2
  exit 1
fi

TEST_VALUE='{"s":"off","r":0,"mode":"","sch":0,"h":6,"m":10,"ack":"","ts":1711000000,"ntp":true}'

echo "Writing test heartbeat value..."
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"value\": $(echo "$TEST_VALUE" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}" > /dev/null

echo "Reading back last value..."
RETURNED=$(curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["value"])')

if [[ "$RETURNED" == "$TEST_VALUE" ]]; then
  echo "PASS: Round-trip verified. Value matches."
else
  echo "FAIL: Values do not match." >&2
  echo "  Sent:     ${TEST_VALUE}" >&2
  echo "  Received: ${RETURNED}" >&2
  exit 1
fi
