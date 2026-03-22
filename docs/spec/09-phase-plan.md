# Shelly Coffee Maker — Phase Plan

## Overview

Five phases, each producing something independently testable. Each phase has a gate — a set of conditions that must be met before moving to the next. No phase requires the next one to be useful.

---

## Phase 1: Prove the unknowns — DONE

**Goal:** Confirm the two high-risk assumptions from doc 08 before writing any production code.

**Duration:** An afternoon to a day.

**Prerequisites:** Shelly Plug S Gen3 powered on, connected to wifi, accessible via browser.

### Tasks

| # | Task | How | Success criteria |
|---|---|---|---|
| 1.1 | ~~Test `Shelly.addRPCHandler()`~~ | ~~Upload a minimal test script via web UI~~ | DONE — does not exist. Use `HTTPServer.registerEndpoint()`. Tested on firmware 1.7.5. |
| 1.2 | ~~Test timezone-aware local time~~ | ~~Configure timezone via `Sys.SetConfig`~~ | DONE — `new Date().getHours()/getMinutes()` returns local time, DST-aware. Timezone set to `Europe/Oslo`. |
| 1.3 | ~~Decide on alternatives~~ | ~~Evaluate alternatives if needed~~ | DONE — `HTTPServer.registerEndpoint()` adopted. URLs: `/script/1/coffee_command`, `/script/1/coffee_status`. Docs 05, 06, 08 updated. |

### Gate: Phase 1 → Phase 2 — PASSED

- ~~Custom RPC handlers work, OR an alternative local HTTP mechanism is identified~~ — DONE: `HTTPServer.registerEndpoint()` adopted
- ~~Local time is available from the firmware, OR a UTC offset workaround is designed~~ — DONE: `new Date()` returns local time, DST-aware
- ~~Doc 08 items 2.1 and 2.2 updated with findings~~ — DONE
- ~~If architecture changes are needed, affected docs (05, 06) updated before proceeding~~ — DONE

---

## Phase 2: Device side — DONE

**Goal:** A working Shelly with the full mJS script, controllable via physical button, local HTTP (curl), and remote MQTT, reporting status via heartbeat.

**Duration:** A few days to a week, depending on mJS debugging.

**Prerequisites:** Phase 1 gate passed. Adafruit IO account ready.

### Stage 2A: Adafruit IO setup and validation

| # | Task | How | Success criteria |
|---|---|---|---|
| 2A.1 | Create Adafruit IO account and feeds | Web UI or REST API (doc 04 §1.3) | 3 feeds exist: command, config, heartbeat |
| 2A.2 | Test REST round-trip | Doc 04 §6.2 — write and read a test value via curl | Value matches |
| 2A.3 | Test MQTT from computer | Doc 04 §6.3 — mosquitto_sub + mosquitto_pub | Message received |
| 2A.4 | Test `/get` topic | Doc 04 §6.4 — publish config via REST, retrieve via MQTT `/get` | Config delivered to subscriber |
| 2A.5 | Connect Shelly to Adafruit IO | Doc 04 §6.5 — `Mqtt.SetConfig`, reboot, check status | `Mqtt.GetStatus` shows `connected: true` |
| 2A.6 | Test empty feed `/get` | Publish to `config/get` before any config has been written | Script doesn't crash; falls back to defaults |
| 2A.7 | Seed initial config | POST v=1 config to the config feed via curl | Config readable via REST and MQTT `/get` |

### Stage 2B: mJS script — incremental build

Build the script from doc 05, adding one capability at a time. Test each before adding the next.

