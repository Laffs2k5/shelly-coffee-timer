# v2 Implementation Tracking

> Living progress tracker for the local-MQTT + watchdog re-architecture (design: [spec/11-local-mqtt.md](../spec/11-local-mqtt.md), validation: [spec/12-watchdog-validation.md](../spec/12-watchdog-validation.md)). Branch: `feat/local-mqtt`.

## Constraints for this implementation round

- **No hardware** (the plug), **no phone**, **no emulator**, **no owner availability**, **no admin** on Windows.
- ⇒ Everything is implemented **blind** and verified only by **automated tests that can run here**.
- The device script (mJS) is verified with a **Node `node:test` harness that mocks the Shelly runtime** — logic is black-box tested (drive inputs, assert on captured `Switch.Set` / `MQTT.publish` / `KVS` / HTTP responses).
- Things needing real hardware/broker/phone (mTLS cert upload, live MQTT, APK on device) are **left as documented ops steps** and flagged **UNVERIFIED**.

## Phase status

| Phase | Scope | Status | Verification |
|---|---|---|---|
| 0 | Tracking doc + Node test harness (Shelly mock) | ✅ done | harness runs |
| 1 | Device script: transport/topics/retain + `alive` | ✅ done | unit tests |
| 2 | Device script: state persistence + boot/resume reset-reason gate | ✅ done | unit tests |
| 3 | Device script: two-tier connectivity watchdog | ✅ done | unit tests |
| 4 | Web fallback (`web/index.html`) → MQTT-WSS cloud-only | ✅ done (logic tested) | unit tests + syntax check; connection UNVERIFIED |
| 5 | Android app → **broker-list (local mTLS → cloud) + keep HTTP-direct** | ✅ done — **verified on hardware** | unit tests + `assembleDebug`; phone sessions 1–3 confirmed all transports, roaming, schedule set/clear (schedule _fire_ still pending) |

## How to run the tests

```bash
scripts/test-device.sh
# or directly:
node --test 'device/test/*.test.js'   # Node 18+, built-in runner, no deps
```
Current: **33 JS tests passing** (25 device + 8 web) + **Android `assembleDebug` BUILD SUCCESSFUL**.

### Building the Android APK (Windows, no admin)
The repo `app/` is synced into a Windows build project (with a gradle wrapper) and built there:
```
# sync repo app/ (src + build.gradle.kts + settings.gradle.kts) into the Windows build dir, then
# on Windows (pwsh) set ANDROID_HOME + JAVA_HOME (Android Studio jbr) and run:
.\gradlew.bat assembleDebug --no-daemon --console=plain
```
SDK 35 / JDK 21 (Android Studio jbr) / gradle 8.11.1. CI `build.yml` builds on an x86_64 runner.
**JVM unit tests** (pure connection logic, no device): `.\gradlew.bat testDebugUnitTest` (9 tests).

## Key decisions & findings (newest first)

- **Schedule set/clear fixed + keepalive churn verified (2026-06-06, phone session 3).** Keepalive-churn
  fix PROVEN by socket (background → 0 sockets, no reconnect loop; foreground → clean reconnect). Schedule
  was found BROKEN: config writes are version-gated but no controller-facing channel exposed the current
  version. Fixed by adding `v`/`dur`/`max` to the heartbeat **and** `coffee_status`; `updateSchedule` now
  derives the new config from the last device status instead of the (empty) retained-config read. Device
  reflashed + app rebuilt; set→`cfg_v 122→123,sch=1`, clear→`124,sch=0`, verified via device KVS. Device
  tests 33/33, app 9/9. Schedule **fire** on hardware still pending. See
  [HW-VALIDATION-v2.md](HW-VALIDATION-v2.md), [spec/03](../spec/03-message-format.md).


- **App connection rework (2026-06-06).** Phone hardware testing proved all three transports
  (HTTP-direct, local-broker mTLS, cloud — the last two by adb socket inspection) but exposed a
  **roaming bug**: the app drifted off the best path and clung to an existing MQTT connection. Reworked:
  4 distinct connection types (HTTP_DIRECT / LOCAL_BROKER / CLOUD / OFFLINE) with distinct footer
  labels+colors, roaming that re-evaluates every poll (HTTP-direct preferred, drop MQTT when it works;
  periodic cloud→local roam-up), and a 4-entry connection event log. Pure logic (`CoffeeApi.decide`,
  `ConnectionUi.label`/`pushIfChanged`) is JVM-unit-tested (9 tests, `testDebugUnitTest`).
  **VERIFIED ON HARDWARE (2026-06-06, phone session 2):** all 4 connection types socket-confirmed
  (HTTP-direct / local-broker `:8883` / cloud `:8084` / offline), roaming up+down observed by socket,
  event log capped-at-4 observed, route-B read+write proven (KVS cross-check). See
  [HW-VALIDATION-v2.md](HW-VALIDATION-v2.md). Still deferred: keepalive-churn runtime check +
  schedule set/clear.

