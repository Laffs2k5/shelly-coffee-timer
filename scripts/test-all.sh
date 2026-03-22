#!/usr/bin/env bash
set -euo pipefail

# Runner: executes all test scripts in order and prints a summary.
# Requires: source .env (all variables for individual tests)
# Usage: scripts/test-all.sh [--dry-run]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
RESET='\033[0m'

EXTRA_ARGS=""
if [[ "${1:-}" == "--dry-run" ]]; then
  EXTRA_ARGS="--dry-run"
fi

TESTS=(
  "test-local-api.sh"
  "test-remote-api.sh"
  "test-staleness.sh"
  "test-config.sh"
  "test-schedule.sh"
)

TOTAL=${#TESTS[@]}
PASSED=0
FAILED=0
SKIPPED=0
RESULTS=()

echo "============================================"
echo "  Shelly Coffee Timer — Full Test Suite"
echo "============================================"
echo ""
echo "Running $TOTAL test scripts..."
echo ""

for test_script in "${TESTS[@]}"; do
  SCRIPT_PATH="${SCRIPT_DIR}/${test_script}"

  if [[ ! -f "$SCRIPT_PATH" ]]; then
    echo -e "${YELLOW}SKIP${RESET}: ${test_script} — file not found"
    SKIPPED=$((SKIPPED + 1))
    RESULTS+=("SKIP  ${test_script}")
    echo ""
    continue
  fi

  echo "============================================"
  echo "  Running: ${test_script}"
  echo "============================================"
  echo ""

  if bash "$SCRIPT_PATH" $EXTRA_ARGS; then
    PASSED=$((PASSED + 1))
    RESULTS+=("PASS  ${test_script}")
  else
    FAILED=$((FAILED + 1))
    RESULTS+=("FAIL  ${test_script}")
  fi
  echo ""
done

# ---------- Final Summary ----------
echo ""
echo "============================================"
echo "  FINAL SUMMARY"
echo "============================================"
echo ""

for result in "${RESULTS[@]}"; do
  STATUS="${result%%  *}"
  NAME="${result#*  }"
  case "$STATUS" in
    PASS) echo -e "  ${GREEN}PASS${RESET}  ${NAME}" ;;
    FAIL) echo -e "  ${RED}FAIL${RESET}  ${NAME}" ;;
    SKIP) echo -e "  ${YELLOW}SKIP${RESET}  ${NAME}" ;;
  esac
done

echo ""
echo "  Total:   $TOTAL"
echo -e "  ${GREEN}Passed${RESET}:  $PASSED"
echo -e "  ${RED}Failed${RESET}:  $FAILED"
if [[ $SKIPPED -gt 0 ]]; then
  echo -e "  ${YELLOW}Skipped${RESET}: $SKIPPED"
fi
echo ""

if [[ $FAILED -gt 0 ]]; then
  echo -e "${RED}Some test suites failed.${RESET}"
  exit 1
else
  echo -e "${GREEN}All test suites passed.${RESET}"
  exit 0
fi
