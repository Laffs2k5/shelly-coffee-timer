# AI Test Guide — Shelly Coffee Timer

Structured test prompts for an AI agent to execute against the live system. Each section is self-contained and can be run independently.

---

## Prerequisites

Before running any test:

1. **Environment setup:**
   - The repo is cloned and `.env` exists with real values for `AIO_USER`, `AIO_KEY`, `SHELLY_IP`
   - Run `source .env` in the shell
   - `curl` and `python3` are available

2. **Device accessible:**
   - The Shelly Plug S Gen3 is powered on and connected to wifi
   - Verify: `curl -s http://${SHELLY_IP}/script/1/coffee_status` returns JSON

3. **Adafruit IO accessible:**
   - Verify: `curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" -H "X-AIO-Key: ${AIO_KEY}"` returns JSON

4. **Safety reminder:**
   - The device is in production use (laptop charger runs through the plug)
   - Always restore the switch to OFF after testing
   - Always restore config to default values (dur=90, max=180, sch=0) after testing

---

## Test 1: Device Regression (all Phase 2B tests)

Execute these steps in order. Each step depends on the previous one.

### 1.1 Boot safety — switch is OFF

```
Read device status:
  curl -s http://${SHELLY_IP}/script/1/coffee_status

Verify:
  - state = "off"
  - remaining = 0
```

### 1.2 Local HTTP — coffee_status endpoint

```
curl -s http://${SHELLY_IP}/script/1/coffee_status

Verify response contains all fields:
  - state (string: "on" or "off")
  - remaining (number)
  - mode (string)
  - sch (number: 0 or 1)
  - h (number: 0-23)
  - m (number: 0-59)
  - ntp (boolean)
  - ts (number: unix epoch or 0)
```

### 1.3 Local HTTP — coffee_command (turn on)

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=t90"

Verify:
  - ok = true
  - state = "on"
  - remaining = 90 (the configured dur value)
  - ack = "t90"
```

### 1.4 Local HTTP — extend

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=ext"

Verify:
  - ok = true
  - remaining increased by 30 (should be ~120)
```

### 1.5 Local HTTP — subtract

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=sub"

Verify:
  - ok = true
  - remaining decreased by 30 (should be ~90)
```

### 1.6 Local HTTP — turn off

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=off"

Verify:
  - ok = true
  - state = "off"
  - remaining = 0
```

### 1.7 Local HTTP — error: unknown command

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=bogus"

Verify:
  - HTTP status 400
  - ok = false
  - error = "unknown command"
```

### 1.8 Local HTTP — error: missing cmd

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command"

Verify:
  - HTTP status 400
  - ok = false
  - error = "missing cmd"
```

### 1.9 Remote command via Adafruit IO

```
# Send command
TS=$(date +%s)
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"value\": \"{\\\"c\\\":\\\"t90\\\",\\\"ts\\\":${TS}}\"}"

# Wait 5 seconds for MQTT delivery
sleep 5

# Check device
curl -s http://${SHELLY_IP}/script/1/coffee_status

Verify:
  - state = "on"
  - remaining = 90 (or close to it)
  - mode = "remote"
```

### 1.10 Staleness rejection

```
# Turn off first
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=off"
sleep 2

# Send command with timestamp 5 minutes in the past
STALE_TS=$(($(date +%s) - 300))
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"value\": \"{\\\"c\\\":\\\"t90\\\",\\\"ts\\\":${STALE_TS}}\"}"

# Wait 5 seconds
sleep 5

# Check device
curl -s http://${SHELLY_IP}/script/1/coffee_status

Verify:
  - state = "off" (stale command was rejected)
```

### 1.11 Heartbeat publishing

```
# Turn on
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=t90"
sleep 3

# Read heartbeat from Adafruit IO
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}"

Verify the "value" field (parse as JSON):
  - s = "on"
  - r = 90 (approximately)
  - mode = "remote"
  - ntp = true
  - ts = recent unix timestamp

# Clean up
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=off"
```

### 1.12 Config version gating

