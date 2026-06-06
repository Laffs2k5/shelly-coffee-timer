# v2 Hardware Validation Log

Real-hardware testing of the v2 device script on the Plug S Gen3 (`shellyplugsg3-XXXXXX`, fw
1.7.5, `192.168.x.x`), driven from Windows (`pwsh` + RPC) with relay observed via Windows WMI
(`BatteryStatus.PowerOnline`). Owner away; phone testing deferred. **The device is restored to the
working v1 (Adafruit) setup at the end** so daily coffee keeps working.

Complements the automated unit tests (`scripts/test-device.sh`) — this covers what only real hardware
can: the v2 mJS actually running (size, concurrent-call/timer limits, no crashes), real `reset_reason`
resume, real countdown/auto-off, and real MQTT/mTLS on the local broker.

## Pre-flight (baseline)

- Device reachable: `shellyplugsg3-XXXXXX`, fw 1.7.5, app PlugSG3.
- `reset_reason=1`, uptime ~12 h, NTP synced.
- Scripts: id=1 "coffee" (v1) enable+running.
- `Switch initial_state=off`; relay ON at start; charger AC_online=True.
- **MQTT config backed up** (for restore): `io.adafruit.com:8883`, user `<aio-user>`,
  `client_id=shelly-coffee`, `ssl_ca="*"`, `topic_prefix=<aio-user>/feeds`, status_ntf/rpc_ntf
  false, use_client_cert false. (Password = `AIO_KEY` from `.env`, not returned by GetConfig.)

## Phone testing (2026-06-06) — OnePlus 13 (CPH2653, Android 16 / SDK 36) via adb

v2 APK installed (signature mismatch vs the old v1 build → uninstall+clean install). Phone on Wi-Fi
192.168.0.104/22 (same LAN as device + broker), against the live v2 device (on local broker).

| Test | Result |
|---|---|
| Install + launch + render | ✅ runs, UI renders, **no crash**; fresh install starts "Not connected/Offline" (no settings yet) |
| HTTP-direct **read** (Wi-Fi) | ✅ set Shelly IP → app shows live device **"ON / 102 min remaining"**, footer **"Wi-Fi"** (LOCAL mode) |
| HTTP-direct **write** | ✅ tapped **+30** → device went 102 → **132 min** (confirmed via RPC) |
| Notification service | ✅ foreground notification **"Coffee ON / 131 min remaining"**, `CoffeeNotificationService` running |

**Cloud (route C)** ✅ — Wi-Fi OFF (LAN unreachable) → footer "Internet", read shows live device, **+30
over cellular → device 125→154** (RPC-confirmed). Socket ESTAB to the EMQX cloud IP `:8084`. Definitive
(local broker was unreachable).

**LAN-mTLS (route B)** ✅ — bad Shelly IP (kills HTTP-direct) + bad cloud username (kills cloud) +
force-stop → fresh connect → **socket ESTAB to `192.168.x.x:8883`** AND app shows live device. Only
local-broker mTLS could serve → full CA-trust + `.p12` cert handshake + subscribe + retained heartbeat works.
> **Honesty note:** my first route-B attempt (bad IP only) was **inferred and wrong** — socket showed the
> app was on **cloud** (`:8084`) because a persisted cloud connection wasn't re-evaluated. The owner pushed
> back; disabling cloud + force-stop is what actually proved route B (socket to `:8883`).

### 🐞 Concluded bug — connection roaming is unstable / non-optimal
Observed live: the footer drifts **Wi-Fi → "Internet"** on its own, and after coming onto Wi-Fi from
cellular the app **stays on cloud** instead of switching to HTTP-direct / local mTLS. Root cause:
`MqttTransport.ensureConnected` returns early when already connected (no re-evaluation), the MQTT socket
lingers even in LOCAL mode, and `pollStatus`'s mode-caching is sticky → no proper "roam to the best path".

### Owner requirements for the app-code rework (2026-06-06)
1. **Visually distinguish** Wi-Fi **direct** (HTTP) vs **via local broker** (LAN mTLS) vs **cloud** vs offline.
2. **Fix roaming** — the app should move to the best available connection type as conditions change.
3. **Connection event log** at the bottom: dark-gray-on-black, timestamps, newest on top, **max 4** recent
   connection-type changes.

Still untested on the phone (at end of session 2): schedule set/clear — **addressed in session 3 below
(found broken, fixed, verified).**

### ✅ App-rework verification — phone session 2 (2026-06-06, reworked APK, all evidence observed)

