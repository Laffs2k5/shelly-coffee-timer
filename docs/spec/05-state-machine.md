# Shelly Coffee Maker — On-Device State Machine

## 1. Overview

This document describes the mJS script that runs on the Shelly Plug S Gen3. It ties together everything from docs 01–04: the timer and safety rules, the three control paths, the MQTT and HTTP interfaces, config persistence, and the heartbeat reporting.

The script is a single file, running on a cooperative single-threaded mJS runtime. It reacts to events (button press, MQTT message, HTTP request, timer tick) and makes decisions based on in-memory state backed by KVS persistence.

---

## 2. State model

### 2.1 In-memory state (lost on reboot)

These variables exist only in RAM. On reboot, they reset to their defaults. The timer is the most important — a power outage kills the timer, and the plug starts off. This is a safety feature.

| Variable | Type | Default | Description |
|---|---|---|---|
| `sw_on` | boolean | `false` | Current switch state |
| `remain` | number | `0` | Timer remaining in seconds (internal precision; reported as minutes) |
| `mode` | string | `""` | What started the current on-state: `"manual"`, `"remote"`, `"sch"`, or `""` (off) |
| `last_ack` | string | `""` | Last command code successfully processed |
| `ntp_synced` | boolean | `false` | Whether NTP has synced at least once since boot |

### 2.2 KVS-persisted state (survives reboot)

These are stored in the Shelly's Key-Value Store and loaded on boot. They represent the device's "last known good" configuration.

| KVS key | Type | Default | Description |
|---|---|---|---|
| `cfg_v` | number | `0` | Config version (from the `v` field in config messages) |
| `cfg_sch` | number | `0` | Schedule enabled (1) or disabled (0) |
| `cfg_h` | number | `6` | Schedule hour (0–23) |
| `cfg_m` | number | `0` | Schedule minute (0–59) |
| `cfg_dur` | number | `90` | Default on-duration in minutes |
| `cfg_max` | number | `180` | Hard ceiling for timer in minutes |

### 2.3 Derived constants

| Name | Value | Source |
|---|---|---|
| `AIO_USER` | `"your_username"` | Hardcoded on-device (matches MQTT auth). **Do not commit real value to repo** — use placeholder in committed code, replace when pasting to device. |
| `TOPIC_CMD` | `AIO_USER + "/f/command"` | Adafruit IO command feed |
| `TOPIC_CFG` | `AIO_USER + "/f/config"` | Adafruit IO config feed |
| `TOPIC_HB` | `AIO_USER + "/f/heartbeat"` | Adafruit IO heartbeat feed |
| `TOPIC_CFG_GET` | `TOPIC_CFG + "/get"` | Config fetch-on-connect topic |
| `STALE_SEC` | `120` | 2-minute staleness window (seconds) |
| `TICK_SEC` | `60` | Timer tick interval (seconds) |
| `HB_ON_SEC` | `300` | Heartbeat interval while on (5 min) |
| `HB_OFF_SEC` | `900` | Heartbeat interval while off (15 min) |

---

## 3. Boot sequence

On device power-up or reboot, the script runs through this sequence:

```
1.  Load config from KVS
      KVS.get("cfg_v") → cfg_v (default 0 if missing)
      KVS.get("cfg_sch") → cfg_sch (default 0)
      KVS.get("cfg_h") → cfg_h (default 6)
      KVS.get("cfg_m") → cfg_m (default 0)
      KVS.get("cfg_dur") → cfg_dur (default 90)
      KVS.get("cfg_max") → cfg_max (default 180)

2.  Initialize in-memory state
      sw_on = false
      remain = 0
      mode = ""
      last_ack = ""
      ntp_synced = false

3.  Ensure switch is OFF
      Shelly.call("Switch.Set", {id: 0, on: false})
      (Safety: plug always boots off, regardless of previous state)

4.  Register event handlers
      Physical button → on_button()
      MQTT status change → on_mqtt_connect()

5.  Register MQTT subscriptions
      MQTT.subscribe(TOPIC_CMD, on_mqtt_command)
      MQTT.subscribe(TOPIC_CFG, on_mqtt_config)

6.  Start timer tick (runs every TICK_SEC)
      Timer.set(TICK_SEC * 1000, true, on_tick)

7.  Start heartbeat timer
      Timer.set(HB_OFF_SEC * 1000, true, on_heartbeat_timer)

8.  Start schedule checker (runs every 30 seconds)
      Timer.set(30000, true, on_schedule_check)

9.  Register local HTTP RPC endpoints
      Register Coffee.Command handler
      Register Coffee.Status handler

10. Wait for NTP sync (passive — checked by event handler)
      Shelly.addStatusHandler → on NTP sync, set ntp_synced = true
```

