#!/usr/bin/env bash
set -euo pipefail

# Test all local HTTP endpoints on the Shelly device.
# Requires: source .env (SHELLY_IP must be set)
# Usage: scripts/test-local-api.sh [--dry-run]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../.env"

if [[ -z "${SHELLY_IP:-}" ]]; then
  echo "Error: SHELLY_IP must be set in .env" >&2
  exit 1
fi

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  echo "=== DRY RUN MODE — no commands will be executed ==="
  echo ""
fi

BASE_URL="http://${SHELLY_IP}/script/1"
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

# Helper: curl with timeout, return body. Sets HTTP_CODE as side effect.
do_curl() {
  local url="$1"
  local tmpfile
  tmpfile=$(mktemp)
  HTTP_CODE=$(curl -s -o "$tmpfile" -w "%{http_code}" --connect-timeout 5 --max-time 10 "$url") || {
    HTTP_CODE="000"
    echo "" > "$tmpfile"
  }
  RESPONSE=$(cat "$tmpfile")
  rm -f "$tmpfile"
}

# Helper: extract JSON field (simple, uses python3)
json_field() {
  local json="$1"
  local field="$2"
  echo "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null || echo ""
}

echo "============================================"
echo "  Local API Tests — ${SHELLY_IP}"
echo "============================================"
echo ""

# ---------- Test 1: GET coffee_status ----------
echo "[1/8] GET /script/1/coffee_status"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_status"
  echo "  Would verify: HTTP 200, JSON with fields: state, remaining, mode, sch, h, m, ntp, ts"
  echo ""
else
  do_curl "${BASE_URL}/coffee_status"
  if [[ "$HTTP_CODE" == "200" ]]; then
    # Verify expected fields exist
    state=$(json_field "$RESPONSE" "state")
    remaining=$(json_field "$RESPONSE" "remaining")
    ntp=$(json_field "$RESPONSE" "ntp")
    if [[ -n "$state" && -n "$remaining" && -n "$ntp" ]]; then
      pass "coffee_status returns 200 with expected fields (state=$state, remaining=$remaining)"
    else
      fail "coffee_status returned 200 but missing fields" "$RESPONSE"
    fi
  else
    fail "coffee_status returned HTTP $HTTP_CODE" "$RESPONSE"
  fi
  echo ""
fi

# ---------- Test 2: GET coffee_command?cmd=t90 ----------
echo "[2/8] GET /script/1/coffee_command?cmd=t90"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command?cmd=t90"
  echo "  Would verify: HTTP 200, ok=true, state=on, remaining=90"
  echo ""
else
  do_curl "${BASE_URL}/coffee_command?cmd=t90"
  if [[ "$HTTP_CODE" == "200" ]]; then
    ok=$(json_field "$RESPONSE" "ok")
    state=$(json_field "$RESPONSE" "state")
    remaining=$(json_field "$RESPONSE" "remaining")
    if [[ "$ok" == "True" && "$state" == "on" ]]; then
      pass "cmd=t90 accepted: state=on, remaining=$remaining"
    else
      fail "cmd=t90 unexpected response" "$RESPONSE"
    fi
  else
    fail "cmd=t90 returned HTTP $HTTP_CODE" "$RESPONSE"
  fi
  # Brief pause for state to settle
  sleep 1
  echo ""
fi

# ---------- Test 3: GET coffee_command?cmd=ext (extend) ----------
echo "[3/8] GET /script/1/coffee_command?cmd=ext"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command?cmd=ext"
  echo "  Would verify: HTTP 200, remaining increased (should be ~120)"
  echo ""
else
  # Read baseline
  do_curl "${BASE_URL}/coffee_status"
  before_remaining=$(json_field "$RESPONSE" "remaining")

  do_curl "${BASE_URL}/coffee_command?cmd=ext"
  if [[ "$HTTP_CODE" == "200" ]]; then
    remaining=$(json_field "$RESPONSE" "remaining")
    if [[ "$remaining" -gt "$before_remaining" ]] 2>/dev/null; then
      pass "cmd=ext increased remaining: $before_remaining -> $remaining"
    else
      fail "cmd=ext did not increase remaining: $before_remaining -> $remaining" "$RESPONSE"
    fi
  else
    fail "cmd=ext returned HTTP $HTTP_CODE" "$RESPONSE"
  fi
  sleep 1
  echo ""
fi