| # | Capability | Test method | Success criteria |
|---|---|---|---|
| 2B.1 | Boot sequence: KVS load, switch OFF | Reboot device, check switch state | Switch is off, script console shows loaded defaults |
| 2B.2 | Physical button: on/off with timer | Press button, wait for countdown | Button turns on, timer counts down, auto-off works |
| 2B.3 | MQTT subscribe + command handler | Send command via curl to Adafruit IO REST | Script console shows received command, switch responds |
| 2B.4 | Staleness check | Send command with old timestamp | Command rejected (console log), switch unchanged |
| 2B.5 | Heartbeat publishing | Check Adafruit IO heartbeat feed via REST after state change | Heartbeat JSON appears with correct fields |
| 2B.6 | Config handler + `/get` on connect | Post config via REST, reboot device | Device loads config from `/get`, console shows new values |
| 2B.7 | KVS persistence | Change config, reboot without internet | Device uses cached config from KVS |
| 2B.8 | Schedule checker | Set schedule for 2 minutes from now, wait | Schedule fires, switch turns on, schedule auto-disarms |
| 2B.9 | Local HTTP: coffee_status | `curl http://<ip>/script/1/coffee_status` | JSON response with current state |
| 2B.10 | Local HTTP: coffee_command | `curl http://<ip>/script/1/coffee_command?cmd=t90` | Switch turns on, JSON response confirms |
| 2B.11 | NTP guard | Reboot, send MQTT command before NTP syncs | Command rejected until NTP syncs |
| 2B.12 | Heartbeat debounce | Rapidly send 3 commands | Only 1-2 heartbeats published, not 3 |
| 2B.13 | Full integration test | Morning schedule scenario from doc 05 §13.1 | Schedule fires, extend works, timer expires, all heartbeats correct |

### Gate: Phase 2 → Phase 3 — PASSED

- ~~All 2B tests pass~~ — DONE
- ~~Device controllable via curl (local) and via Adafruit IO REST (remote)~~ — DONE
- ~~Heartbeat correctly reflects device state~~ — DONE
- ~~Script runs stable for 24+ hours without crashes~~ — DONE
- ~~mJS script committed to git repo~~ — DONE
- Implementation lessons documented in doc 08 §4

---

## Phase 3: Phone side — DONE

**Goal:** An Android app matching the mockup, with auto-detect local/remote, 10-second polling, and schedule configuration. Plus the HTML fallback page.

**Duration:** One to two weeks (longer if learning Kotlin/Compose from scratch).

**Prerequisites:** Phase 2 gate passed. Device is stable and testable.

### Stage 3A: Android app — incremental build

| # | Capability | Test method | Success criteria |
|---|---|---|---|
| 3A.1 | Project setup | Create Android Studio project, build and run on phone | Empty app launches |
| 3A.2 | Settings screen | Enter Shelly IP, AIO username, AIO key | Values persist across app restart (SharedPreferences) |
| 3A.3 | Local status polling | Hardcode local path, poll every 10s | Status displays on screen, updates live |
| 3A.4 | Remote status polling | Hardcode remote path, poll from Adafruit IO | Status displays, matches device state |
| 3A.5 | Auto-detect local/remote | Combine 3A.3 and 3A.4 with 2s timeout | Shows "Local" on home wifi, "Remote" on cellular |
| 3A.6 | Timer buttons (local) | Tap buttons while on home wifi | Commands execute, status updates immediately |
| 3A.7 | Timer buttons (remote) | Tap buttons while on cellular | Commands execute, status updates after brief delay |
| 3A.8 | Schedule toggle | Toggle schedule on/off | Config posted to Adafruit IO, device heartbeat reflects change |
| 3A.9 | Schedule time picker | Tap time, use native TimePickerDialog | Config posted with correct h and m values |
| 3A.10 | Connection status bar | Switch between wifi and cellular | UI reflects which path is active |
| 3A.11 | UI polish | Match mockup dark theme, colors, layout | Looks like the mockup |
| 3A.12 | Multi-phone test | Sideload APK on second phone, configure | Both phones can control the device |

### Stage 3B: HTML fallback page

| # | Capability | Test method | Success criteria |
|---|---|---|---|
| 3B.1 | Basic page with status display | Open in browser, check Adafruit IO heartbeat | Shows current state |
| 3B.2 | Timer buttons | Click buttons | Commands sent, status updates |
| 3B.3 | Schedule controls | Toggle and set time | Config posted correctly |
| 3B.4 | Auto-refresh | Leave open, change state from phone | Page updates within 10 seconds |
| 3B.5 | Host on GitHub Pages | Push to repo, access via URL | Works from phone browser and laptop |

### Gate: Phase 3 → Done — PASSED

- ~~Android app passes all 3A tests~~ — DONE
- ~~HTML fallback passes all 3B tests~~ — DONE
- ~~Full end-to-end: schedule set from app → schedule fires on device → app shows status → extend from app → timer expires → app shows off~~ — DONE
- ~~App APK and HTML page committed to git repo~~ — DONE
- ~~Doc 07 deployment steps verified against actual process~~ — DONE