**KVS loading is asynchronous in mJS.** Each `KVS.get()` takes a callback. The boot sequence must chain these or use a counter to track when all values are loaded before proceeding. This is an mJS limitation — no promises, no async/await.

**The switch is forced OFF on boot** regardless of what KVS says or what the switch's hardware state is. This implements the power-loss safety rule (doc 01 §5.2): a power interruption kills the session.

---

## 4. Event handlers

### 4.1 Physical button — `on_button()`

Triggered by the Shelly's `Input` component event when the physical button is pressed.

```
on_button():
  if sw_on:
    turn_off()
  else:
    turn_on(cfg_dur, "manual")
```

No NTP check. No staleness check. No connectivity required. The physical button always works.

### 4.2 MQTT command — `on_mqtt_command(topic, message)`

Triggered when a message arrives on the command feed from Adafruit IO.

```
on_mqtt_command(topic, message):
  msg = JSON.parse(message)
  if msg is null → return (malformed)

  // Staleness check
  if not ntp_synced → return (cannot verify timestamp)
  now = get_unixtime()
  if now - msg.ts > STALE_SEC → return (stale command)
  if msg.ts > now + STALE_SEC → return (future timestamp, clock skew)

  // Execute
  execute_command(msg.c)
  last_ack = msg.c
  publish_heartbeat()
```

### 4.3 MQTT config — `on_mqtt_config(topic, message)`

Triggered when a message arrives on the config feed (either from a new publish or from a `/get` response).

```
on_mqtt_config(topic, message):
  msg = JSON.parse(message)
  if msg is null → return (malformed)

  // Version check
  if msg.v <= cfg_v → return (stale or duplicate config)

  // Accept new config
  cfg_v = msg.v
  cfg_sch = msg.sch
  cfg_h = msg.h
  cfg_m = msg.m
  cfg_dur = msg.dur
  cfg_max = msg.max

  // Persist to KVS
  KVS.set("cfg_v", cfg_v)
  KVS.set("cfg_sch", cfg_sch)
  KVS.set("cfg_h", cfg_h)
  KVS.set("cfg_m", cfg_m)
  KVS.set("cfg_dur", cfg_dur)
  KVS.set("cfg_max", cfg_max)

  // Enforce max on current timer if it was lowered
  if sw_on and remain > cfg_max * 60:
    remain = cfg_max * 60

  publish_heartbeat()
```

No staleness check on config. Config is "desired state" — it's always valid regardless of when it was written. The version number handles ordering.

### 4.4 MQTT connect — `on_mqtt_connect()`

Triggered when the firmware establishes (or re-establishes) the MQTT connection. Detected via `Shelly.addStatusHandler` watching the `mqtt` component's `connected` status.

```
on_mqtt_connect():
  // Request latest config from Adafruit IO
  MQTT.publish(TOPIC_CFG_GET, "", 0, false)

  // Publish current state so the phone has a fresh snapshot
  publish_heartbeat()
```

Subscriptions are registered once at boot (step 5 in boot sequence). The Shelly firmware maintains them across reconnections — no need to re-subscribe on connect.

### 4.5 Timer tick — `on_tick()`

Runs every `TICK_SEC` seconds (60s). This is the countdown engine.

```
on_tick():
  if not sw_on → return

  remain = remain - TICK_SEC
  if remain < 0:
    remain = 0

  if remain <= 0:
    turn_off()
    publish_heartbeat()
```

The tick runs unconditionally (even while off) for simplicity. The `if not sw_on` guard makes it a no-op when off, avoiding the complexity of creating/destroying timers on state changes.

**Precision:** The timer counts in seconds internally but reports minutes to the phone. This gives smooth countdown behavior without over-reporting. The `remain` value is floored to whole minutes for heartbeat reporting: `Math.floor(remain / 60)`.

