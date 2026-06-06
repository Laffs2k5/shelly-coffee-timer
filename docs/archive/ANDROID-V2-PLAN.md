# Android app — v2 migration plan

> **✅ IMPLEMENTED (2026-06-06) — this plan is in force.** The full broker-list transport (local mTLS →
> cloud) is built and **compiles** (`assembleDebug` BUILD SUCCESSFUL); see `IMPLEMENTATION.md`.
> Reference implementation for future apps. **Runtime is UNVERIFIED** (no device): mTLS handshake,
> failover, and the PKCS#12 import UX need the physical phone + broker. Deviations from the detail
> below: the client identity is loaded from an imported PKCS#12 in app storage (not AndroidKeyStore
> yet — a production hardening TODO), and HiveMQ was replaced with **Paho mqttv3** (lower build risk).

> Concrete, file-by-file plan for porting the app off Adafruit REST onto the local-MQTT stack
> (design: [spec/11-local-mqtt.md](../spec/11-local-mqtt.md) §7). **Not implemented as code** in this
> round: it can't be compiled (WSL `aapt2` is x86_64-only; Android Studio on this Windows-ARM box runs
> under x64 translation) nor run (no emulator on Windows-ARM, no physical device, no owner). Writing it
> blind would be unverified code masquerading as done. This is the ready-to-execute spec for when a
> build/test environment (an x86_64 host or the physical phone) is available.

## Current shape (what exists)

- `app/.../api/AdafruitApi.kt` — `object CoffeeApi` (309 lines). Methods:
  - **Keep:** `fetchLocalStatus(shellyIp)`, `sendLocalCommand(shellyIp, cmd)` — HTTP-direct to
    `http://<ip>/script/1/coffee_status|coffee_command`. **Hard req (D11.64): must keep working.**
  - **Replace:** `fetchRemoteStatus(user, key)`, `sendRemoteCommand(user, key, cmd)`, and the config
    read/write — currently Adafruit REST (`io.adafruit.com/api/v2/...`).
  - `ConnectionMode { LOCAL, REMOTE, OFFLINE }`, `DeviceStatus`, `ConfigData` data classes — keep.
- `app/.../notification/*` — foreground service polls `CoffeeApi` every 30 s; `ScheduleAlarmManager`
  re-arms on each poll. Transport-agnostic — keep the service, swap what it calls underneath.
- `app/build.gradle.kts` — no MQTT dependency yet.

## Plan

### 1. Dependency
Add an MQTT 5 client. **HiveMQ MQTT Client** (recommended — first-class MQTT 5, TLS, reconnect):
```kotlin
// app/build.gradle.kts
implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
```
(Paho `org.eclipse.paho:paho-mqtt-client` is the fallback if HiveMQ pulls problematic transitive deps.)

### 2. New transport: `MqttTransport.kt` (new file in `api/`)
A small class owning one MQTT connection with **broker-list failover**:
- **Primary (on Wi-Fi):** local Mosquitto `192.168.x.x:8883`, **mTLS** — trust the bundled private CA
  (`ca.crt` in `res/raw` or assets), present the `YOUR_PHONE_ID` client cert/key loaded from the
  **Android KeyStore**.
- **Secondary (off-LAN):** EMQX `…emqxsl.com:8883` (or `:8084` wss), **username/password**, system
  trust store, **SNI + ALPN=`mqtt`** set explicitly.
- Short local connect timeout (3–5 s) → fail over to cloud. `cleanStart=false`, **stable clientId
  `YOUR_PHONE_ID`**, LWT. Distinct from the web fallback's clientId so they don't take over each other.
- Subscribe `devices/YOUR_DEVICE_ID/heartbeat` (retained → "last seen" arrives on connect) and
  `.../config`. Publish `.../command` (retain=false) and `.../config` (retain=true).
- Expose a callback/StateFlow `DeviceStatus?` so the UI + notification service consume pushes instead
  of polling. **Do NOT subscribe `mon/.../alive` or `.../online`** (those are the infra monitor's).

### 3. Rewire `CoffeeApi`
- Delete `fetchRemoteStatus` / `sendRemoteCommand` / Adafruit config calls; route "remote" through
  `MqttTransport`. Keep `ConnectionMode`: LOCAL = HTTP-direct on Wi-Fi (lowest latency), REMOTE = MQTT
  (local or cloud per failover), OFFLINE = neither.
- Keep `fetchLocalStatus`/`sendLocalCommand` unchanged (HTTP-direct hard req). Auto-detect stays:
  try HTTP-direct first on Wi-Fi, else MQTT.
- Schedule writes: publish retained `config` with a bumped `v` (read-modify-write of the cached
  retained config), mirroring `web/coffee-core.js` `buildConfig`.

### 4. Settings screen
- Replace "AIO user / AIO key" with: **cloud MQTT username + password** (the `YOUR_PHONE_ID` cloud creds),
  keep **Shelly IP**, add a one-time **client-cert import** (PKCS#12 → Android KeyStore) for LAN mTLS.
- Bundle `ca.crt` (private CA, public cert) in the APK.

### 5. Notification service
- Switch from 30 s polling to consuming `MqttTransport`'s pushed `DeviceStatus`; keep the local
  countdown, "Connection lost" after N missed, and `ScheduleAlarmManager` re-arm on `sch=1`. The
  foreground-service lifecycle (only while ON) is unchanged.

## Testing when a build env exists

- **Unit-testable now (JVM, no device):** the pure helpers — `buildConfig` version bump, command
  payload, heartbeat parse — mirror `web/coffee-core.js`; port those tests to JUnit (`app/src/test`).
- **Needs device/emulator:** mTLS handshake to Mosquitto, cloud SNI/ALPN, failover, KeyStore cert load,
  push→notification. Validate on the physical phone (per CLAUDE.md, no Windows-ARM emulator).
- Build via the existing CI `build.yml` (x86_64 GitHub runner) to get an APK without a local x64 host.

## Risks / open

- HiveMQ vs Paho transitive-dependency / minSdk fit — verify at build time.
- Android cleartext policy: HTTP-direct is plaintext on LAN — keep a `network_security_config` exception
  scoped to the Shelly IP/subnet (already needed in v1 for local HTTP).
- mTLS client-cert UX on Android (KeyStore import) is fiddly — the cert import flow is the riskiest UX.