---

## Phase 4: UI Polish — DONE (2026-03-22)

**Goal:** Improve the user experience of both the Android app and web page.

### Completed

- ~~Dark theme refinements~~ — Material 3 dark color scheme, green/gray palette (no blue)
- ~~Better layout and spacing~~ — Card-based status, outlined rounded buttons, subtle connection footer
- ~~Visual improvements~~ — Consistent styling across app and HTML, hover/active states, transitions
- ~~Consistent look and feel~~ — Same color palette, button styles, and layout patterns on both platforms
- App launcher icon (coffee cup with timer)
- Favicon for GitHub Pages
- 24-hour time format on HTML
- Removed confusing "Mode" label, renamed connection to Wi-Fi/Internet

### Deferred

- Loading spinner during connection/mode switch — future improvement
- PWA support — not planned

### 4B: Coffee ON notification — IN PROGRESS

**Goal:** Show a persistent, auto-updating notification while the coffee maker is on. Notification shows real remaining time, refreshed from the device, and disappears when coffee finishes.

#### Architecture

**Foreground service (only while coffee is ON):**

1. App polls, detects `state=on` → starts `CoffeeNotificationService` (foreground service)
2. Service polls the device every ~30s via local HTTP or Adafruit IO REST (same auto-detect logic as the app)
3. Between polls, service counts down locally (1 min/min) for smooth display
4. Notification text: "Coffee ON — 74 min remaining" (updated every poll)
5. If service can't reach device for 5+ minutes → notification shows "Connection lost"
6. When poll returns `state=off` → cancel notification, stop service (no more battery drain)

**Schedule-aware wake-up:**

