# Architecture Overview

High-level system architecture for the Shelly Coffee Timer project.

---

## System Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         INTERNET                                        │
│                                                                         │
│  ┌──────────────┐       REST API        ┌────────────────────────┐     │
│  │  Android App │ ◄──────────────────► │     Adafruit IO        │     │
│  │  (Kotlin/    │  GET heartbeat/last   │  (Cloud MQTT Broker    │     │
│  │   Compose)   │  POST command/data    │   + REST API)          │     │
│  │              │  GET/POST config/data │                        │     │
│  └──────┬───────┘                       │  Feeds:                │     │
│         │                               │   command  (phone→dev) │     │
│         │                               │   config   (phone→dev) │     │
│         │                               │   heartbeat (dev→phone)│     │
│  ┌──────┴───────┐                       └────────────┬───────────┘     │
│  │  HTML Page   │  REST API only                     │                 │
│  │  (GitHub     │ ◄─────────────────────────────────►│                 │
│  │   Pages)     │  (no local access due to CORS)     │ MQTT            │
│  └──────────────┘                                    │ (TLS, QoS 1)   │
│                                                      │                 │
└──────────────────────────────────────────────────────┼─────────────────┘
                                                       │
┌──────────────────────────────────────────────────────┼─────────────────┐
│                     LOCAL WIFI                        │                 │
│                                                      │                 │
│  ┌──────────────┐    HTTP (direct)    ┌──────────────▼──────────────┐  │
│  │  Android App │ ◄────────────────► │    Shelly Plug S Gen3      │  │
│  │  (on wifi)   │  GET coffee_status  │                            │  │
│  │              │  GET coffee_command  │  mJS script (coffee.js)   │  │
│  └──────────────┘                     │  - State machine           │  │
│                                       │  - KVS persistence         │  │
│         Physical                      │  - MQTT client             │  │
│         button ──────────────────────►│  - HTTP server             │  │
│                                       │  - Timer engine            │  │
│                                       └────────────────────────────┘  │
│                                                    │                   │
│                                              AC Relay                  │
│                                                    │                   │
│                                       ┌────────────▼────────────────┐  │
│                                       │       Coffee Maker          │  │
│                                       └─────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Control Paths

| Path | Route | Latency | Requirements |
|------|-------|---------|-------------|
| Physical button | Button → Shelly firmware → mJS status handler | Instant | None (always works) |
| Local HTTP | Phone → wifi → Shelly HTTP → mJS endpoint | ~10ms | Same wifi network |
| Remote MQTT | Phone → REST → Adafruit IO → MQTT → Shelly | 1-5s | Internet on both ends |

---

## On-Device Timer Architecture

The mJS runtime is constrained to ~4-5 concurrent timers. The script uses a single
30-second repeating timer that handles all periodic tasks via counter-based dispatch:

```
Timer.set(30000, true, main_loop)
                  │
                  ▼
         ┌─ main_loop() ─────────────────────────────────────┐
         │                                                     │
         │  tick_counter++                                     │
         │  if tick_counter >= 2:     ← every 60s             │
         │      decrement remain by 60s                        │
         │      if remain <= 0: turn_off()                     │
         │                                                     │
         │  hb_elapsed += 30                                   │
         │  if hb_pending OR hb_elapsed >= interval:           │
         │      publish_heartbeat()   ← 300s when on, 900s off│
         │                                                     │
         │  if cfg_sch==1 AND ntp AND !sw_on:                  │
         │      check if current time matches schedule         │
         │      if match: fire schedule, disarm                │
         │                                                     │
         │  if !mqtt_init_done:       ← once, ~30s after boot │
         │      check MQTT, request config via /get            │
         └─────────────────────────────────────────────────────┘
```

---

## Heartbeat Flow with Debounce

The device publishes heartbeats to Adafruit IO to keep the phone informed. A 2-second
debounce prevents burst publishing when multiple events fire close together.

```
Event triggers:                    Debounce logic:
  state change (on/off)  ──┐
  command processed      ──┤      do_publish_heartbeat(force):
  config received        ──┼──►     if !force AND (now - hb_last_ts < 2):
  schedule fires         ──┤            hb_pending = true    ← deferred
  MQTT connect           ──┘            return
                                    hb_pending = false
                                    hb_last_ts = now
                                    MQTT.publish(heartbeat)
                                    hb_elapsed = 0

Periodic flush (in main_loop):
  if hb_pending: do_publish_heartbeat(false)   ← catches deferred
```

The `hb_pending` flag ensures deferred heartbeats are flushed on the next 30-second
main_loop cycle, so no state change goes unreported for more than 30 seconds.

---

## Physical Button Detection

