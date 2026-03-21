# Shelly Coffee Maker — Phone Control Interface

## 1. Requirements driving the design

From the mockup and discussion, the phone interface must:

1. **Display live status** — on/off, remaining minutes when on (updated every 10 seconds)
2. **Provide instant controls** — timer buttons (0, -30, +30, 90)
3. **Configure schedule** — toggle enable/disable, set hour and minute
4. **Auto-detect local vs remote** — try the Shelly's local IP first, fall back to Adafruit IO REST
5. **Work on multiple Android phones** — installable/shareable without per-device setup
6. **No Apple products** — Android only

---

## 2. Technology evaluation

### 2.1 Why a web page alone doesn't work

A hosted HTML page (GitHub Pages, any static host) can talk to Adafruit IO's REST API — they serve proper CORS headers. However, the **local auto-detect path is blocked by CORS**. When the browser tries `fetch("http://192.168.1.xxx/script/1/coffee_status")` from a page served at `https://github.io/...`, the Shelly's HTTP server does not include `Access-Control-Allow-Origin` headers, and the browser rejects the response.

This means a pure browser-based solution can only do remote (Adafruit IO), not local. The auto-detect requirement is incompatible with browser security constraints.

**Exception:** If the Shelly served the HTML page itself (same origin), local calls would work. But then the page isn't reachable when away from home. You'd need two separate interfaces — one local, one remote — which defeats the purpose.

### 2.2 Options evaluated

| Option | Live status | Local + remote | Multi-phone | Effort | Cost |
|---|---|---|---|---|---|
| **Kotlin/Compose Android app** | Yes | Yes (no CORS) | Sideload APK | High (learn Android dev) | Free |
| **Flutter app** | Yes | Yes (no CORS) | Sideload APK | Medium-High | Free |
| **WebView wrapper app** (APK wrapping an HTML page) | Yes | Partial (CORS workaround possible) | Sideload APK | Medium | Free |
| **Tasker + KWGT widget** | Yes (with effort) | Yes | Export/import config | Medium (Tasker learning curve) | ~€5 |
| **HTTP Shortcuts app** | No (fire-and-forget only) | Yes | Export JSON | Low | Free |
| **Hosted HTML page (PWA)** | Yes (remote only) | Remote only (CORS blocks local) | URL bookmark | Low | Free |
| **HTML page served by Shelly** | Yes (local only) | Local only | Same wifi only | Low | Free |

### 2.3 Recommendation: Kotlin/Compose Android app + HTML fallback

**Primary interface: A native Android app.**

Rationale:
- No CORS restrictions — can call both the Shelly's local HTTP API and Adafruit IO REST freely
- Native polling with background timer for 10-second refresh
- Home screen icon, proper app lifecycle, works on all Android phones
- Sideloadable APK — build once, share the `.apk` file to install on any phone
- Full control over the UI matching your mockup exactly
- Kotlin is a reasonable language for someone who codes regularly (not Java boilerplate)

This is the highest-effort option, but it's the only one that cleanly satisfies all requirements. The app itself is small — one screen, a few HTTP calls, a 10-second timer. No database, no login, no complex navigation. The scope is closer to a "hello world with REST" tutorial than a real app.

**Fallback interface: A hosted HTML page (for computer access and remote-only phone use).**

A single HTML file on GitHub Pages (or any static host) that talks exclusively to Adafruit IO REST. Covers: laptop/desktop control, any phone without the app installed (remote only), debugging. Does not support local access.

### 2.4 Technology choice for the Android app

| Component | Choice | Why |
|---|---|---|
| Language | Kotlin | Modern Android standard, avoids Java verbosity |
| UI toolkit | Jetpack Compose | Declarative, less boilerplate than XML layouts |
| HTTP client | `java.net.HttpURLConnection` or OkHttp | Simple GET/POST is all we need; Retrofit is overkill for 4 endpoints |
| JSON parsing | `org.json.JSONObject` (built-in) | Already in Android, no library needed for flat JSON |
| Architecture | Single-activity, single-screen | One ViewModel, one composable, one timer |
| Min SDK | 26 (Android 8.0) | Covers ~95% of devices in active use |
| Distribution | Sideload APK (no Play Store) | Build with Android Studio, share `.apk` file |

**No external libraries required.** The built-in Android HTTP and JSON APIs are sufficient for our 4 simple REST calls. This keeps the APK small and the build simple.

---

## 3. Interface design

### 3.1 Layout (matches mockup)