1. Every time the app or service polls successfully: if `sch=1`, save `h` and `m` to SharedPreferences and schedule an `AlarmManager` wake-up for that time
2. This catches schedules set from any client (other phone, HTML page) — the alarm is re-set on every successful poll
3. `AlarmManager` fires at schedule time → starts the foreground service → service polls → detects ON → notification appears
4. When app or service detects `sch=0` → cancel any pending alarm
5. If alarm fires but poll shows device is still OFF (schedule didn't fire yet, or was disarmed) → retry a few times over 5 minutes, then give up and stop

**Key property:** The service only exists while coffee is actively on. No background drain during the 99% of the time when the plug is off.

#### Permissions needed

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

#### Components

| Component | Purpose |
|---|---|
| `CoffeeNotificationService` | Foreground service that polls and updates notification |
| `ScheduleAlarmReceiver` | BroadcastReceiver that starts the service when AlarmManager fires |
| `NotificationHelper` | Creates channel, builds/updates/cancels notifications |
| SharedPreferences keys | `schedule_h`, `schedule_m`, `schedule_enabled` — persisted from last poll |

#### Rate limit impact

- Service polls every 30s = 2 req/min (within 30/min budget)
- App foreground polls 6/min + service 2/min = 8/min max (still fine)
- Service stops when OFF → no ongoing rate usage

#### Edge cases

- App killed while coffee is ON → service survives independently (foreground services are protected from OOM kills)
- Phone rebooted while coffee is ON → service doesn't restart (coffee timer on device also reset by power loss — both are in consistent state)
- Schedule set from HTML/other phone → caught on next app open (alarm re-scheduled from poll data)
- AlarmManager fires but coffee didn't start yet → service retries for 5 min, then stops

---

## Phase 5: Testing & Quality — DONE (2026-03-22)

**Goal:** Establish formal testing practices and documentation cleanup.

**Prerequisites:** Phase 3 complete.

### 5A: Automated device test scripts

Bash/curl scripts in `scripts/` that exercise the device API programmatically. Two categories:

| # | Task | Description | Needs hardware? |
|---|---|---|---|
| 5A.1 | `test-local-api.sh` | Test all local HTTP endpoints (coffee_status, coffee_command with all cmds, error cases) | Yes (Shelly on wifi) |
| 5A.2 | `test-remote-api.sh` | Test Adafruit IO REST endpoints (read/write feeds, command→heartbeat flow) | Yes (Shelly + AIO) |
| 5A.3 | `test-staleness.sh` | Send commands with stale timestamps, verify rejection | Yes |
| 5A.4 | `test-config.sh` | Post config updates, verify version gating and KVS persistence across reboot | Yes |
| 5A.5 | `test-schedule.sh` | Set schedule for 2 min ahead, wait, verify fire + auto-disarm | Yes |
| 5A.6 | `test-all.sh` | Runner that executes 5A.1–5A.5 in sequence with pass/fail summary | Yes |

### 5B: AI-assisted test instructions

A test instruction file (`docs/testing/AI-TEST-GUIDE.md`) that an AI agent can follow autonomously to verify system functionality. Includes:

| # | Task | Description |
|---|---|---|
| 5B.1 | Device regression prompt | Step-by-step instructions an agent executes against live hardware (all Phase 2B tests) |
| 5B.2 | Remote-only test prompt | Tests that only need Adafruit IO REST (no local wifi required) |
| 5B.3 | Relay verification guide | How to use AC1/online sysfs to verify physical relay state |

### 5C: Android app tests

| # | Task | Description |
|---|---|---|
| 5C.1 | API unit tests | Test AdafruitApi parsing/serialization with mock HTTP responses |
| 5C.2 | Config version logic tests | Verify read-increment-write, version gating |
| 5C.3 | Auto-detect logic tests | Verify local-first with fallback, mode caching |

### 5D: Manual regression checklist

A human-readable checklist (`docs/testing/REGRESSION.md`) covering:

| # | Task | Description |
|---|---|---|
| 5D.1 | Device checklist | Physical button, MQTT commands, local HTTP, schedule, boot safety |
| 5D.2 | Android app checklist | Settings persistence, local/remote control, schedule UI, auto-detect |
| 5D.3 | HTML page checklist | Credentials prompt, commands, schedule, auto-refresh, 24h format |
| 5D.4 | Cross-platform checklist | Command from app → verify in HTML, and vice versa |

### 5E: Documentation cleanup

| # | Task | Description |
|---|---|---|
| 5E.1 | Decision renumbering | Resolve doc 08 §3.1 — adopt prefix scheme (D00.1, D02.7, etc.) across all spec docs |
| 5E.2 | Doc 00 open questions audit | Resolve doc 08 §3.2 — mark answered questions with cross-references |

**Status:** DONE.

---

## Phase 6: CI/CD — DONE (2026-03-22)

**Goal:** Automate builds, testing, and release publishing via GitHub Actions.

**Prerequisites:** Phase 5 test scripts exist.

### Tasks

| # | Task | Description |
|---|---|---|
| 6.1 | APK build workflow | GitHub Actions: build debug APK on every push to main. Upload as artifact. |
| 6.2 | Test scripts in CI | Run device API test scripts against mock/recorded responses (no real hardware in CI) |
| 6.3 | Android unit tests in CI | Run 5C unit tests as part of the build workflow |
| 6.4 | Release workflow | On git tag (e.g., `v1.0`): build APK, create GitHub Release, attach APK as asset |
| 6.5 | Web deploy (already done) | Formalize the existing gh-pages workflow, ensure it runs on web/ changes only |
| 6.6 | Build status badge | Add build status badge to README.md |

### Gate: Phase 6 → Done

- Push to main triggers build + tests
- Tagged commit creates a GitHub Release with APK attached
- README shows build status badge

**Status:** DONE.

---

## What can be done in parallel

- **Phase 5 and Phase 6** overlap: 6.1 (build workflow) can be done before tests exist; test integration (6.2, 6.3) needs Phase 5 scripts
- **Doc cleanup** (5E.1, 5E.2) can be done anytime

---

## Risk-adjusted time estimate (Phases 1–3)

| Phase | Optimistic | Realistic | If things go wrong |
|---|---|---|---|
| Phase 1 | Half a day | 1 day | 2-3 days (if alternatives needed) |
| Phase 2 | 3 days | 5-7 days | 2 weeks (mJS debugging) |
| Phase 3 | 5 days | 1-2 weeks | 3 weeks (learning Kotlin/Compose) |
| **Total** | **~1 week** | **~2-3 weeks** | **~5-6 weeks** |

The biggest variable is phase 3 — if you've done Android development before, it's fast. If not, the Kotlin/Compose learning curve is the dominant cost. The actual app logic is trivial; it's the Android tooling and project structure that takes time the first time.