The Shelly Plug S Gen3 has no separate Input component. The physical button toggles
the switch directly in firmware. Both button presses and `Switch.Set` API calls fire
the same `switch:0` status change event.

```
                   ┌──────────────────────┐
                   │  Status handler      │
switch:0 event ──► │  (Shelly.addStatus)  │
                   │                      │
                   │  if script_switching: │──► ignore (our own call)
                   │      return           │
                   │                      │
                   │  else:               │──► physical button pressed
                   │      sync state      │    update remain, mode, ack
                   │      publish hb      │
                   └──────────────────────┘

Script-initiated switch changes:
  script_switching = true
  Shelly.call("Switch.Set", ..., callback {
      script_switching = false
  })
```

---

## Boot Sequence

```
1. KVS load (sequential chain, avoids "too many calls" crash)
   cfg_v → cfg_sch → cfg_h → cfg_m → cfg_dur → cfg_max
                                                    │
2. boot_complete()                                  ▼
   ├── Force switch OFF (safety)
   ├── Subscribe MQTT: command, config
   ├── Start main_loop timer (30s repeating)
   ├── Register status handler (NTP, MQTT, button)
   ├── Register HTTP endpoints (coffee_status, coffee_command)
   └── Check if NTP already synced
```

---

## Android App Architecture

```
┌─────────────────────────────────────────────────────────┐
│  MainActivity                                            │
│  ├── MainScreen (Compose)                                │
│  │   ├── Status card (polled every 10s)                  │
│  │   ├── Timer buttons (OFF, -30, +30, 90)               │
│  │   ├── Schedule section (toggle + time picker)          │
│  │   └── Connection footer (Wi-Fi / Internet / Offline)   │
│  └── SettingsScreen (Shelly IP, AIO user, AIO key)        │
├──────────────────────────────────────────────────────────┤
│  CoffeeApi (singleton)                                    │
│  ├── pollStatus() — auto-detect local/remote              │
│  │   ├── Try local first (2s timeout)                     │
│  │   ├── Fall back to remote (Adafruit IO REST)           │
│  │   └── Mode caching: skip local for 6 polls after fail  │
│  ├── sendCommand() — route via current mode               │
│  ├── fetchRemoteConfig() / writeRemoteConfig()            │
│  └── Connection mode enum: LOCAL / REMOTE / OFFLINE       │
├──────────────────────────────────────────────────────────┤
│  Notification subsystem (only while coffee is ON)         │
│  ├── CoffeeNotificationService (foreground service)       │
│  │   ├── Polls device every 30s                           │
│  │   ├── Local countdown between polls (1 min ticks)      │
│  │   ├── Shows "Coffee ON — N min remaining"              │
│  │   ├── Shows "Connection lost" after 10 failures (~5m)  │
│  │   └── Self-stops when device reports OFF                │
│  ├── ScheduleAlarmManager                                 │
│  │   ├── Sets AlarmManager for scheduled coffee time       │
│  │   ├── Re-armed on every successful poll with sch=1      │
│  │   └── Cancelled when sch=0                              │
│  ├── ScheduleAlarmReceiver                                │
│  │   └── Starts CoffeeNotificationService on alarm fire    │
│  └── NotificationHelper                                   │
│      └── Channel creation, notification build/update/cancel│
└──────────────────────────────────────────────────────────┘
```

### Auto-detect Mode Caching

The app tracks `lastMode` and `localFailCount` to optimize polling:

- If last mode was REMOTE, skip the 2-second local timeout on most polls
- Try local again every 6th poll (~60 seconds) to detect returning home
- On local success, reset fail counter and switch to LOCAL immediately

---

## CI/CD Pipeline

```
Push to main ──► Build workflow (.github/workflows/build.yml)
                  ├── Build debug APK
                  └── Upload as GitHub Actions artifact

Push tag v* ───► Release workflow (.github/workflows/release.yml)
                  ├── Build debug APK
                  ├── Generate changelog from commits
                  └── Create GitHub Release with APK attached

Push web/** ───► Deploy workflow (.github/workflows/deploy-pages.yml)
                  └── Publish web/ to gh-pages branch
```

---

## Message Formats (Quick Reference)

**Command** (phone to device, via command feed):
```json
{"c":"t90","ts":1711036800}
```

**Config** (phone to device, via config feed):
```json
{"v":25,"sch":1,"h":6,"m":10,"dur":90,"max":180}
```

**Heartbeat** (device to phone, via heartbeat feed):
```json
{"s":"on","r":84,"mode":"remote","sch":0,"h":9,"m":27,"ack":"t90","ts":1774181053,"ntp":true}
```

**Local status** (device HTTP response, longer key names):
```json
{"state":"on","remaining":84,"mode":"remote","sch":0,"h":9,"m":27,"ntp":true,"ts":1774181087}
```