```
# Read current config
CURRENT=$(curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/config/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}")
# Extract current version (parse value field as JSON, get v)

# Post config with v+1
# Post config with same v and different values — verify device ignores it

See scripts/test-config.sh for the full procedure.
```

### 1.13 Cleanup

```
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=off"

Verify state = "off"
```

---

## Test 2: Remote-Only Test (no local wifi needed)

This test only uses Adafruit IO REST and can be run from anywhere with internet access.

### 2.1 Read heartbeat

```
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}"

Verify:
  - Response is valid JSON
  - "value" field contains a JSON string with fields: s, r, mode, sch, h, m, ack, ts, ntp
  - ts is a recent unix timestamp (within the last 15 min if device is off, 5 min if on)
```

### 2.2 Send command and verify ack

```
TS=$(date +%s)
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"value\": \"{\\\"c\\\":\\\"t90\\\",\\\"ts\\\":${TS}}\"}"

# Wait for device to process and publish heartbeat
sleep 10

curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}"

Verify:
  - s = "on"
  - ack = "t90"
  - ts is more recent than before the command

# Turn off
TS2=$(date +%s)
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"value\": \"{\\\"c\\\":\\\"off\\\",\\\"ts\\\":${TS2}}\"}"
```

### 2.3 Read config

```
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/config/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}"

Verify:
  - Response contains "value" field
  - Parse value as JSON: should have v, sch, h, m, dur, max fields
```

### 2.4 Check rate limit status

```
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/throttle" \
  -H "X-AIO-Key: ${AIO_KEY}"

Verify: no active bans or throttling
```

---

## Test 3: Relay Verification

The user's laptop charger runs through the Shelly plug. The relay state can be verified via the Linux power supply sysfs interface.

### 3.1 Verify relay turns on

```
# Check initial state
cat /sys/class/power_supply/AC1/online
# Expected: 0 (if device is off) or 1 (if on)

# Turn on
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=t90"
sleep 2

# Check relay
cat /sys/class/power_supply/AC1/online
# Expected: 1

# Turn off
curl -s "http://${SHELLY_IP}/script/1/coffee_command?cmd=off"
sleep 2

# Check relay
cat /sys/class/power_supply/AC1/online
# Expected: 0
```

---

## Test 4: Script Upload and Test

To upload a new version of the mJS script to the device:

### 4.1 Upload via Script.PutCode RPC

```
# Read the script file
SCRIPT_CONTENT=$(cat device/coffee.js)

# Stop the running script
curl -s -X POST -d '{"id":1,"method":"Script.Stop","params":{"id":1}}' \
  http://${SHELLY_IP}/rpc

# Upload the script (may need multiple chunks if > 1500 bytes)
# First chunk (creates/overwrites):
curl -s -X POST -d "{\"id\":1,\"method\":\"Script.PutCode\",\"params\":{\"id\":1,\"code\":\"FIRST_CHUNK\"}}" \
  http://${SHELLY_IP}/rpc

# Subsequent chunks (append):
curl -s -X POST -d "{\"id\":1,\"method\":\"Script.PutCode\",\"params\":{\"id\":1,\"code\":\"NEXT_CHUNK\",\"append\":true}}" \
  http://${SHELLY_IP}/rpc

# Start the script
curl -s -X POST -d '{"id":1,"method":"Script.Start","params":{"id":1}}' \
  http://${SHELLY_IP}/rpc

# Enable auto-start
curl -s -X POST -d '{"id":1,"method":"Script.SetConfig","params":{"id":1,"config":{"enable":true}}}' \
  http://${SHELLY_IP}/rpc
```

### 4.2 Verify after upload

```
# Wait for boot sequence
sleep 5

# Check status
curl -s http://${SHELLY_IP}/script/1/coffee_status

Verify:
  - state = "off" (boot safety)
  - ntp = true or false (depends on timing)

# Run the full regression (Test 1 above)
```

### 4.3 Important notes

- The script must have `YOUR_AIO_USERNAME` replaced with the real username before uploading
- Script chunks must be properly JSON-escaped (backslashes, quotes)
- The Script.PutCode RPC has a per-call size limit; split large scripts into chunks
- After upload, the device boots into OFF state (safe)