### 4.6 Heartbeat timer — `on_heartbeat_timer()`

Runs periodically. The interval changes based on switch state.

```
on_heartbeat_timer():
  publish_heartbeat()
```

The interval is `HB_ON_SEC` (5 min) while on and `HB_OFF_SEC` (15 min) while off. Since mJS `Timer.set` doesn't support changing intervals on a repeating timer easily, there are two approaches:

**Option A (simple):** Use a single timer at the shorter interval (60 seconds) and track elapsed time in a counter. Publish when the counter exceeds the appropriate threshold.

**Option B (cleaner):** Use a non-repeating timer. Each time it fires, publish the heartbeat and set a new one-shot timer with the appropriate interval based on current state.

Option B is recommended — it avoids waking up every 60 seconds while off (saves power in eco mode) and keeps the logic explicit.

### 4.7 Schedule check — `on_schedule_check()`

Runs every 30 seconds. Checks if the scheduled time has arrived.

```
on_schedule_check():
  if cfg_sch !== 1 → return (schedule not armed)
  if not ntp_synced → return (no clock, fail safe)

  now = get_localtime()   // needs timezone-aware time
  if now.hour === cfg_h and now.minute === cfg_m:
    // Disarm schedule (before turning on, to prevent re-fire)
    cfg_sch = 0
    KVS.set("cfg_sch", 0)

    // Turn on
    turn_on(cfg_dur, "sch")
    publish_heartbeat()
```

**30-second check interval** ensures we don't miss the minute window. Since we only check hour:minute (not seconds), and the check runs twice per minute, we'll always catch the target minute.

**Preventing double-fire:** The schedule is disarmed immediately when it fires. Even if the check runs again within the same minute, `cfg_sch` will be 0 and the check short-circuits.

**Timezone:** The Shelly's `Sys.GetStatus` provides local time based on the configured timezone. The schedule uses local time — the user sets "06:10" meaning 06:10 in their timezone.

---

## 5. Core functions

### 5.1 `turn_on(duration_min, new_mode)`

Activates the switch and sets the countdown timer.

```
turn_on(duration_min, new_mode):
  // Enforce ceiling
  if duration_min > cfg_max:
    duration_min = cfg_max

  remain = duration_min * 60   // convert to seconds
  mode = new_mode
  sw_on = true

  Shelly.call("Switch.Set", {id: 0, on: true})
```

### 5.2 `turn_off()`

Deactivates the switch and clears the timer.

```
turn_off():
  remain = 0
  mode = ""
  sw_on = false

  Shelly.call("Switch.Set", {id: 0, on: false})
```

### 5.3 `execute_command(cmd)`

Processes a command code. Used by both MQTT and local HTTP paths.

```
execute_command(cmd):
  if cmd === "on" or cmd === "t90":
    turn_on(cfg_dur, "remote")

  else if cmd === "off":
    if sw_on:
      turn_off()

  else if cmd === "ext":
    if sw_on:
      new_remain = remain + 30 * 60
      max_remain = cfg_max * 60
      remain = (new_remain > max_remain) ? max_remain : new_remain
    else:
      turn_on(30, "remote")

  else if cmd === "sub":
    if sw_on:
      remain = remain - 30 * 60
      if remain <= 0:
        turn_off()
```

**Note on `on` vs `t90`:** Both call `turn_on(cfg_dur, "remote")`. They are functionally identical as stated in doc 03 decision #25. The script treats them the same.

**Note on `ext` while off:** Turns on with 30 minutes, not `cfg_dur`. This matches doc 01 §3.2 — the +30 button while off starts a 30-minute session.

### 5.4 `publish_heartbeat()`

Constructs and publishes the heartbeat JSON to Adafruit IO.

```
publish_heartbeat():
  if not ntp_synced → return (heartbeat needs a timestamp)

  hb = JSON.stringify({
    s: sw_on ? "on" : "off",
    r: Math.floor(remain / 60),
    mode: mode,
    sch: cfg_sch,
    h: cfg_h,
    m: cfg_m,
    ack: last_ack,
    ts: get_unixtime(),
    ntp: true
  })

  MQTT.publish(TOPIC_HB, hb, 1, false)
```

