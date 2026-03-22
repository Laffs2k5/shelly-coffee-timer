# Architecture Overview

High-level system architecture for the Shelly Coffee Timer project.

---

## System Diagram

```mermaid
graph TD
    subgraph Internet
        App[Android App<br/>Kotlin/Compose]
        HTML[HTML Page<br/>GitHub Pages]
        AIO[Adafruit IO<br/>Cloud MQTT Broker + REST API<br/>Feeds: command, config, heartbeat]

        App <-->|REST API<br/>GET heartbeat/last<br/>POST command/data<br/>GET/POST config/data| AIO
        HTML <-->|REST API only<br/>no local access due to CORS| AIO
    end

    subgraph Local WiFi
        AppLocal[Android App<br/>on wifi]
        Button[Physical Button]
        Shelly[Shelly Plug S Gen3<br/>mJS script coffee.js<br/>State machine, KVS persistence<br/>MQTT client, HTTP server, Timer engine]
        Coffee[Coffee Maker]

        AppLocal <-->|HTTP direct<br/>GET coffee_status<br/>GET coffee_command| Shelly
        Button -->|press| Shelly
        Shelly -->|AC Relay| Coffee
    end

    AIO <-->|MQTT<br/>TLS, QoS 1| Shelly
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

```mermaid
flowchart TD
    Timer["Timer.set(30000, true, main_loop)"] --> ML

    ML["main_loop()"] --> Tick
    Tick["tick_counter++<br/>if tick_counter >= 2 (every 60s):<br/>decrement remain by 60s<br/>if remain <= 0: turn_off()"]

    ML --> HB["hb_elapsed += 30<br/>if hb_pending OR hb_elapsed >= interval:<br/>publish_heartbeat()<br/>(300s when on, 900s off)"]

    ML --> Sched["if cfg_sch==1 AND ntp AND !sw_on:<br/>check if current time matches schedule<br/>if match: fire schedule, disarm"]

    ML --> Init["if !mqtt_init_done (once, ~30s after boot):<br/>check MQTT, request config via /get"]
```

---

## Heartbeat Flow with Debounce

The device publishes heartbeats to Adafruit IO to keep the phone informed. A 2-second
debounce prevents burst publishing when multiple events fire close together.

```mermaid
flowchart LR
    E1[state change] --> Debounce
    E2[command processed] --> Debounce
    E3[config received] --> Debounce
    E4[schedule fires] --> Debounce
    E5[MQTT connect] --> Debounce

    Debounce["do_publish_heartbeat(force)"]
    Debounce --> Check{"!force AND<br/>(now - hb_last_ts < 2)?"}
    Check -->|Yes| Defer["hb_pending = true<br/>(deferred)"]
    Check -->|No| Publish["hb_pending = false<br/>hb_last_ts = now<br/>MQTT.publish(heartbeat)<br/>hb_elapsed = 0"]

    Flush["Periodic flush in main_loop:<br/>if hb_pending: do_publish_heartbeat(false)"] --> Debounce
```

The `hb_pending` flag ensures deferred heartbeats are flushed on the next 30-second
main_loop cycle, so no state change goes unreported for more than 30 seconds.

---

## Physical Button Detection

The Shelly Plug S Gen3 has no separate Input component. The physical button toggles
the switch directly in firmware. Both button presses and `Switch.Set` API calls fire
the same `switch:0` status change event.

```mermaid
flowchart TD
    Event["switch:0 event"] --> Handler["Status handler<br/>(Shelly.addStatus)"]
    Handler --> Check{"script_switching?"}
    Check -->|Yes| Ignore["Ignore (our own call)"]
    Check -->|No| Button["Physical button pressed<br/>sync state, update remain/mode/ack<br/>publish heartbeat"]

    Script["Script-initiated switch changes:<br/>script_switching = true<br/>Shelly.call Switch.Set callback:<br/>script_switching = false"]
```

---

## Boot Sequence

```mermaid
flowchart TD
    KVS["1. KVS load (sequential chain)<br/>cfg_v → cfg_sch → cfg_h → cfg_m → cfg_dur → cfg_max"] --> Boot

    Boot["2. boot_complete()"]
    Boot --> B1["Force switch OFF (safety)"]
    Boot --> B2["Subscribe MQTT: command, config"]
    Boot --> B3["Start main_loop timer (30s repeating)"]
    Boot --> B4["Register status handler (NTP, MQTT, button)"]
    Boot --> B5["Register HTTP endpoints (coffee_status, coffee_command)"]
    Boot --> B6["Check if NTP already synced"]
```

---

## Android App Architecture

```mermaid
graph TD
    subgraph MainActivity
        MS[MainScreen - Compose]
        MS --> SC[Status card - polled every 10s]
        MS --> TB[Timer buttons: OFF, -30, +30, 90]
        MS --> SS[Schedule section: toggle + time picker]
        MS --> CF[Connection footer: Wi-Fi / Internet / Offline]
        Settings[SettingsScreen: Shelly IP, AIO user, AIO key]
    end

    subgraph CoffeeApi [CoffeeApi - singleton]
        Poll["pollStatus() — auto-detect local/remote<br/>Try local first (2s timeout)<br/>Fall back to remote (Adafruit IO REST)<br/>Mode caching: skip local for 6 polls after fail"]
        Send["sendCommand() — route via current mode"]
        Config["fetchRemoteConfig() / writeRemoteConfig()"]
        Mode["Connection mode: LOCAL / REMOTE / OFFLINE"]
    end

    subgraph Notification [Notification subsystem - only while coffee is ON]
        CNS["CoffeeNotificationService (foreground service)<br/>Polls device every 30s<br/>Local countdown between polls (1 min ticks)<br/>Shows 'Coffee ON — N min remaining'<br/>Shows 'Connection lost' after 10 failures (~5m)<br/>Self-stops when device reports OFF"]
        SAM["ScheduleAlarmManager<br/>Sets AlarmManager for scheduled coffee time<br/>Re-armed on every successful poll with sch=1<br/>Cancelled when sch=0"]
        SAR["ScheduleAlarmReceiver<br/>Starts CoffeeNotificationService on alarm fire"]
        NH["NotificationHelper<br/>Channel creation, notification build/update/cancel"]
    end
```

### Auto-detect Mode Caching

The app tracks `lastMode` and `localFailCount` to optimize polling:

- If last mode was REMOTE, skip the 2-second local timeout on most polls
- Try local again every 6th poll (~60 seconds) to detect returning home
- On local success, reset fail counter and switch to LOCAL immediately

---

## CI/CD Pipeline

```mermaid
flowchart LR
    Push["Push to main"] --> Build["Build workflow<br/>.github/workflows/build.yml"]
    Build --> B1["Build debug APK"]
    Build --> B2["Upload as GitHub Actions artifact"]

    Tag["Push tag v*"] --> Release["Release workflow<br/>.github/workflows/release.yml"]
    Release --> R1["Build debug APK"]
    Release --> R2["Generate changelog from commits"]
    Release --> R3["Create GitHub Release with APK attached"]

    Web["Push web/**"] --> Deploy["Deploy workflow<br/>.github/workflows/deploy-pages.yml"]
    Deploy --> D1["Publish web/ to gh-pages branch"]
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
