# Shelly Coffee Maker — Functional Requirements

## 1. Core concept

A smart plug controlling a coffee maker. Every "on" state is a countdown timer. There is no "on indefinitely" — when the timer reaches zero, the plug turns off. This is the fundamental safety invariant.

---

## 2. Device control surfaces

There are three control paths to the device, with different capabilities and requirements:

| Control path | Transport | Requires wifi | Requires internet | Requires NTP | Staleness check |
|---|---|---|---|---|---|
| Physical button | Electrical | No | No | No | N/A |
| Local (phone on same wifi) | HTTP direct to device port 80 | Yes | No | No | No — synchronous |
| Remote (phone on cellular / away) | MQTT via Adafruit IO | Yes | Yes | Yes | Yes — 2 min threshold |

Local HTTP and remote MQTT both expose the same controls (manual + schedule). The difference is the transport and its safety properties. Local commands are synchronous and immediate — no intermediary, no delay. Remote commands pass through Adafruit IO and may be delayed, so they require a staleness check.

### 2.1 Physical button (on device)

- **Press while off** → turn on, start countdown at default duration (90 min)
- **Press while on** → turn off immediately (timer cleared)
- No extend via physical button — extend is remote-only

### 2.2 Remote control (phone/computer, local or remote)

#### Manual control

| Control | Behavior |
|---|---|
| **Status** | Read-only. Shows current state: ON (with remaining minutes) or OFF |
| **Timer: 0** | Turn off immediately. No-op if already off |
| **Timer: -30** | Subtract 30 min from remaining time. If remaining ≤ 30, turns off. No-op if off |
| **Timer: +30** | Add 30 min. If off, turns on with 30 min. Capped: remaining may not exceed 180 min |
| **Timer: 90** | Set timer to 90 min. If off, turns on. If on, resets countdown to 90 min |

#### Schedule control

| Control | Behavior |
|---|---|
| **Schedule enabled** | Toggle (on/off). When enabled, the schedule will fire once at the configured time, then auto-disable |
| **Schedule hour** | Hour component of scheduled start time (00–23) |
| **Schedule minute** | Minute component of scheduled start time (00–59) |

---

## 3. Timer behavior

### 3.1 Rules

- Every "on" transition starts or modifies a countdown timer
- When timer reaches 0 → plug turns off
- Default duration: **90 minutes** (configurable via config feed — see doc 03 §3.3). Used by physical button, 90 button, and schedule.
- Minimum timer value: **0** (which means off)
- Maximum timer value at any moment: **180 minutes** (configurable ceiling — see doc 03 §3.3)
- The +30 button is capped: if remaining + 30 > 180, the timer is set to 180 (not rejected)
- The -30 button floors at 0: if remaining - 30 < 0, the plug turns off

### 3.2 Sources that start the timer

| Source | Duration | Notes |
|---|---|---|
| Physical button | Default (90 min) | Uses configured default duration |
| Remote: 90 button | Default (90 min) | Resets timer if already on |
| Remote: +30 button (while off) | 30 min | Turns on with 30 min |
| Schedule fires | Default (90 min) | Same as physical button |

### 3.3 The 180-minute cap

The cap applies to the countdown value at any moment, not to total session length. This means:

- You can keep the coffee maker on indefinitely by periodically pressing +30
- But you must actively choose to do so — you cannot "set and forget" beyond 180 min
- If you walk away, the plug will turn off in at most 180 minutes

Example session:
```
90 button      → timer = 90
(60 min pass)  → timer = 30
+30            → timer = 60
+30            → timer = 90
+30            → timer = 120
+30            → timer = 150
+30            → timer = 180  (cap reached)
+30            → timer = 180  (no change, already at cap)
(30 min pass)  → timer = 150
+30            → timer = 180  (allowed again — current value was under cap)
```

---

## 4. Schedule behavior

### 4.1 One-off schedule

- The schedule is a single future event, not a recurring pattern
- When the schedule fires, it turns the plug on for the default duration (90 min)
- After firing, the schedule auto-disables (enabled → off)
- The user must re-enable the schedule for it to fire again
- This is a deliberate safety choice: automatic recurring activation of a coffee maker is dangerous