QoS 1 for heartbeats — we want at-least-once delivery since this is the phone's primary view of device state.

The `false` in the publish call is the retain flag. Even though it has no effect on Adafruit IO (they don't support retain), we pass `false` to be explicit.

### 5.5 `get_unixtime()`

Returns the current Unix epoch time in seconds.

```
get_unixtime():
  return Shelly.getComponentStatus("sys").unixtime
```

`Shelly.getComponentStatus("sys")` returns an object that includes `unixtime` (seconds since epoch) when NTP is synced. Before NTP sync, this value may be 0 or unreliable — which is why `ntp_synced` gates all time-dependent operations.

### 5.6 `get_localtime()`

Returns the current local time as hour and minute, respecting the device's configured timezone.

```
get_localtime():
  let status = Shelly.getComponentStatus("sys")
  // The Shelly provides local_time as "HH:MM" string when timezone is configured
  // Alternatively, use unixtime + timezone offset
  // Exact API depends on firmware version — validate during testing
```

**Open question:** The exact API for getting timezone-aware local hour/minute in mJS needs to be confirmed during development. `Sys.GetStatus` includes a `time` field in some firmware versions. Worst case, we can compute it from `unixtime` + a timezone offset stored in config, but the firmware should handle this natively since the device has a configured timezone via `Sys.SetConfig`.

---

## 6. Local HTTP RPC endpoints

The mJS script registers custom RPC handlers that the phone (on the same wifi) calls directly via HTTP GET.

### 6.1 `Coffee.Command`

**Request:** `GET /rpc/Coffee.Command?cmd=t90`

**Handler:**

```
Shelly.addRPCHandler("Coffee.Command", function(params) {
  let cmd = params.cmd
  if not cmd → return {ok: false, error: "missing cmd"}

  let valid = ["on", "off", "ext", "sub", "t90"]
  if valid.indexOf(cmd) < 0 → return {ok: false, error: "unknown command"}

  execute_command(cmd)
  last_ack = cmd
  publish_heartbeat()

  return {
    ok: true,
    state: sw_on ? "on" : "off",
    remaining: Math.floor(remain / 60),
    ack: cmd
  }
})
```

**No staleness check.** Local HTTP is synchronous — there is no intermediary that could delay the command. No NTP dependency for command processing.

**Response:**

```json
{"ok":true,"state":"on","remaining":90,"ack":"t90"}
```

### 6.2 `Coffee.Status`

**Request:** `GET /rpc/Coffee.Status`

**Handler:**

```
Shelly.addRPCHandler("Coffee.Status", function(params) {
  return {
    state: sw_on ? "on" : "off",
    remaining: Math.floor(remain / 60),
    mode: mode,
    sch: cfg_sch,
    h: cfg_h,
    m: cfg_m,
    ntp: ntp_synced,
    ts: ntp_synced ? get_unixtime() : 0
  }
})
```

Same fields as the heartbeat, delivered synchronously. This is how the phone gets status when on the same wifi without going through Adafruit IO.

---

## 7. NTP sync detection

The Shelly firmware syncs NTP automatically when internet is available. The script detects this via the status handler:

```
Shelly.addStatusHandler(function(event) {
  // Detect NTP sync
  if event.component === "sys" and event.delta.unixtime is defined:
    if event.delta.unixtime > 1700000000:   // sanity check: after ~Nov 2023
      ntp_synced = true

  // Detect MQTT connect
  if event.component === "mqtt" and event.delta.connected === true:
    on_mqtt_connect()
})
```

The `unixtime > 1700000000` check prevents false positives from the RTC reporting a near-zero value before actual NTP sync.

**Once set, `ntp_synced` stays true for the rest of the session.** Per doc 01 §5.4, a single successful sync is sufficient — the ESP32 RTC drifts by seconds per day, which is irrelevant for a 2-minute staleness window.

---

## 8. Timer precision and the tick model

### 8.1 Why 60-second ticks

The timer counts down in 60-second intervals. This means:

- Worst-case latency from "timer hits 0" to "switch turns off" is 60 seconds
- For a coffee maker safety timer, 60-second granularity is more than sufficient
- Saves CPU/memory vs a 1-second tick (mJS is cooperative, and each timer wake-up has overhead)

### 8.2 Internal seconds, reported minutes

The timer internally tracks `remain` in seconds for two reasons:

1. Subtraction is cleaner: `remain -= 60` per tick, `remain -= 30 * 60` for `sub` command
2. Future flexibility: if we ever want finer-grained reporting, the internal state supports it

The heartbeat reports `Math.floor(remain / 60)` — always whole minutes, rounding down. This means a heartbeat might show "89 min" one second after a 90-minute timer starts, which is fine. The phone displays "89 min remaining" and the user understands this is approximate.

### 8.3 Turn-off happens at the tick boundary

When `remain` goes to 0 or below, the turn-off happens at the next tick. This means the coffee maker might run up to 59 seconds past the nominal timer value. For a 90-minute or 180-minute timer, this is negligible.

---

## 9. Heartbeat publishing strategy

### 9.1 Event-triggered heartbeats

These publish immediately when something happens:

| Trigger | Why |
|---|---|
| State change (on↔off) | Phone needs to know ASAP |
| Command processed (MQTT or local) | Updates `ack` field |
| Config received | Updates schedule fields |
| Schedule fires | Updates `sch` and state |
| MQTT connect/reconnect | Fresh snapshot after connectivity gap |

### 9.2 Periodic heartbeats

| State | Interval | Purpose |
|---|---|---|
| On | Every 5 min | Keeps `r` (remaining) reasonably current |
| Off | Every 15 min | "I'm alive" — updates `ts` for last-seen |

### 9.3 Deduplication

Multiple triggers can fire close together (e.g., command processed + state change). To avoid publishing 3 heartbeats in one second, use a simple debounce: after publishing, set a `hb_cooldown` flag and clear it after 2 seconds. If `publish_heartbeat()` is called during cooldown, skip it (the previous heartbeat already has the latest state).

Exception: the MQTT-connect heartbeat should always publish (it might be the first message after a long offline period).

---

## 10. Config processing details

### 10.1 Config arrives via MQTT

When the phone writes a new config to Adafruit IO, the device receives it on the config topic. The handler:

1. Parses JSON
2. Compares `v` to stored `cfg_v`
3. If newer: accepts, persists all fields to KVS
4. If `cfg_max` was lowered and the current timer exceeds it: clamps `remain`

### 10.2 Config arrives via `/get` on connect

Same handler, same logic. The `/get` response delivers the most recent config value from Adafruit IO's database, which is indistinguishable from a live publish.

### 10.3 Config on fresh boot (no MQTT)

If the device boots without internet (wifi only, or no wifi), it loads config from KVS. This is the "last known good" configuration. The schedule and safety limits work offline using these cached values.

### 10.4 What the device never does

- The device never writes to the config feed. Config is phone-owned (doc 02 decision #10).
- The device never increments `cfg_v`. Only the phone manages the version counter.
- The device modifies `cfg_sch` locally (sets it to 0 when the schedule fires) but only in KVS, not on the config feed. The heartbeat reports the local state.

---

## 11. mJS implementation considerations

### 11.1 KVS loading is asynchronous

Every `KVS.get(key, callback)` is async. The boot sequence must handle this. Pattern:

```javascript
let boot_count = 0;
let BOOT_TOTAL = 6;  // number of KVS keys to load

function on_kvs_loaded(key, value) {
  if (key === "cfg_v") cfg_v = value || 0;
  if (key === "cfg_sch") cfg_sch = value || 0;
  // ... etc
  boot_count++;
  if (boot_count === BOOT_TOTAL) {
    boot_complete();
  }
}

KVS.get("cfg_v", function(v) { on_kvs_loaded("cfg_v", v); });
KVS.get("cfg_sch", function(v) { on_kvs_loaded("cfg_sch", v); });
// ... etc
```

`boot_complete()` then registers event handlers, starts timers, and ensures the switch is off.

### 11.2 JSON.parse safety

mJS `JSON.parse()` returns `undefined` (not `null`) on failure in some mJS versions. Always check:

```javascript
let msg = JSON.parse(payload);
if (typeof msg !== "object" || msg === null) return;
```

### 11.3 String comparison in mJS

mJS does not have `Array.indexOf()` in all builds. For command validation, use chained `if/else if` rather than array lookup:

```javascript
if (cmd === "on" || cmd === "t90" || cmd === "off" || cmd === "ext" || cmd === "sub") {
  // valid
}
```

### 11.4 Memory budget

The script's global variables, function closures, and parsed JSON objects all consume RAM from the ~200 KB available. Keeping payloads small (short keys, flat JSON) is why doc 03 made those encoding decisions.

**Avoid holding multiple parsed JSON objects simultaneously.** Parse the incoming message, extract what you need into local variables, and let the parsed object go out of scope.

### 11.5 Timer.set behavior

- `Timer.set(ms, repeat, callback)` — `repeat=true` for periodic, `false` for one-shot
- Timers survive across iterations of the event loop but not across script restarts
- The callback receives no arguments
- Creating many timers consumes resources — prefer a single tick timer over per-feature timers where practical

### 11.6 MQTT.publish when disconnected

If the MQTT connection is down, `MQTT.publish()` silently fails (returns false). The script should not crash or accumulate unsent messages. Heartbeats are best-effort — if MQTT is down, the phone won't see updates, and the device continues operating autonomously.

### 11.7 Shelly.call callback pattern

`Shelly.call("Switch.Set", {id: 0, on: true}, callback)` — the callback is optional. For safety-critical calls (turning the switch on/off), consider adding a callback to verify the switch actually changed state. However, in practice, the Shelly's internal switch control is highly reliable and adding callbacks increases complexity.

---

## 12. State transition diagram

```
                    ┌──────────┐
                    │          │
      ───boot──────►│   OFF    │◄──────────────────────────┐
                    │ remain=0 │                            │
                    │ mode=""  │                            │
                    └────┬─────┘                            │
                         │                                  │
          ┌──────────────┼──────────────┐                   │
          │              │              │                    │
     button press    cmd: on/t90    schedule fires           │
     (any time)      cmd: ext       (if armed +             │
                     (turns on      NTP synced)             │
                      with 30)                              │
          │              │              │                    │
          ▼              ▼              ▼                    │
     ┌─────────────────────────────────────┐                │
     │              ON                     │                │
     │  remain = countdown (seconds)       │                │
     │  mode = manual | remote | sch       │                │
     │                                     │                │
     │  Events while on:                   │                │
     │    tick: remain -= 60               │                │
     │    cmd ext: remain += 1800 (cap)    │                │
     │    cmd sub: remain -= 1800          │                │
     │    cmd on/t90: remain = dur*60      │                │
     │    button press: → OFF              ├────────────────┘
     │    cmd off: → OFF                   │  remain ≤ 0
     │    remain ≤ 0: → OFF               │  or cmd off
     └─────────────────────────────────────┘  or button
```

---

## 13. Complete event flow examples

### 13.1 Morning schedule with extend

```
05:55  Boot complete, config loaded from KVS (sch=1, h=6, m=10)
06:00  NTP syncs. ntp_synced = true
06:10  schedule_check: hour=6, min=10, sch=1 → fires
         cfg_sch = 0, KVS.set("cfg_sch", 0)
         turn_on(90, "sch") → remain=5400, sw_on=true, mode="sch"
         heartbeat published: s=on, r=90, mode=sch, sch=0
06:15  heartbeat timer → publish (r=85)
...
07:10  heartbeat timer → publish (r=30)
07:15  MQTT command arrives: {"c":"ext","ts":1711036500}
         staleness: now - ts = 3 sec → OK
         remain = 1800 + 1800 = 3600 (60 min)
         ack = "ext"
         heartbeat published: s=on, r=60, ack=ext
07:40  tick → remain = 60*60 - 25*60 = 2100 (35 min)
...
08:15  tick → remain ≤ 0 → turn_off()
         heartbeat published: s=off, r=0
```

### 13.2 Stale command rejected

```
14:00  Device on, remain = 600 (10 min)
14:02  Phone sends {"c":"ext","ts":1711036920} (intent: extend)
       Internet flaky — message queued at Adafruit IO
14:10  tick → remain ≤ 0 → turn_off()
         heartbeat: s=off
14:11  MQTT delivers the ext command (ts=1711036920)
         now = 1711037460, delta = 540 sec > 120 → STALE, discarded
         Coffee maker stays off ✓
```

### 13.3 Local control while MQTT is down

```
Internet goes down. MQTT disconnected.
Device is off, config in KVS, schedule armed.

User on same wifi:
  GET http://192.168.1.xxx/rpc/Coffee.Command?cmd=t90
  → 200 {"ok":true,"state":"on","remaining":90,"ack":"t90"}
  Device turns on, timer counting down locally.

  GET http://192.168.1.xxx/rpc/Coffee.Status
  → 200 {"state":"on","remaining":85,"mode":"remote","sch":1,"h":6,"m":10,"ntp":false,"ts":0}

  Note: ntp=false because internet is down and NTP hasn't synced.
  Timer still counts down. Physical button still works.
  No heartbeat published (MQTT down).

Internet returns:
  MQTT reconnects → on_mqtt_connect()
  → publishes config/get, receives config, publishes heartbeat
  Phone can now see the device state again.
```

---

## 14. Error handling

| Error | Handling |
|---|---|
| Malformed MQTT command JSON | `JSON.parse` returns non-object → silently ignored |
| Malformed MQTT config JSON | Same — silently ignored, KVS config unchanged |
| Unknown command code | Ignored (no `execute_command` match). No ack published. |
| MQTT publish fails (disconnected) | `MQTT.publish` returns false. No retry. Heartbeat lost. Device continues. |
| KVS.get fails on boot | Use default value. Device operates with defaults until config arrives via MQTT. |
| KVS.set fails | Config not persisted. Survives until reboot, then reverts to previously-persisted value. Not critical for single session. |
| Switch.Set fails | Extremely unlikely (firmware-internal). Could add callback to detect and retry. Defer for now. |
| Script crash/exception | Shelly firmware restarts the script automatically if "run on startup" is enabled. Device reboots into off state (safe). |

---

## 15. Decisions made

| # | Decision | Rationale |
|---|---|---|
| 36 | Timer counts in seconds internally, reports minutes externally | Cleaner arithmetic; future-proof for finer reporting if needed |
| 37 | Single 60-second tick timer for countdown | Sufficient precision for a coffee maker; minimizes timer overhead on ESP32 |
| 38 | One-shot heartbeat timer (re-armed with appropriate interval) | Avoids 60-second wake-ups while off; adapts interval to on/off state |
| 39 | Schedule checker runs every 30 seconds | Ensures we never miss the target minute; negligible CPU cost |
| 40 | Heartbeat debounce: 2-second cooldown after publishing | Prevents burst of heartbeats when multiple events fire simultaneously |
| 41 | Boot always forces switch OFF | Power-loss safety (doc 01 §5.2); no timer state survives reboot |
| 42 | KVS loading uses counter-based callback pattern | mJS has no promises; counter tracks when all async loads complete |
| 43 | Local HTTP commands have no staleness check and no NTP dependency | Synchronous path — no intermediary, no delay possible (doc 01 §5.4, doc 02 decision #17) |
| 44 | Config version comparison is strictly greater-than | Prevents replaying old config; phone must always increment `v` |
| 45 | `ntp_synced` is one-way (false → true, never back to false) | Per doc 01 §5.4: single sync is sufficient for session lifetime |
| 46 | Script uses short `/f/` topic form for all Adafruit IO topics | Saves bytes on every MQTT operation; meaningful on constrained device (doc 04 decision #33) |

---

## 16. Open items for implementation

- [ ] Confirm `Shelly.getComponentStatus("sys").unixtime` availability and behavior before/after NTP sync
- [ ] Confirm how to get timezone-aware local hour/minute from mJS (for schedule checker)
- [ ] Test `MQTT.subscribe()` persistence across reconnects (does firmware re-subscribe automatically?)
- [ ] Test `Shelly.addRPCHandler()` — confirm it works for custom method names like `Coffee.Command`
- [ ] Measure RAM usage after script initialization — verify headroom for JSON parsing
- [ ] Test KVS.get behavior when key doesn't exist (returns `undefined`? `null`? calls error callback?)
- [ ] Determine if `Shelly.addStatusHandler` fires for NTP sync events specifically, or if we need to poll
- [ ] Test script auto-restart behavior when "run on startup" is enabled — does it restart on crash?
- [ ] Write the actual mJS script (doc 05 is the design; implementation is a separate step)
