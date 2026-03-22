#!/usr/bin/env bash
set -euo pipefail

# Test staleness rejection: commands with old timestamps should be ignored.
# Requires: source .env (AIO_USER, AIO_KEY, SHELLY_IP must be set)
# Usage: scripts/test-staleness.sh [--dry-run]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${AIO_USER:-}" || -z "${AIO_KEY:-}" || -z "${SHELLY_IP:-}" ]]; then
  echo "Error: AIO_USER, AIO_KEY, and SHELLY_IP must be set in .env" >&2
  exit 1
fi

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  echo "=== DRY RUN MODE — no commands will be executed ==="
  echo ""
fi

AIO_BASE="https://io.adafruit.com/api/v2/${AIO_USER}/feeds"
SHELLY_STATUS="http://${SHELLY_IP}/script/1/coffee_status"
SHELLY_CMD="http://${SHELLY_IP}/script/1/coffee_command"
PASS_COUNT=0
FAIL_COUNT=0
GREEN='\033[0;32m'
RED='\033[0;31m'
RESET='\033[0m'

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo -e "  ${GREEN}PASS${RESET}: $1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo -e "  ${RED}FAIL${RESET}: $1"
  if [[ -n "${2:-}" ]]; then
    echo "        Detail: $2"
  fi
}

json_field() {
  local json="$1"
  local field="$2"
  echo "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null || echo ""
}

aio_post_command() {
  local payload="$1"
  curl -s -X POST "${AIO_BASE}/command/data" \
    -H "X-AIO-Key: ${AIO_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"value\": $(echo "$payload" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}" > /dev/null
}

echo "============================================"
echo "  Staleness Tests"
echo "============================================"
echo ""

# ---------- Setup: ensure device is OFF ----------
echo "[Setup] Ensuring device is OFF before tests"
if $DRY_RUN; then
  echo "  Would send cmd=off via local HTTP"
  echo ""
else
  curl -s "${SHELLY_CMD}?cmd=off" --connect-timeout 5 --max-time 10 > /dev/null 2>&1 || true
  sleep 2
fi

# ---------- Test 1: Stale command (300s in the past) ----------
echo "[1/2] Send command with timestamp 300s in the past"
STALE_TS=$(($(date +%s) - 300))
STALE_PAYLOAD="{\"c\":\"t90\",\"ts\":${STALE_TS}}"
if $DRY_RUN; then
  echo "  Would POST to ${AIO_BASE}/command/data"
  echo "  Payload: ${STALE_PAYLOAD}"
  echo "  Would wait 5s for MQTT delivery"
  echo "  Would GET ${SHELLY_STATUS} and verify state=off (command rejected)"
  echo ""
else
  # Record state before
  BEFORE=$(curl -s "${SHELLY_STATUS}" --connect-timeout 5 --max-time 10)
  BEFORE_STATE=$(json_field "$BEFORE" "state")

  aio_post_command "$STALE_PAYLOAD"
  echo "  Sent stale command (ts=$STALE_TS, ${STALE_TS} = 300s ago)"
  echo "  Waiting 5s for MQTT delivery..."
  sleep 5

  AFTER=$(curl -s "${SHELLY_STATUS}" --connect-timeout 5 --max-time 10)
  AFTER_STATE=$(json_field "$AFTER" "state")

  if [[ "$AFTER_STATE" == "off" ]]; then
    pass "Stale command ignored — device remains off"
  else
    fail "Stale command was NOT rejected — device state changed to $AFTER_STATE" "$AFTER"
  fi
  echo ""
fi

# ---------- Test 2: Fresh command (current timestamp) ----------
echo "[2/2] Send command with current timestamp"
FRESH_TS=$(date +%s)
FRESH_PAYLOAD="{\"c\":\"t90\",\"ts\":${FRESH_TS}}"
if $DRY_RUN; then
  echo "  Would POST to ${AIO_BASE}/command/data"
  echo "  Payload: ${FRESH_PAYLOAD}"
  echo "  Would wait 5s for MQTT delivery"
  echo "  Would GET ${SHELLY_STATUS} and verify state=on"
  echo ""
else
  aio_post_command "$FRESH_PAYLOAD"
  echo "  Sent fresh command (ts=$FRESH_TS)"
  echo "  Waiting 5s for MQTT delivery..."
  sleep 5

  AFTER=$(curl -s "${SHELLY_STATUS}" --connect-timeout 5 --max-time 10)
  AFTER_STATE=$(json_field "$AFTER" "state")

  if [[ "$AFTER_STATE" == "on" ]]; then
    pass "Fresh command accepted — device is on"
  else
    fail "Fresh command was NOT accepted — device state is $AFTER_STATE" "$AFTER"
  fi
  echo ""
fi

# ---------- Cleanup: turn off ----------
echo "[Cleanup] Restoring device to OFF"
if ! $DRY_RUN; then
  curl -s "${SHELLY_CMD}?cmd=off" --connect-timeout 5 --max-time 10 > /dev/null 2>&1 || true
  sleep 1
  echo "  Done."
fi
echo ""

# ---------- Summary ----------
echo "============================================"
echo "  Summary"
echo "============================================"
echo -e "  ${GREEN}Passed${RESET}: $PASS_COUNT"
echo -e "  ${RED}Failed${RESET}: $FAIL_COUNT"
echo ""

if [[ $FAIL_COUNT -gt 0 ]]; then
  echo -e "${RED}Some tests failed.${RESET}"
  exit 1
else
  echo -e "${GREEN}All tests passed.${RESET}"
  exit 0
fi