- **Android app (Phase 5) implements the FULL spec 11 §7 broker list — compiles.** `assembleDebug` BUILD SUCCESSFUL on Windows. Reference implementation for future apps. Transport (`api/MqttTransport.kt`) tries **local broker mTLS** (`ssl://<localHost>:8883`, no username → cert CN identity) first, falls back to **cloud** (`wss://<cloudHost>:8084/mqtt`, user/pass); `connectedVia` tracks which. `api/MqttTls.kt` builds the mTLS `SSLSocketFactory` from the **bundled public CA** (`res/raw/mqtt_ca.crt`) + an **imported PKCS#12** client identity. `CoffeeApi` remote methods delegate with unchanged signatures (HTTP-direct stays the on-Wi-Fi hard-req path; MQTT broker-list is the reference transport). Settings gained a local-host field, a `.p12` password field, and an **Import client certificate (.p12)** button (writes to `filesDir/client.p12`).
  - _Earlier I had simplified this to cloud-only (no phone mTLS); reverted on owner direction — the full broker-list is wanted as the reference. [ANDROID-V2-PLAN.md](ANDROID-V2-PLAN.md) is back in force._
  - **Runtime UNVERIFIED** (no device): mTLS handshake to Mosquitto, failover, KeyStore/p12 load, cert-import UX.
  - **Production hardening TODO:** the `.p12` password is in plain `SharedPreferences` — move to `EncryptedSharedPreferences`/AndroidKeyStore. The client `.p12` is in app-internal storage (not the repo); the bundled `mqtt_ca.crt` is the *public* CA cert (safe to ship).
- **Web fallback ported (Phase 4).** `web/index.html` now talks **MQTT over WSS to EMQX** (cloud-only escape hatch) instead of Adafruit REST: `mqtt.js@5.10.1` (pinned CDN), subscribe retained `heartbeat` + `config`, publish `command` (retain=false) / `config` (retain=true). Pure logic (topics, payloads, heartbeat parse, broker URL) is in `web/coffee-core.js` and unit-tested (8 tests); the MQTT-connection + DOM wiring in `index.html` is **UNVERIFIED** (no broker/browser here, syntax-checked only). Creds = the phone's `YOUR_PHONE_ID` cloud user/pass in `localStorage` (Q6); a **distinct random clientId** avoids taking over the phone's MQTT session.

- **mJS `try/catch` avoided.** Used `JSON.parse` directly (mJS returns `undefined` on bad input, never throws) instead of a `try/catch` wrapper, since `try/catch` support in mJS is uncertain and a parse-time error would brick the script. The Node harness injects a `JSON.parse` with the same undefined-on-failure semantics so tests match device behaviour. Verified by a malformed-input test.
- **Test strategy = black-box via mocked Shelly runtime.** `device/test/harness.js` loads `coffee.js` in a `vm` context with mocked `Shelly`/`MQTT`/`Timer`/`HTTPServer`/`print`/`Date`. Top-level `function`s are callable; `let` state is observed only through outputs (`Switch.Set` relay, `MQTT.publish`, `KVS`, the `coffee_status` HTTP response) — true black-box, so refactors that preserve behaviour stay green.
- **Watchdog gating.** The two-tier watchdog (`Wi-Fi 900 s` / `broker 10800 s`) is implemented unconditionally in `main_loop`; safety comes from the resume gate, not from disabling the watchdog — a reboot only resumes when `reset_reason == 3`, otherwise boots OFF. Verified the nightly-Pi-blip case (broker down 1 h) does NOT reboot.
- **Flash-wear bound.** `rt_remain` is persisted every 60 s **only while ON** (no churn while OFF) plus on transitions; verified by tests.

## Verified on hardware (phone sessions 1–3, 2026-06-06)

Device runs the real minified script on a Plug S Gen3 (deployed via chunked `Script.PutCode`, mem_free
~2.6 KB, stable). Confirmed end-to-end with the physical phone + broker (evidence in
[HW-VALIDATION-v2.md](HW-VALIDATION-v2.md)): all 4 connection types (HTTP-direct / local-broker
mTLS / cloud / offline, socket-confirmed), roaming both directions, the event log, command read+write over
every transport, the notification service, the keepalive-churn fix, **schedule set/clear**, and the
**schedule fire** (relay switched ON at the armed time with `mode=sch`, then auto-disarmed `sch→0`).

## UNVERIFIED / needs hardware before production

- **Device script edge cases under real mJS** — `~3 concurrent Shelly.call` / `~4–5 timer` limits under
  sustained watchdog+persist load over long uptimes; only spot-checked so far.
- **Web (Phase 4):** MQTT-over-WSS connection + DOM wiring — never run against a real broker/browser.