### 4.2 Schedule + manual interaction

- If the plug is already on when the schedule fires → reset timer to 90 min (same as pressing the 90 button)
- If the user manually turns off after a schedule-triggered on → plug turns off, schedule remains disabled (already auto-disabled on fire)
- Manual controls always work regardless of schedule state

### 4.3 Schedule configuration

- Hour: 00–23
- Minute: 00–59
- No day-of-week selection (it's a one-off)
- Schedule must be set while the plug has NTP time sync (device enforces this)

---

## 5. Safety requirements

### 5.1 Hard timer ceiling

- No on-state may exceed 180 minutes on the countdown
- This is enforced on-device regardless of what commands arrive
- If a command tries to set timer > 180, clamp to 180

### 5.2 Power-loss recovery

- After power outage, the plug starts in **off** state
- Timer does not resume — a power interruption kills the session
- Schedule persists across reboots (stored in KVS)
- Schedule enabled/disabled state persists across reboots

### 5.3 Connectivity loss

- If the plug loses wifi while on: the timer continues counting down locally and the plug turns off when it reaches zero
- Physical button always works regardless of connectivity
- No connectivity required for safe shutdown
- The plug never turns on due to connectivity restoration alone (no retained command replay)
- If wifi is available but internet is not: physical button and local HTTP control still work. Only remote control (via Adafruit IO) is lost.

### 5.4 NTP dependency

- "NTP synced" means: has successfully obtained the time at least once since boot. After first sync, the ESP32 RTC keeps adequate time (drift is seconds/day — irrelevant for a 2-minute staleness window).
- Schedule-based activation requires NTP synced — if not synced, the schedule does not fire (fail safe)
- Physical button and local HTTP control work without NTP (no staleness concern — synchronous)
- Remote commands via MQTT are rejected until first NTP sync after boot (device cannot verify timestamps)

### 5.5 Remote command staleness

- Every remote command (via Adafruit IO / MQTT) carries a timestamp set by the sender
- The device compares the command timestamp to its own clock
- Commands older than **2 minutes** are silently discarded
- This prevents delayed commands from unexpectedly turning on the coffee maker after network disruptions
- Local HTTP commands are exempt — they are synchronous and cannot be delayed by an intermediary

Example scenario this prevents:
```
14:00  Device on, timer = 10 min remaining
14:02  User sends "+30" remotely (intent: extend current session)
       Internet is flaky — command queued at Adafruit IO
14:10  Timer hits 0, device turns off autonomously
14:11  MQTT delivers the stale "+30"
       Device checks timestamp: 14:02, now is 14:11 → 9 min old → discarded
       Coffee maker stays off (correct)
```

---

## 6. Status and acknowledgment

### 6.1 What the phone needs to see

- Current state: on or off
- If on: remaining minutes on timer
- Current mode: manual, scheduled (timer started by schedule), or off
- Last seen: timestamp of last device heartbeat
- Last ack: the last command the device confirmed processing

### 6.2 What the phone does NOT need

- Real-time power consumption
- Full event history
- Detailed error logs

---

## 7. Autonomous behavior summary

The plug is an autonomous device. It makes all safety decisions locally. Remote control is a convenience layer, not a dependency.

| Behavior | Requires wifi? | Requires internet? | Requires NTP? |
|---|---|---|---|
| Timer countdown and auto-off | No | No | No |
| Physical button on/off | No | No | No |
| Local HTTP control (same wifi) | Yes | No | No |
| Schedule fires at set time | No | No | Prior sync required |
| 180-min hard cap enforcement | No | No | No |
| Power-loss → start off | No | No | No |
| Remote control via Adafruit IO | Yes | Yes | Yes (staleness check) |
| Remote schedule config via Adafruit IO | Yes | Yes | No (config is data, not a timed action) |
| Status reporting (heartbeat) | Yes | Yes | Yes (for timestamps) |

---

## 8. Out of scope (for now)

- Recurring schedules (daily, weekday, etc.)
- Temperature-based logic
- Multiple plugs / multi-device coordination
- Power consumption monitoring and alerts
- Integration with other smart home systems
- Phone UI design (separate concern)
