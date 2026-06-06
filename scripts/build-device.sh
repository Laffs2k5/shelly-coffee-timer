#!/usr/bin/env bash
# Generate the deployable device script (device/coffee.min.js) from the readable source
# (device/coffee.js) by stripping full-line comments, blank lines, and leading indentation.
#
# WHY: the Shelly Plug S Gen3 mJS heap is tiny. The fully-commented v2 source (~15 KB) crashes the
# runtime with out_of_memory; the stripped build (~10 KB, ≈ the proven v1 footprint) runs stably.
# Flash device/coffee.min.js to the device, NOT device/coffee.js. Verified on hardware 2026-06-06
# (see docs/testing/HW-VALIDATION-v2.md). The script has no "//" inside string literals, so dropping
# full-line comments is safe; trailing inline comments are left as-is (mJS ignores them).
#
# Usage:
#   scripts/build-device.sh           # regenerate device/coffee.min.js
#   scripts/build-device.sh --check   # exit 1 if coffee.min.js is stale vs coffee.js (CI guard)
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=device/coffee.js
OUT=device/coffee.min.js

minify() {
  awk '
    /^[[:space:]]*\/\// { next }   # drop full-line // comments
    /^[[:space:]]*$/    { next }   # drop blank lines
    { sub(/^[[:space:]]+/, ""); print }   # strip leading indentation
  ' "$SRC"
}

if [ "${1:-}" = "--check" ]; then
  if ! diff -q <(minify) "$OUT" >/dev/null 2>&1; then
    echo "device/coffee.min.js is STALE — run scripts/build-device.sh" >&2
    exit 1
  fi
  echo "device/coffee.min.js is up to date."
else
  minify > "$OUT"
  echo "wrote $OUT ($(wc -c < "$OUT") bytes, from $(wc -c < "$SRC") bytes source)"
fi