# ---------- Test 4: GET coffee_command?cmd=sub (subtract) ----------
echo "[4/8] GET /script/1/coffee_command?cmd=sub"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command?cmd=sub"
  echo "  Would verify: HTTP 200, remaining decreased"
  echo ""
else
  do_curl "${BASE_URL}/coffee_status"
  before_remaining=$(json_field "$RESPONSE" "remaining")

  do_curl "${BASE_URL}/coffee_command?cmd=sub"
  if [[ "$HTTP_CODE" == "200" ]]; then
    remaining=$(json_field "$RESPONSE" "remaining")
    if [[ "$remaining" -lt "$before_remaining" ]] 2>/dev/null; then
      pass "cmd=sub decreased remaining: $before_remaining -> $remaining"
    else
      fail "cmd=sub did not decrease remaining: $before_remaining -> $remaining" "$RESPONSE"
    fi
  else
    fail "cmd=sub returned HTTP $HTTP_CODE" "$RESPONSE"
  fi
  sleep 1
  echo ""
fi

# ---------- Test 5: GET coffee_command?cmd=off ----------
echo "[5/8] GET /script/1/coffee_command?cmd=off"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command?cmd=off"
  echo "  Would verify: HTTP 200, state=off, remaining=0"
  echo ""
else
  do_curl "${BASE_URL}/coffee_command?cmd=off"
  if [[ "$HTTP_CODE" == "200" ]]; then
    state=$(json_field "$RESPONSE" "state")
    remaining=$(json_field "$RESPONSE" "remaining")
    if [[ "$state" == "off" && "$remaining" == "0" ]]; then
      pass "cmd=off accepted: state=off, remaining=0"
    else
      fail "cmd=off unexpected response" "$RESPONSE"
    fi
  else
    fail "cmd=off returned HTTP $HTTP_CODE" "$RESPONSE"
  fi
  sleep 1
  echo ""
fi

# ---------- Test 6: GET coffee_command?cmd=bogus (invalid command) ----------
echo "[6/8] GET /script/1/coffee_command?cmd=bogus"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command?cmd=bogus"
  echo "  Would verify: HTTP 400, ok=false, error present"
  echo ""
else
  do_curl "${BASE_URL}/coffee_command?cmd=bogus"
  if [[ "$HTTP_CODE" == "400" ]]; then
    ok=$(json_field "$RESPONSE" "ok")
    error=$(json_field "$RESPONSE" "error")
    if [[ "$ok" == "False" && -n "$error" ]]; then
      pass "cmd=bogus correctly rejected: error=$error"
    else
      fail "cmd=bogus returned 400 but unexpected body" "$RESPONSE"
    fi
  else
    fail "cmd=bogus returned HTTP $HTTP_CODE (expected 400)" "$RESPONSE"
  fi
  echo ""
fi

# ---------- Test 7: GET coffee_command (no cmd param) ----------
echo "[7/8] GET /script/1/coffee_command (no cmd)"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command"
  echo "  Would verify: HTTP 400, ok=false, error='missing cmd'"
  echo ""
else
  do_curl "${BASE_URL}/coffee_command"
  if [[ "$HTTP_CODE" == "400" ]]; then
    ok=$(json_field "$RESPONSE" "ok")
    error=$(json_field "$RESPONSE" "error")
    if [[ "$ok" == "False" && "$error" == "missing cmd" ]]; then
      pass "Missing cmd correctly rejected: error='$error'"
    else
      fail "Missing cmd returned 400 but unexpected body" "$RESPONSE"
    fi
  else
    fail "Missing cmd returned HTTP $HTTP_CODE (expected 400)" "$RESPONSE"
  fi
  echo ""
fi

# ---------- Test 8: Verify switch is OFF after all tests ----------
echo "[8/8] Ensure switch is restored to OFF"
if $DRY_RUN; then
  echo "  Would GET ${BASE_URL}/coffee_command?cmd=off"
  echo "  Would GET ${BASE_URL}/coffee_status and verify state=off"
  echo ""
else
  # Send off command to ensure clean state
  do_curl "${BASE_URL}/coffee_command?cmd=off"
  sleep 1
  do_curl "${BASE_URL}/coffee_status"
  state=$(json_field "$RESPONSE" "state")
  if [[ "$state" == "off" ]]; then
    pass "Switch restored to OFF"
  else
    fail "Switch not restored to OFF (state=$state)" "$RESPONSE"
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