Reworked APK installed (`-r`, settings preserved). Phone OnePlus 13, alternating cellular ⇄ Wi-Fi
192.168.0.104/22, live v2 device on the local broker (relay ON / charger powered). **Per owner directive
"verify, don't infer," every result below is from an observed socket (`ss -tn`) and/or screenshot — not
the footer label alone.**

All three owner requirements (§above) — **PASS on hardware:**

| Connection type | Label / colour | How it was reached | Observed proof |
|---|---|---|---|
| `HTTP_DIRECT` | "Wi-Fi · direct" / green | on Wi-Fi, good Shelly IP | reads device live; **0** sockets to `:8883`/`:8084` (HTTP-direct has no persistent socket) |
| `LOCAL_BROKER` | "Wi-Fi · broker" / light green | on Wi-Fi, **bad** Shelly IP (HTTP-direct killed) | **`ss` ESTAB `192.168.0.104:… → 192.168.x.x:8883`**, cloud `:8084` count **0** |
| `CLOUD` | "Cloud" / amber | Wi-Fi OFF (cellular) | **`ss` ESTAB `… → <emqx>:8084`** |
| `OFFLINE` | "Offline" / red | at launch, pre-connect | footer red, "Updated never" |

- **Roaming (the bug) — FIXED.** Cellular→Cloud, then **enable Wi-Fi → roams UP to Wi-Fi·direct** (cloud
  `:8084` socket torn down — confirmed gone via `ss`), then **disable Wi-Fi → roams back to Cloud** (`:8084`
  re-established). Both directions observed by socket, not just label. No more clinging to the worse path.
- **Event log — PASS.** Dark-gray-on-black, timestamped, newest-on-top; **capped at 4** — drove 5+
  transitions and watched the oldest entry drop (e.g. `17:59:02 Cloud / 17:58:32 Wi-Fi·direct /
  17:58:02 Cloud / 17:57:02 Wi-Fi·direct`).
- **Route-B read + write — PASS, socket-proven.** With the bad IP pinning LOCAL_BROKER: app read live
  device (ON, 84 min) **and** a `+30` command sent while `ss` showed the socket still on `:8883` raised the
  timer — independently confirmed by the device's own KVS `rt = {"on":1,"r":5040,...}` (5040 s = 84 min,
  matching the app exactly). _Caveat: the net delta read ~+25 not a clean +30 (timer granularity / ~1 min
  elapsed between the before-shot and the tap); only the "command took effect over the broker, app⟷device
  agree" claim is made — the exact delta wasn't tightly captured._

**Phone session 3 (2026-06-06) — the two deferred items:**

- ✅ **Keepalive-churn fix — PROVEN (commit 2631138), socket-observed.** Charger no longer on the laptop,
  so device verified via RPC/app display (relay state still reads over RPC). App put on cloud (Wi-Fi off).
  - _Coffee ON + backgrounded ~3 min:_ MQTT socket count held at 2 (no pile-up), one poll-driven reconnect
    (local port 40884→43162) — **not** the old tight loop (was ~5 connects/8 min). FG service keeps it warm.
  - _Coffee OFF + backgrounded ~2 min:_ FG service stopped (refs=0) → on `HOME`, MQTT sockets dropped to
    **0 and stayed 0** (the `ON_STOP` disconnect) — **zero** reconnect attempts. Foreground → clean reconnect.
  - Note: laptop's LAN→device RPC only works while the phone has Wi-Fi (laptop reaches the LAN *through* the
    phone). On cellular, device verified via the app's own (cloud) display instead.

