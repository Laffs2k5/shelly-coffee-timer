#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${AIO_USER:-}" || -z "${AIO_KEY:-}" ]]; then
  echo "Error: AIO_USER and AIO_KEY must be set in .env" >&2
  exit 1
fi

echo "WARNING: Adafruit IO allows only ONE MQTT connection per account."
echo "         If the Shelly is connected, this script will fail."
echo ""

TOPIC="${AIO_USER}/f/command"
TEST_MSG='{"c":"test","ts":0}'
OUTFILE=$(mktemp)
trap 'rm -f "$OUTFILE"' EXIT

echo "Subscribing to ${TOPIC}..."
mosquitto_sub -h io.adafruit.com -p 8883 \
  --capath /etc/ssl/certs \
  -u "${AIO_USER}" -P "${AIO_KEY}" \
  -t "${TOPIC}" -C 1 > "$OUTFILE" &
SUB_PID=$!

# Give the subscriber time to connect
sleep 3

echo "Publishing test message..."
mosquitto_pub -h io.adafruit.com -p 8883 \
  --capath /etc/ssl/certs \
  -u "${AIO_USER}" -P "${AIO_KEY}" \
  -t "${TOPIC}" \
  -m "$TEST_MSG"

# Wait for subscriber to receive (it exits after -C 1)
wait "$SUB_PID" || true

RECEIVED=$(cat "$OUTFILE")
if [[ "$RECEIVED" == "$TEST_MSG" ]]; then
  echo "PASS: MQTT round-trip verified. Message received."
else
  echo "FAIL: Message mismatch." >&2
  echo "  Sent:     ${TEST_MSG}" >&2
  echo "  Received: ${RECEIVED}" >&2
  exit 1
fi