```
┌─────────────────────────────────────────┐
│  ▌ Manual control                       │  ← section header
├─────────────────────────────────────────┤
│  ⏻  Status        ON with 60 min to go │  ← live, refreshes every 10s
│  ⚡ Timer control  [ 0 ][-30][+30][ 90] │  ← buttons send commands
├─────────────────────────────────────────┤
│  ▌ Schedule                             │  ← section header
├─────────────────────────────────────────┤
│  ⏻  Schedule control          [toggle]  │  ← enable/disable
│  🕐 Schedule time             [06:10]   │  ← native Android TimePickerDialog
├─────────────────────────────────────────┤
│  ▌ Connection                           │  ← status bar
│  🔗 Local (192.168.1.xxx)    ● Online   │  ← or "Remote (Adafruit IO)"
│  Last updated: 06:15:30                 │
└─────────────────────────────────────────┘
```

### 3.2 Status display

The status line shows one of:

| State | Display |
|---|---|
| On, timer running | **ON with 74 min to go** (green) |
| Off | **OFF** (grey) |
| Unreachable | **Unknown — device not responding** (red) |

The remaining minutes come from the `r` field in the heartbeat (remote) or `remaining` field from `Coffee.Status` (local).

### 3.3 Timer buttons

Each button sends a command and immediately refreshes status:

| Button | Command code | Notes |
|---|---|---|
| **0** | `off` | Turn off |
| **-30** | `sub` | Subtract 30 min |
| **+30** | `ext` | Add 30 min |
| **90** | `t90` | Set to 90 min (or turn on with 90) |

**Remote path:** POST to `https://io.adafruit.com/api/v2/{user}/feeds/command/data` with body `{"value": "{\"c\":\"CMD\",\"ts\":UNIXTIME}"}`. The timestamp is the phone's current Unix time.

**Local path:** GET to `http://{shelly_ip}/script/1/coffee_command?cmd=CMD`. No timestamp needed (synchronous, no staleness check).

