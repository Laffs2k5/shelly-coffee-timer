#!/usr/bin/env bash
set -euo pipefail

# Test schedule fire-and-disarm: set schedule for 2 min ahead, wait, verify.
# Requires: source .env (AIO_USER, AIO_KEY, SHELLY_IP must be set)
# Usage: scripts/test-schedule.sh [--dry-run]
#
# WARNING: This test takes up to 3 minutes and modifies the schedule config.

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
YELLOW='\033[1;33m'
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

aio_post_config() {
  local payload="$1"
  curl -s -X POST "${AIO_BASE}/config/data" \
    -H "X-AIO-Key: ${AIO_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"value\": $(echo "$payload" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}" > /dev/null
}

echo "============================================"
echo "  Schedule Fire & Disarm Test"
echo "============================================"
echo ""
echo -e "${YELLOW}NOTE: This test takes up to 3 minutes.${RESET}"
echo ""

# ---------- Step 1: Ensure device is OFF ----------
echo "[1/5] Ensure device is OFF"
if $DRY_RUN; then
  echo "  Would send cmd=off via local HTTP"
  echo ""
else
  curl -s "${SHELLY_CMD}?cmd=off" --connect-timeout 5 --max-time 10 > /dev/null 2>&1 || true
  sleep 1
  echo "  Done."
  echo ""
fi

# ---------- Step 2: Read device time and current config version ----------
echo "[2/5] Read device time and config version"
if $DRY_RUN; then
  echo "  Would GET ${SHELLY_STATUS} to read device time"
  echo "  Would GET ${AIO_BASE}/config/data/last for config version"
  echo "  Would compute schedule time = now + 2 minutes"
  echo ""
  TARGET_H=6
  TARGET_M=12
  NEW_V=100
else
  # Get device local time
  STATUS=$(curl -s "${SHELLY_STATUS}" --connect-timeout 5 --max-time 10)
  DEVICE_TS=$(json_field "$STATUS" "ts")
  NTP=$(json_field "$STATUS" "ntp")

  if [[ "$NTP" != "True" ]]; then
    fail "Device NTP not synced — schedule test requires NTP"
    echo ""
    echo "============================================"
    echo "  Summary: Cannot run schedule test without NTP"
    echo "============================================"
    exit 1
  fi

  # Compute target time: 2 minutes from now in device's local time
  # We use the device's ts (unix epoch) and convert to local time
  TARGET_EPOCH=$((DEVICE_TS + 120))
  TARGET_H=$(python3 -c "import datetime; t=datetime.datetime.fromtimestamp($TARGET_EPOCH); print(t.hour)")
  TARGET_M=$(python3 -c "import datetime; t=datetime.datetime.fromtimestamp($TARGET_EPOCH); print(t.minute)")

  echo "  Device time (epoch): $DEVICE_TS"
  echo "  Target schedule: ${TARGET_H}:$(printf '%02d' "$TARGET_M") (2 min from now)"

  # Read current config version
  CFG_RESPONSE=$(curl -s "${AIO_BASE}/config/data/last" -H "X-AIO-Key: ${AIO_KEY}")
  CFG_VALUE=$(echo "$CFG_RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("value",""))' 2>/dev/null || echo "")
  if [[ -n "$CFG_VALUE" ]]; then
    CURRENT_V=$(echo "$CFG_VALUE" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read()).get("v",0))' 2>/dev/null || echo "0")
  else
    CURRENT_V=0
  fi
  NEW_V=$((CURRENT_V + 1))
  echo "  Config version: $CURRENT_V -> $NEW_V"
  echo ""
fi

# ---------- Step 3: Post config with schedule armed ----------
echo "[3/5] Post config with schedule armed for ${TARGET_H}:$(printf '%02d' "${TARGET_M}")"
CONFIG_PAYLOAD="{\"v\":${NEW_V},\"sch\":1,\"h\":${TARGET_H},\"m\":${TARGET_M},\"dur\":90,\"max\":180}"
if $DRY_RUN; then
  echo "  Would POST config: $CONFIG_PAYLOAD"
  echo "  Would wait for MQTT delivery"
  echo ""
else
  aio_post_config "$CONFIG_PAYLOAD"
  echo "  Posted config: $CONFIG_PAYLOAD"
  sleep 3
  echo ""
fi

# ---------- Step 4: Poll until schedule fires or timeout ----------
echo "[4/5] Polling status every 15s (up to 3 min timeout)"
if $DRY_RUN; then
  echo "  Would poll ${SHELLY_STATUS} every 15s"
  echo "  Would check: state=on, mode=sch, sch=0"
  echo "  Would timeout after 3 minutes if schedule does not fire"
  echo ""
else
  TIMEOUT=180
  ELAPSED=0
  FIRED=false

  while [[ $ELAPSED -lt $TIMEOUT ]]; do
    STATUS=$(curl -s "${SHELLY_STATUS}" --connect-timeout 5 --max-time 10 2>/dev/null || echo "")
    if [[ -n "$STATUS" ]]; then
      STATE=$(json_field "$STATUS" "state")
      MODE=$(json_field "$STATUS" "mode")
      SCH=$(json_field "$STATUS" "sch")
      echo "    [${ELAPSED}s] state=$STATE mode=$MODE sch=$SCH"
      if [[ "$STATE" == "on" && "$MODE" == "sch" ]]; then
        FIRED=true
        break
      fi
    else
      echo "    [${ELAPSED}s] (device unreachable)"
    fi
    sleep 15
    ELAPSED=$((ELAPSED + 15))
  done

  echo ""
  if $FIRED; then
    pass "Schedule fired: state=on, mode=sch"
    # Check that schedule was disarmed
    if [[ "$SCH" == "0" ]]; then
      pass "Schedule auto-disarmed (sch=0)"
    else
      fail "Schedule did not auto-disarm (sch=$SCH)"
    fi
  else
    fail "Schedule did not fire within ${TIMEOUT}s timeout"
  fi
  echo ""
fi

# ---------- Step 5: Cleanup — turn off and restore config ----------
echo "[5/5] Cleanup: turn off and restore config"
if $DRY_RUN; then
  echo "  Would send cmd=off via local HTTP"
  echo "  Would post config with sch=0"
  echo ""
else
  curl -s "${SHELLY_CMD}?cmd=off" --connect-timeout 5 --max-time 10 > /dev/null 2>&1 || true
  sleep 1

  RESTORE_V=$((NEW_V + 1))
  RESTORE_PAYLOAD="{\"v\":${RESTORE_V},\"sch\":0,\"h\":6,\"m\":0,\"dur\":90,\"max\":180}"
  aio_post_config "$RESTORE_PAYLOAD"
  echo "  Device turned off, config restored (v=$RESTORE_V, sch=0)"
  echo ""
fi

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