- 🟢 **Schedule set/clear — was BROKEN, now FIXED + VERIFIED (see fix at end of this entry).** Root cause
  (all observed, verify-don't-infer):
  Tapping "Schedule enabled" does nothing — `cfg_sch` stays 0, toggle reverts on next poll. Chain:
  1. Device config writes are **version-gated**: `coffee.js` does `if (msg.v <= cfg_v) return;` (cfg_v=122).
     The controller must publish config with `v > 122`.
  2. **No controller-facing channel exposes the current version.** Heartbeat = `{s,r,mode,sch,h,m,ack,ts,ntp}`
     and HTTP `coffee_status` = `{state,remaining,mode,sch,h,m,ntp,ts}` — **neither carries `v`/`dur`/`max`.**
  3. The only config source is the **retained MQTT `config` topic** — but the device only *subscribes* to
     it (never publishes), and a focused subscribe proved **no retained config message exists on the broker**.
  4. So `CoffeeApi.fetchRemoteConfig` returns `MqttTransport.lastConfig` = `null` (also returns synchronously,
     before any retained msg could arrive), and `MainActivity.updateSchedule` does `?: return@launch` → aborts.
  - **This is a pre-existing v2 design gap**, not caused by the roaming rework — though the rework (tearing
    down MQTT on Wi-Fi·direct) removes the only thing that used to mask it (a long-lived MQTT conn that might
    have held a retained config). HTTP-direct `coffee_command` only accepts `on/off/ext/sub/t90` — no config.
  - **FIXED + VERIFIED ON HARDWARE (2026-06-06, same session).** Added `v`/`dur`/`max` to the heartbeat
    and `coffee_status` (device exposes the current config version); `updateSchedule` now derives the new
    config from the last device status (`version+1`, preserving dur/max) instead of the empty retained read.
    Device reflashed (chunked `Script.PutCode`, running, mem_free ~2.6 KB); app rebuilt (9/9 unit + APK) and
    reinstalled. End-to-end, observed via RPC on the device KVS:
    - **Set:** toggle ON → `cfg_sch 0→1`, `cfg_v 122→123` (version gate satisfied), time preserved.
    - **Clear:** toggle OFF → `cfg_sch 1→0`, `cfg_v 123→124`.
    - App UI reflects toggle state; config travels over MQTT (local broker on Wi-Fi) while status is read
      via HTTP-direct. Device test suite 33/33 still green.
    - ✅ **Schedule FIRE — VERIFIED ON HARDWARE (2026-06-06).** Armed for a future time (20:30) via the
      app, left the device untouched. At 20:30 the relay went **OFF→ON on its own**; `rt` became
      `{"on":1,"r":5400,"m":"sch"}` (90-min countdown, **mode=`sch`** confirms the schedule — not
      manual/remote — started it); and `cfg_sch` **auto-disarmed 1→0** (fires once then disarms, the safety
      rule). Polled every 25 s via RPC; relay flipped exactly in the 20:30 minute. Returned to OFF after.

> Housekeeping: route-B testing left the app's Shelly IP set to a bad value (`192.168.0.250`) to pin the
> local broker; owner to restore it to `192.168.x.x` in Settings. Device timer was bumped to ~84 min.

## Results (2026-06-06)

### 🔴 CRITICAL FINDING — v2 source OOMs the mJS heap; deploy the minified build
- Flashing the full commented v2 source (**15 KB**) → the script ran briefly then **crashed with
  `out_of_memory`** (`Script.GetStatus` → `running:false, errors:["out_of_memory"]`) during normal HTTP
  commands. System RAM was fine (108 KB free); the **mJS *script* heap** is the tiny, fixed limit.
- **Fix:** strip comments/blank lines/indentation → **10.3 KB** (≈ the proven v1 footprint, 10.9 KB).
  Added `scripts/build-device.sh` → `device/coffee.min.js`; **flash that, not `coffee.js`.**
- After the fix: stable. `mem_free` ~2.5 KB and steady across commands/reboot/auto-off (no leak).
  ⚠️ Headroom is thin — see "Risks" below.
- _Why unit tests missed it:_ the Node harness mocks the runtime; it can't model the device's mJS heap.
  This is the core value of the hardware pass.

### ✅ Passed (on the minified build)
| Test | Result |
|---|---|
| v2 mJS boots & runs | script running, no crash, `coffee_status` valid, HTTP endpoints registered |
| Boot-OFF gate | `reset_reason=1` (not 3) → booted OFF (relay off), per safety invariant |
| HTTP-direct commands | t90→on/rem90, ext→120, sub→90, off→off; relay tracked via WMI (on/off); bogus→HTTP 400 |
| **Watchdog resume** | ON → `Shelly.Reboot` → `reset_reason=3` → **resumed**: state=on, remaining=90, mode preserved, charger on |
| Persistence | `rt_on=1 / rt_remain=5400 / rt_mode` written on turn-on; cleared (`rt_on=0`) on off/auto-off |
| **Countdown + auto-off** | injected 2-min timer + reboot-resume → counted 2→1→0 over ~120 s → **auto-off**, relay off, `rt_*` cleared |
| **MQTT mTLS → local broker** | provisioned CA+client cert/key via RPC, `Mqtt.SetConfig` to `192.168.x.x:8883` (`use_client_cert`, `topic_prefix=devices/YOUR_DEVICE_ID`) → **connected=True** (broker accepted the `YOUR_DEVICE_ID` cert) |
| **Retained heartbeat** | a fresh subscriber received the retained `devices/<id>/heartbeat` immediately (native retain — no `/get`) |
| **Online LWT** | retained `devices/<id>/online = true` delivered on subscribe |
| **`alive` liveness** | `mon/<id>/alive = 1` received (non-retained, ~60 s) |
| **MQTT command round-trip** | test client published `command t90` → device executed → relay ON (`status/switch:0 apower 19.9 W`, WMI charger True) → fresh heartbeat `s:on, mode:remote, ack:t90` |

| **Power-loss boot-OFF** | device ON (timer running) → physical wall unplug/replug → `reset_reason=1` → **booted OFF, remaining=0, `rt` cleared `{on:0}`, charger off** (did NOT resume — the safety gate vs the reset_reason=3 resume path) |
| **Cloud MQTT round-trip** | client on EMQX cloud (`YOUR_PHONE_ID`/WSS) → published `command t90` → **cloud → bridge → local broker → device → turned ON** (`apower 41 W`) → heartbeat `s:on, ack:t90` mirrored back to cloud. Retained heartbeat + `online` also arrived cloud-side via the bridge. |

MQTT end-to-end was exercised with a Node `mqtt` client on Windows (WSL is LAN-isolated) using the
device cert as test identity (it has ACL on both `devices/<id>/#` and `mon/<id>/#`).

### Memory hardening (rt_* → single KVS key)
Consolidated the three `rt_*` keys into one JSON `rt={on,r,m}` so persistence does **1 KVS write
instead of 3** (lower peak allocation — the original OOM was a write burst). Re-validated on hardware:
HTTP commands, `rt` written as `{"on":1,"r":5400,"m":"remote"}`, resume across reboot, power-loss
boot-OFF — all green; `mem_free` ~2.8 KB (up from ~2.5 KB). Source size unchanged (the win is runtime
peak, not bytes). Unit suite 33/33.

### Observation — one transient firmware panic
During the local-broker provisioning (cert upload + `Mqtt.SetConfig` + reboot + TLS reconnect in quick
succession), one boot came up with `reset_reason=4` (ESP panic). The device **self-recovered and stayed
stable** (uptime climbed, script running, MQTT connected, no boot loop). Read as a transient firmware
hiccup under rapid reconfigure, not a script fault (a script OOM stops the script, it does not panic the
SoC). Worth a glance if it recurs during real provisioning.

### Broker-maintainer feedback (post-test) + analysis
After the session, the broker maintainer reported the local-broker connection had been **unstable**:
a fresh TLS connection ~every 10 s, each logged `Client shelly-coffee already connected, closing old
connection`, interspersed with `unexpected eof while reading`, then offline since the restore (09:37
UTC). Their hypothesis: duplicate `client_id` (built-in MQTT + the mJS script both connecting).

**Analysis (researched + cross-checked):**
- **Not duplicate clients / not a code bug.** Shelly's mJS `MQTT.*` API uses the device's **single
  built-in MQTT connection** — it does not open a second client (Shelly docs + confirmed empirically:
  the *same* `client_id=shelly-coffee` with the same script is **rock-stable on Adafruit** — 80+ min
  uptime, one connection. If the script opened a 2nd connection, Adafruit's one-per-account limit would
  show the same war; it doesn't).
- **`client_id` was `shelly-coffee`** — inherited from the Adafruit config because the local
  `Mqtt.SetConfig` didn't set one. Not the root cause (stable on Adafruit), but it should be set
  explicitly + uniquely for the local broker (fixed in spec 11 §5).
- **Most likely cause: (a) test churn** — the session had many reboots / reconfigs / the 15 KB OOM
  crash / a TLS-reconnect panic, each leaving a half-open socket → exactly the "already connected" +
  "unexpected eof" pattern; **plus (b) a known Shelly firmware issue:** MQTT-over-TLS + a running
  script is reported as unstable on Gen2/3 (incl. Plug S Gen3 with a self-signed CA). The transient
  panic during mTLS provisioning fits this.
- **`mon/<id>/alive` IS implemented and WAS observed** publishing (`= 1`) in both the local-broker and
  cloud round-trip tests — the maintainer saw it absent only because the device was offline/flapping.
- **No retained config is expected** — no v2 controller (phone/web) has published config to the broker yet.

**Next step:** redeploy v2 to the local broker and observe a **quiet** steady-state window (no
reboots) to distinguish residual test-churn from genuine firmware TLS flapping; set an explicit
`client_id`; keep firmware current. (Sources: Shelly MQTT docs; community reports on Gen3 MQTT-TLS +
script instability and Plug S Gen3 self-signed-cert MQTT issues.)

### Quiet-session observation window (running) — started 2026-06-06 12:30 UTC
Redeployed v2 to the local broker with the explicit `client_id=YOUR_DEVICE_ID`, set a **180-min
timer** (relay ON / charger powered), then **left the device untouched** so the broker maintainer can
watch a churn-free window. If it still reconnects ~every 10 s while idle → genuine firmware flapping;
if stable → the earlier flapping was test-session churn. Charger auto-offs ~15:30 UTC.

- **Reproducible panic:** the mTLS provision reboot came up `reset_reason=4` (panic) on **both** v2
  provisions, then self-recovered (uptime climbs, MQTT connects, no boot loop). Reinforces the
  firmware mTLS-stability concern — flag for the firmware-current / known-issue follow-up.

### Broker-maintainer feedback #2 (2026-06-06, post quiet-window) + fixes

Three observations after the device went quiet:

1. **🔴 `status/#` telemetry leaking to cloud — FIXED + VERIFIED.** Provisioning had left
   `status_ntf:true`/`rpc_ntf:true` (spec 11 §5 wrongly specified `true` to obtain the `online` LWT).
   That makes the firmware publish its full `devices/<id>/status/#` tree — `switch:0` power metering
   (apower/voltage/current/temperature) every few seconds — *inside* the bridged `devices/#` subtree,
   so the bridge exported all of it to EMQX: exactly the high-frequency traffic the `mon/` split exists
   to keep off the free-tier quota, leaking via a different subtree. My §9 estimate missed it.
   - **Fix:** `Mqtt.SetConfig {status_ntf:false, rpc_ntf:false}` — took effect **live** (`restart_required=false`,
     no reboot, so no provision panic). The v2 script never used these notifications (it publishes its
     own heartbeat/alive); firmware status tree is unused.
   - **Verified** (local-broker mTLS subscriber, 22 s, relay ON = worst case): `devices/<id>/status/#`
     count **0** (was every few seconds); `online`/heartbeat/`alive` all still flowing. Crucially the
     **`online` LWT survives `status_ntf:false`** — it's independent (this was the risk; now disproven).
   - Spec 11 §4/§5/§9 corrected to `false` with the rationale + the LWT-independence note.

2. **🟡 Phone keepalive churn — app-side, fix in progress.** In ~8 min: 5 connects, 3 keepalive-timeouts,
   1 unexpected eof. With `keepAlive=30`, the backgrounded app didn't PINGREQ within the broker's ~45 s
   grace → dropped → auto-reconnect every ~45–70 s. Cause is Android Doze suspending Paho's ping thread
   when the app is backgrounded with no foreground service (coffee OFF). `cleanSession=false` resumes
   the session so messages survive, but it's noisy and risks QoS0 lag/loss. **Fix:** make the MQTT
   connection lifecycle-aware (drop it when backgrounded + no FG service; reconnect on foreground) — see
   app changes below. Runtime UNVERIFIED until next phone session.

3. **ℹ️ Read-only telemetry note.** `status/sys` still carried `reset_reason:4` (the provision-reboot
   panic, item above) and advertised `2.0.0-beta1`. Reset reason reflects the *last* boot and clears on
   the next clean boot; firmware-version-current is tracked under Risks. No action.

### Risks / follow-ups
- **Thin mJS headroom (~2.5 KB free).** Matches v1's working footprint, but untested allocation spikes
  (schedule-fire JSON, alive+heartbeat in one cycle) could still OOM. Recommended hardening: consolidate
  the 3 `rt_*` KVS keys into one compact value (fewer keys/calls/globals) to widen the margin.
- `scripts/build-device.sh --check` guards against `coffee.min.js` drifting from the source.

### Restore (device returned to daily v1 state)
After testing, the device was restored: re-flashed v1 `coffee.js`, `Mqtt.SetConfig` back to Adafruit
(`io.adafruit.com:8883`, user `<aio-user>`, `topic_prefix=<aio-user>/feeds`,
`use_client_cert=false`), rebooted → **MQTT connected, v1 running, state OFF**, and a fresh heartbeat
confirmed on Adafruit REST (the phone app's remote path works). The uploaded mTLS certs remain on the
device (harmless with `use_client_cert=false`); to deploy v2 for real, just re-flash `coffee.min.js`
and re-point MQTT to the local broker.

### Not yet tested on hardware (for the final pass / when phone is available)
- Schedule fire memory profile (logic covered by unit tests; allocation ≈ a command, which passed).
- Config publish→apply round-trip from a client (retain mechanism proven via heartbeat/online).
- Long-run stability / `alive` cadence over hours; the two watchdog reboot *triggers* (Wi-Fi 15 min,
  broker 3 h) — only the resume-after-`Shelly.Reboot` path was exercised.
- Android app + web fallback against the live broker (phone testing deferred).