After sending a command, the app immediately polls for fresh status (doesn't wait for the next 10-second cycle).

### 3.4 Schedule controls

The schedule section has two elements:

- **Toggle** — arms/disarms the schedule
- **Time display** — shows the currently set time (e.g. "06:10"). Tapping opens the native Android `TimePickerDialog` (24-hour format). The user picks the time with the standard spinner or clock face, the app splits the result into `h` and `m` for the config JSON.

Schedule changes write a **config** message, not a command. The app must:

1. Read the current config from Adafruit IO: `GET /api/v2/{user}/feeds/config/data/last`
2. Parse the `value` field to get the current `v`, `sch`, `h`, `m`, `dur`, `max`
3. Modify the changed field(s)
4. Increment `v` by 1
5. POST the new config to `https://io.adafruit.com/api/v2/{user}/feeds/config/data`

**Local path for schedule changes:** The local HTTP path doesn't currently have a config endpoint (doc 05 only defines `Coffee.Command` and `Coffee.Status`). Two options:

**Option A:** Schedule changes always go via Adafruit IO, even when local. This means schedule changes require internet, but it keeps config authority simple (phone always writes to Adafruit IO, device always reads from Adafruit IO or KVS).

**Option B:** Add a `Coffee.Config` RPC endpoint to the mJS script that accepts config updates directly. This duplicates the config path and introduces a second writer to the config state.

**Recommendation: Option A.** Schedule changes are rare (once a day at most). Requiring internet for schedule configuration is acceptable. The config authority model stays clean. If internet is down, the schedule is whatever it was last set to — the device has it cached in KVS.

### 3.5 Connection status bar

Shows which path the app is using:

| State | Display |
|---|---|
| Local connected | **Local (192.168.1.xxx)** ● green |
| Remote connected | **Remote (Adafruit IO)** ● yellow |
| Both unreachable | **Offline** ● red |

Also shows "Last updated: HH:MM:SS" — the time of the most recent successful status fetch.

---

## 4. Auto-detect: local vs remote

### 4.1 Detection logic

On each poll cycle (every 10 seconds):

```
1. Try local: GET http://{shelly_ip}/script/1/coffee_status
     Timeout: 2 seconds
     If success → use local data, mark connection as "Local"

2. If local fails → try remote: GET https://io.adafruit.com/api/v2/{user}/feeds/heartbeat/data/last
     If success → parse value field, mark connection as "Remote"

3. If both fail → mark connection as "Offline", show last known state greyed out
```

### 4.2 Why try local first

- Faster response (~10ms vs ~200ms+)
- Works without internet
- More current data (direct device state vs heartbeat that may be up to 5 minutes old)
- Less load on Adafruit IO rate limit

### 4.3 Shelly IP configuration

The local IP must be configured in the app. Options:

**Option A: Hardcoded IP.** The Shelly has a DHCP reservation on the Omada router, so the IP is stable. Simplest approach — put the IP in a settings screen or even a constant.

**Option B: mDNS discovery.** Android supports mDNS/Bonjour via `NsdManager`. The app could discover `shellyplugsg3-XXXXXXXXXXXX.local` automatically. More complex, may not work reliably on all Android versions/networks.

**Recommendation: Option A with a settings field.** A single text field in the app settings where you type the Shelly's IP. Default to a sensible value. mDNS discovery can be added later if desired.

### 4.4 Command routing

When the app sends a command (timer button press):

```
if connection == "Local":
  GET http://{shelly_ip}/script/1/coffee_command?cmd={cmd}
  → synchronous response with new state
  → update UI immediately from response

else:
  POST https://io.adafruit.com/api/v2/{user}/feeds/command/data
    body: {"value": "{\"c\":\"{cmd}\",\"ts\":{unix_time}}"}
  → command sent, but response doesn't contain device state
  → immediately poll heartbeat for updated state
  → UI updates when heartbeat reflects the command (may take a moment)
```

Local commands are nicer UX — the response contains the new state directly. Remote commands have a brief lag while the command propagates through Adafruit IO to the device and the device publishes a new heartbeat.

---

## 5. App configuration

The app needs a small settings screen with:

| Setting | Value | Stored in |
|---|---|---|
| Shelly local IP | `192.168.1.xxx` | SharedPreferences |
| Adafruit IO username | `your_username` | SharedPreferences |
| Adafruit IO key | `your_aio_key` | SharedPreferences |
| Poll interval | `10` (seconds) | Hardcoded default, could be configurable |

**Multi-phone setup:** Configure once on phone A. For phone B, either enter the same three values manually, or export/import via a shared config (QR code, text file, or simple copy-paste of the three strings).

**Security note:** The AIO key is stored in SharedPreferences (private to the app, not accessible to other apps without root). The key is sent over HTTPS to Adafruit IO. The local Shelly connection is plain HTTP (port 80) on the local network — acceptable for a home network, and the Shelly doesn't support HTTPS for its local API.

---

## 6. HTML fallback (for computers and remote-only access)

A single static HTML file that provides the same controls via Adafruit IO REST only (no local path).

### 6.1 Capabilities

- Same UI layout as the Android app
- 10-second auto-refresh of status
- Timer buttons (0, -30, +30, 90)
- Schedule configuration
- No local access (CORS limitation)

### 6.2 Implementation

A self-contained `.html` file with inline CSS and JavaScript. No build tools, no frameworks. Opens in any browser. Uses `fetch()` to call Adafruit IO REST API.

Configuration: AIO username and key are **never hardcoded** in the file (the repo is public). The page must prompt the user on first load and save to `localStorage`.

### 6.3 Hosting options

| Option | Pros | Cons |
|---|---|---|
| GitHub Pages | Free HTTPS, accessible everywhere, version controlled | Credentials must be entered at runtime (stored in `localStorage`) — repo is public |
| Open directly as local file | No hosting needed | `file://` may have fetch restrictions in some browsers |
| Any static web server | Full control | Need a server |

### 6.4 PWA installability

If hosted on GitHub Pages with HTTPS, the HTML page can include a minimal `manifest.json` and service worker to become a PWA. On Android Chrome, this allows "Add to Home Screen" which gives it an app icon and runs without browser chrome. Still remote-only (no local access), but a decent experience for when you're away from home.

---

## 7. Data flow summary

### 7.1 Status polling (every 10 seconds)

```
App                              Shelly (local)           Adafruit IO (remote)
 │                                    │                         │
 │── GET /script/1/coffee_status ─────────►│                         │
 │◄── {state, remaining, mode, ...} ──│                         │
 │  (if local reachable, use this)    │                         │
 │                                    │                         │
 │  (if local unreachable)            │                         │
 │── GET /feeds/heartbeat/data/last ──────────────────────────►│
 │◄── {value: "{\"s\":\"on\",...}"} ──────────────────────────│
```

### 7.2 Command (timer button)

```
App                              Shelly (local)           Adafruit IO (remote)
 │                                    │                         │
 │  [if local]                        │                         │
 │── GET /script/1/coffee_command?cmd=ext─►│                         │
 │◄── {ok, state, remaining, ack} ────│                         │
 │                                    │                         │
 │  [if remote]                       │                         │
 │── POST /feeds/command/data ─────────────────────────────────►│
 │                                    │◄── MQTT push ───────────│
 │                                    │── process command        │
 │                                    │── publish heartbeat ────►│
 │── GET /feeds/heartbeat/data/last ──────────────────────────►│
 │◄── updated heartbeat ─────────────────────────────────────────│
```

### 7.3 Schedule change

```
App                                                      Adafruit IO
 │                                                            │
 │── GET /feeds/config/data/last ────────────────────────────►│
 │◄── current config (v=3, sch=0, h=6, m=10, ...) ──────────│
 │                                                            │
 │  (user toggles schedule on)                                │
 │  (app increments v to 4, sets sch=1)                       │
 │                                                            │
 │── POST /feeds/config/data ────────────────────────────────►│
 │   {value: "{\"v\":4,\"sch\":1,\"h\":6,\"m\":10,...}"}     │
 │                                                            │
 │                              Shelly                        │
 │                                │◄── MQTT push config ──────│
 │                                │── updates KVS, arms sched │
 │                                │── publishes heartbeat ────►│
```

---

## 8. Impact on other docs

### 8.1 Doc 05 (state machine) — no changes needed

The mJS script's `Coffee.Status` and `Coffee.Command` RPC endpoints already return exactly what the app needs. The heartbeat format already contains all fields shown in the mockup. No new endpoints required.

### 8.2 Doc 04 (Adafruit IO) — no changes needed

The REST API usage for reading heartbeat (`/data/last`) and posting commands/config is already documented and matches what the app needs.

### 8.3 Doc 03 (message format) — minor addition

The command format includes a `ts` field for staleness. The app must generate this: `Math.floor(System.currentTimeMillis() / 1000)` in Kotlin. Already specified in doc 03 but worth noting that the phone generates this, not Adafruit IO.

### 8.4 Doc 01 (functional requirements) — addition needed

Doc 01 §6 (Status and acknowledgment) should be updated to explicitly state: "The phone interface displays live status with 10-second polling, matching the mockup layout: status line, timer buttons, schedule controls."

This was implicit but never stated as a requirement.

---

## 9. Decisions made

| # | Decision | Rationale |
|---|---|---|
| 36a | Primary phone interface is a native Android app (Kotlin/Compose) | CORS blocks local HTTP access from browser-hosted pages; native app has no such restriction |
| 37a | HTML page as fallback for computer access and remote-only use | Covers non-Android devices; remote path via Adafruit IO REST works from any browser |
| 38a | Auto-detect: try local first (2s timeout), fall back to remote | Local is faster, works without internet, provides more current data |
| 39a | Shelly IP configured manually in app settings | DHCP reservation makes IP stable; mDNS discovery adds complexity for little gain |
| 40a | Schedule changes always go via Adafruit IO, even when local | Keeps config authority model simple; schedule changes are rare and can tolerate internet requirement |
| 41a | Config version managed by read-increment-write on the phone | No local storage of version needed; always reads current from Adafruit IO before writing |
| 42a | Status polls every 10 seconds when app is in foreground | Balances freshness (heartbeat publishes every 5 min, but local gives real-time) against battery and rate limits |
| 43a | App distributed as sideloaded APK, not via Play Store | Single-user project; avoids Play Store requirements and review process |
| 44a | Schedule time uses native Android TimePickerDialog, not separate hour/minute fields | Better UX; the wire format still sends `h` and `m` separately in the config JSON |

**Note on decision numbering:** Decisions 36–46 were used in doc 05. Using 36a–43a here to avoid collision. The final numbering will be consolidated across all docs before implementation.

---

## 10. Open items

- [ ] Decide on exact Kotlin HTTP approach (built-in HttpURLConnection vs OkHttp)
- [ ] Design the settings screen (Shelly IP, AIO username, AIO key)
- [ ] Multi-phone config sharing mechanism (QR code? exportable text?)
- [ ] Should the app show a notification/indicator when the coffee maker is on? (Android persistent notification)
- [ ] Should the app poll in the background or only when open? (battery implications)
- [ ] Define the exact color scheme and icon to match the dark theme in the mockup
- [ ] Build the HTML fallback page
- [ ] Decide whether to add `Coffee.Config` local endpoint later (would enable offline schedule changes)
