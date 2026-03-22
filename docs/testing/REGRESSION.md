# Manual Regression Checklist — Shelly Coffee Timer

Use this checklist before releases, after script changes, or after major configuration updates. Check each box after verifying.

---

## Device

### Physical button
- [ ] Press while off: plug turns on, timer starts at default duration
- [ ] Press while on: plug turns off immediately, timer clears
- [ ] LED indicates switch state correctly

### MQTT commands (via Adafruit IO)
- [ ] `t90` command with current timestamp: device turns on, remaining=90
- [ ] `ext` command while on: remaining increases by 30
- [ ] `sub` command while on: remaining decreases by 30
- [ ] `off` command while on: device turns off
- [ ] Stale command (timestamp > 2 min old): silently ignored, no state change
- [ ] Command before NTP sync: rejected (if testable after reboot)

### Local HTTP
- [ ] `GET /script/1/coffee_status` returns JSON with all fields (state, remaining, mode, sch, h, m, ntp, ts)
- [ ] `GET /script/1/coffee_command?cmd=t90` turns on, returns ok=true
- [ ] `GET /script/1/coffee_command?cmd=ext` extends timer
- [ ] `GET /script/1/coffee_command?cmd=sub` reduces timer
- [ ] `GET /script/1/coffee_command?cmd=off` turns off
- [ ] `GET /script/1/coffee_command?cmd=bogus` returns 400 with error
- [ ] `GET /script/1/coffee_command` (no cmd) returns 400 with "missing cmd"

### Schedule
- [ ] Arm schedule via config post (sch=1, h, m set to 2 min from now)
- [ ] Schedule fires at the correct time
- [ ] Switch turns on with mode=sch
- [ ] Schedule auto-disarms (sch=0 in heartbeat after firing)
- [ ] Schedule does not fire if sch=0

### Boot safety
- [ ] After power cycle: switch is OFF
- [ ] After power cycle: timer is cleared (remaining=0)
- [ ] After power cycle: config loaded from KVS (schedule settings preserved)
- [ ] After power cycle: MQTT reconnects and publishes heartbeat

### Heartbeat
- [ ] Heartbeat published on state change (on/off)
- [ ] Heartbeat published on command acknowledgment
- [ ] Periodic heartbeat while on (~every 5 min)
- [ ] Periodic heartbeat while off (~every 15 min)
- [ ] Heartbeat contains correct fields: s, r, mode, sch, h, m, ack, ts, ntp

### Config
- [ ] Config with higher version accepted: values applied
- [ ] Config with same or lower version rejected: values unchanged
- [ ] Config changes dur: next t90/on command uses new duration
- [ ] Config changes max: timer capped at new max
- [ ] Config persists across reboot (KVS)

### Relay verification (if laptop charger plugged through Shelly)
- [ ] `cat /sys/class/power_supply/AC1/online` = 1 when switch is on
- [ ] `cat /sys/class/power_supply/AC1/online` = 0 when switch is off

---

## Android App

### Settings persistence
- [ ] Shelly IP saved and persisted across app restart
- [ ] AIO username saved and persisted
- [ ] AIO key saved and persisted

### Local control (same wifi)
- [ ] Status shows "Wi-Fi" or "Local" connection indicator
- [ ] Status refreshes every ~10 seconds
- [ ] Timer buttons work: 90, +30, -30, 0
- [ ] Status updates immediately after local command

### Remote control (cellular / different network)
- [ ] Status shows "Internet" or "Remote" connection indicator
- [ ] Status refreshes from Adafruit IO heartbeat
- [ ] Timer buttons work via Adafruit IO REST
- [ ] Status updates after brief delay (heartbeat propagation)

### Schedule UI
- [ ] Schedule toggle arms/disarms
- [ ] Time picker opens and allows time selection
- [ ] Schedule change posts config to Adafruit IO with incremented version
- [ ] Device heartbeat reflects new schedule settings

### Auto-detect
- [ ] On home wifi: uses local path (faster response)
- [ ] On cellular: falls back to remote path
- [ ] Switching between wifi and cellular: auto-detects correctly

### App icon
- [ ] Custom launcher icon visible (coffee cup with timer)

---

## HTML Page (web/index.html)

### Credentials
- [ ] Prompts for AIO username and key on first load
- [ ] Credentials stored in localStorage
- [ ] Credentials persist across page refresh

### Commands
- [ ] Status displayed and auto-refreshes
- [ ] Timer buttons (0, -30, +30, 90) send commands via Adafruit IO REST
- [ ] Command reflected in status after brief delay

### Schedule
- [ ] Schedule toggle works
- [ ] Schedule time picker works
- [ ] Config posted with incremented version

### Display
- [ ] Auto-refresh every ~10 seconds
- [ ] 24-hour time format used
- [ ] Favicon displayed in browser tab

---

## Cross-Platform

- [ ] Command sent from Android app: visible in HTML page heartbeat
- [ ] Command sent from HTML page: visible in Android app status
- [ ] Schedule changed from Android app: reflected in HTML page
- [ ] Schedule changed from HTML page: reflected in Android app
- [ ] Device state consistent across all interfaces after each operation

---

## Post-Test Cleanup

- [ ] Switch is OFF
- [ ] Schedule is disarmed (sch=0)
- [ ] Config restored to defaults (dur=90, max=180)
- [ ] No active Adafruit IO rate limit bans
