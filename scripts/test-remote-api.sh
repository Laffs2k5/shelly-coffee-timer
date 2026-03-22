#!/usr/bin/env bash
set -euo pipefail

# Test Adafruit IO REST endpoints (command, heartbeat, config feeds).
# Requires: source .env (AIO_USER, AIO_KEY must be set)
# Usage: scripts/test-remote-api.sh [--dry-run]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${AIO_USER:-}" || -z "${AIO_KEY:-}" ]]; then
  echo "Error: AIO_USER and AIO_KEY must be set in .env" >&2
  exit 1
fi

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  echo "=== DRY RUN MODE — no commands will be executed ==="
  echo ""
fi

AIO_BASE="https://io.adafruit.com/api/v2/${AIO_USER}/feeds"
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

echo "============================================"
echo "  Remote API Tests — Adafruit IO"
echo "============================================"
echo ""

# ---------- Test 1: POST command with current timestamp ----------
echo "[1/4] POST command to Adafruit IO"
TS=$(date +%s)
CMD_PAYLOAD="{\"c\":\"off\",\"ts\":${TS}}"
if $DRY_RUN; then
  echo "  Would POST to ${AIO_BASE}/command/data"
  echo "  Payload: {\"value\": \"${CMD_PAYLOAD}\"}"
  echo "  Would verify: HTTP 200, value field matches"
  echo ""
else
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${AIO_BASE}/command/data" \
    -H "X-AIO-Key: ${AIO_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"value\": $(echo "$CMD_PAYLOAD" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}")
  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  BODY=$(echo "$RESPONSE" | sed '$d')
  if [[ "$HTTP_CODE" == "200" ]]; then
    RETURNED_VALUE=$(echo "$BODY" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("value",""))' 2>/dev/null || echo "")
    if [[ "$RETURNED_VALUE" == "$CMD_PAYLOAD" ]]; then
      pass "Command accepted, value matches"
    else
      fail "Command accepted but value mismatch" "expected=$CMD_PAYLOAD got=$RETURNED_VALUE"
    fi
  else
    fail "Command POST returned HTTP $HTTP_CODE" "$BODY"
  fi
  echo ""
fi

# ---------- Test 2: GET heartbeat ----------
echo "[2/4] GET heartbeat from Adafruit IO"
if $DRY_RUN; then
  echo "  Would GET ${AIO_BASE}/heartbeat/data/last"
  echo "  Would verify: HTTP 200, JSON response with value field"
  echo ""
else
  RESPONSE=$(curl -s -w "\n%{http_code}" "${AIO_BASE}/heartbeat/data/last" \
    -H "X-AIO-Key: ${AIO_KEY}")
  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  BODY=$(echo "$RESPONSE" | sed '$d')
  if [[ "$HTTP_CODE" == "200" ]]; then
    HB_VALUE=$(echo "$BODY" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("value",""))' 2>/dev/null || echo "")
    if [[ -n "$HB_VALUE" ]]; then
      # Try parsing the inner JSON
      HB_STATE=$(echo "$HB_VALUE" | python3 -c 'import sys,json; d=json.loads(sys.stdin.read()); print(d.get("s","?"))' 2>/dev/null || echo "?")
      pass "Heartbeat retrieved: s=$HB_STATE"
    else
      fail "Heartbeat returned 200 but no value field" "$BODY"
    fi
  else
    fail "Heartbeat GET returned HTTP $HTTP_CODE" "$BODY"
  fi
  echo ""
fi

# ---------- Test 3: POST config with incremented version ----------
echo "[3/4] POST config with incremented version"
if $DRY_RUN; then
  echo "  Would GET ${AIO_BASE}/config/data/last to read current version"
  echo "  Would POST config with v+1"
  echo "  Would verify: HTTP 200, value contains new version"
  echo ""
else
  # Read current config
  RESPONSE=$(curl -s "${AIO_BASE}/config/data/last" -H "X-AIO-Key: ${AIO_KEY}")
  CURRENT_VALUE=$(echo "$RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("value",""))' 2>/dev/null || echo "")

  if [[ -n "$CURRENT_VALUE" ]]; then
    CURRENT_V=$(echo "$CURRENT_VALUE" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read()).get("v",0))' 2>/dev/null || echo "0")
  else
    CURRENT_V=0
  fi

  NEW_V=$((CURRENT_V + 1))
  # Preserve existing config fields if available, otherwise use defaults
  if [[ -n "$CURRENT_VALUE" ]]; then
    CFG_PAYLOAD=$(echo "$CURRENT_VALUE" | python3 -c "
import sys, json
d = json.loads(sys.stdin.read())
d['v'] = $NEW_V
print(json.dumps(d, separators=(',',':')))" 2>/dev/null || echo "{\"v\":${NEW_V},\"sch\":0,\"h\":6,\"m\":0,\"dur\":90,\"max\":180}")
  else
    CFG_PAYLOAD="{\"v\":${NEW_V},\"sch\":0,\"h\":6,\"m\":0,\"dur\":90,\"max\":180}"
  fi

  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${AIO_BASE}/config/data" \
    -H "X-AIO-Key: ${AIO_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"value\": $(echo "$CFG_PAYLOAD" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read().strip()))')}")
  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  BODY=$(echo "$RESPONSE" | sed '$d')
  if [[ "$HTTP_CODE" == "200" ]]; then
    pass "Config v=$NEW_V posted successfully (was v=$CURRENT_V)"
    # Save for next test
    POSTED_V=$NEW_V
  else
    fail "Config POST returned HTTP $HTTP_CODE" "$BODY"
    POSTED_V=0
  fi
  echo ""
fi

# ---------- Test 4: GET config and verify version ----------
echo "[4/4] GET config and verify version matches"
if $DRY_RUN; then
  echo "  Would GET ${AIO_BASE}/config/data/last"
  echo "  Would verify: version field matches what was posted"
  echo ""
else
  sleep 1  # Brief pause to allow AIO to settle
  RESPONSE=$(curl -s "${AIO_BASE}/config/data/last" -H "X-AIO-Key: ${AIO_KEY}")
  READ_VALUE=$(echo "$RESPONSE" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("value",""))' 2>/dev/null || echo "")
  if [[ -n "$READ_VALUE" ]]; then
    READ_V=$(echo "$READ_VALUE" | python3 -c 'import sys,json; print(json.loads(sys.stdin.read()).get("v",0))' 2>/dev/null || echo "0")
    if [[ "$READ_V" == "${POSTED_V:-0}" ]]; then
      pass "Config version matches: v=$READ_V"
    else
      fail "Config version mismatch: expected=${POSTED_V:-?} got=$READ_V"
    fi
  else
    fail "Config GET returned empty value" "$RESPONSE"
  fi
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
