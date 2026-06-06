#!/usr/bin/env bash
# Run the JS logic unit tests (Node built-in test runner — no deps, no hardware):
#   - device/test  : coffee.js mJS logic against a mocked Shelly runtime
#   - web/test      : web fallback pure logic (coffee-core.js)
set -euo pipefail
cd "$(dirname "$0")/.."
exec node --test 'device/test/*.test.js' 'web/test/*.test.js'
