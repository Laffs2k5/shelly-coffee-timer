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

## Phase 4: UI Polish — NOT STARTED

**Goal:** Improve the user experience of both the Android app and web page. Future work.

**Prerequisites:** Phase 3 complete.

### Android app

- Loading spinner during connection attempts and mode switches
- Better layout and spacing
- Dark theme refinements
- Notification when coffee maker is currently on

### HTML page

- Visual improvements (styling, responsiveness)
- Possibly add PWA support (offline caching, add-to-home-screen)

### Both

- Consistent look and feel between Android app and web page

**Status:** NOT STARTED — future work.

---

## Phase 5: Testing & Quality — NOT STARTED

**Goal:** Establish formal testing practices and clean up documentation debt. Future work.

**Prerequisites:** Phase 3 complete (Phase 4 is independent — these can be done in either order).

### Tasks

| # | Task | Description |
|---|---|---|
| 5.1 | Manual regression test procedures | Document step-by-step test scripts for verifying all device, app, and web functionality |
| 5.2 | Device API test scripts | Bash/curl scripts that exercise local HTTP and Adafruit IO REST endpoints programmatically |
| 5.3 | AI-assisted test instructions | Prompts that an AI agent can execute to verify system functionality end-to-end |
| 5.4 | Android app test suite | Unit tests for business logic, UI tests for Compose screens |
| 5.5 | HTML page testing | Functional tests for the web control page |
| 5.6 | End-to-end test scenarios | Full workflow tests: schedule fire, extend, expire, config change, multi-client |
| 5.7 | Decision renumbering | Resolve doc 08 §3.1 — consolidate decision numbers across all spec docs |
| 5.8 | Doc 00 open questions audit | Resolve doc 08 §3.2 — mark answered questions in doc 00 with cross-references |

**Status:** NOT STARTED — future work.

---

## What can be done in parallel

- **Phase 4 and Phase 5** are independent of each other
- **Doc cleanup** (items 5.7 and 5.8) can be done anytime

---

## Risk-adjusted time estimate (Phases 1–3)

| Phase | Optimistic | Realistic | If things go wrong |
|---|---|---|---|
| Phase 1 | Half a day | 1 day | 2-3 days (if alternatives needed) |
| Phase 2 | 3 days | 5-7 days | 2 weeks (mJS debugging) |
| Phase 3 | 5 days | 1-2 weeks | 3 weeks (learning Kotlin/Compose) |
| **Total** | **~1 week** | **~2-3 weeks** | **~5-6 weeks** |

The biggest variable is phase 3 — if you've done Android development before, it's fast. If not, the Kotlin/Compose learning curve is the dominant cost. The actual app logic is trivial; it's the Android tooling and project structure that takes time the first time.
