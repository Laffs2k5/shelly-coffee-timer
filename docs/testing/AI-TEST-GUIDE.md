# AI Test Guide — Shelly Coffee Timer (v2, local-MQTT)

Test prompts for an AI agent. **v2 transport** = local Mosquitto (mTLS) bridged to EMQX Cloud — see
[../spec/11-local-mqtt.md](../spec/11-local-mqtt.md). The Adafruit IO path is gone.

> **Two tiers of testing:**
> - **§0 Automated unit tests — runnable now, no hardware.** This is what's actually verified today.
> - **§1+ Hardware/runtime tests — for final validation** (need the plug, broker, and clients). These
>   are written but **not yet executed against v2 hardware**; revisit and run them in the final pass.

---

## 0. Automated unit tests (no hardware) ✅

The device mJS logic and the web fallback's pure logic are unit-tested in Node against a mocked Shelly
runtime — no device, broker, or network needed.

```bash
scripts/test-device.sh          # or: node --test 'device/test/*.test.js' 'web/test/*.test.js'
```

Expect **33 tests passing** (25 device + 8 web). Coverage:
- **Device** ([device/test/coffee.test.js](../../device/test/coffee.test.js)): topics/retain (heartbeat
  retain=true, no `/get`), `mon/<id>/alive` (non-retained, NTP-independent, 60 s), commands
  (t90/ext/sub/off), 180-min cap, staleness rejection, config version gating, schedule fire+disarm,
  **reset-reason resume gate** (resume iff `reset_reason==3`, else boot OFF + clear `rt_*`), `rt_*`
  persistence (and no churn while OFF), **two-tier watchdog** (Wi-Fi 900 s / broker 10800 s; nightly
  Pi blip does NOT reboot), physical-button handling.
- **Web** ([web/test/coffee-core.test.js](../../web/test/coffee-core.test.js)): topics, command/config
  payloads, version bump, heartbeat parse, broker URL.

Also build the Android app (compile-level verification, Windows toolchain):
```
# sync repo app/ -> Windows build project, then on Windows (pwsh):
.\gradlew.bat assembleDebug --no-daemon
```
Expect **BUILD SUCCESSFUL**. (See [../archive/IMPLEMENTATION.md](../archive/IMPLEMENTATION.md) for the full build steps.)

---

## Prerequisites (hardware tests, §1+)

1. **Device:** Shelly Plug S Gen3, v2 `coffee.js` flashed, MQTT pointed at the local broker (mTLS) via
   RPC (see spec 11 §5). Verify HTTP-direct: `curl -s http://<SHELLY_IP>/script/1/coffee_status` → JSON.
2. **Broker:** local Mosquitto (`192.168.x.x:8883`, mTLS) up; bridge to EMQX healthy.
3. **MQTT test client:** `mosquitto_pub`/`mosquitto_sub` with the CA + a client cert (or the cloud
   user/pass against EMQX over `:8884`/`:8883`). Topics under `devices/YOUR_DEVICE_ID/`.
4. **Safety:** the plug may power real load — restore switch OFF and config to defaults
   (dur=90, max=180, sch=0) after testing.

> Note: unlike Adafruit (single MQTT connection per account), the local broker + EMQX allow multiple
> connections, so a test client can connect alongside the device.

---

## 1. Local HTTP (route A — unchanged, always-on path)

`coffee_status` / `coffee_command` on port 80 are identical to v1 and independent of the broker.

```
curl -s http://<SHELLY_IP>/script/1/coffee_status      # state, remaining, mode, sch, h, m, ntp, ts
curl -s "http://<SHELLY_IP>/script/1/coffee_command?cmd=t90"   # ok=true, state=on, remaining=90, ack=t90
curl -s "http://<SHELLY_IP>/script/1/coffee_command?cmd=ext"   # remaining ~120
curl -s "http://<SHELLY_IP>/script/1/coffee_command?cmd=sub"   # remaining ~90
curl -s "http://<SHELLY_IP>/script/1/coffee_command?cmd=off"   # state=off
curl -s "http://<SHELLY_IP>/script/1/coffee_command?cmd=bogus" # 400, error="unknown command"
curl -s "http://<SHELLY_IP>/script/1/coffee_command"           # 400, error="missing cmd"
```

## 2. MQTT command + heartbeat (route B/C — new topics, native retain)

Publish a command (retain=false) and read the retained heartbeat. Example with a client cert against
the local broker (substitute your cert paths / or use cloud user/pass against EMQX):

```
TS=$(date +%s)
mosquitto_pub --cafile ca.crt --cert YOUR_PHONE_ID.crt --key YOUR_PHONE_ID.key \
  -h 192.168.x.x -p 8883 -t devices/YOUR_DEVICE_ID/command \
  -m "{\"c\":\"t90\",\"ts\":${TS}}"            # NOTE: retain MUST be false (default)

# Retained heartbeat = the app's "last seen"; -C 1 grabs the retained value and exits:
mosquitto_sub --cafile ca.crt --cert YOUR_PHONE_ID.crt --key YOUR_PHONE_ID.key \
  -h 192.168.x.x -p 8883 -t devices/YOUR_DEVICE_ID/heartbeat -C 1
# Verify JSON: s="on", r~90, mode="remote", ack="t90", ntp=true, recent ts
```

- **Config (retain=true):** publish to `devices/YOUR_DEVICE_ID/config` with a bumped `v`; the device
  applies it and a (re)subscribing client receives the latest immediately (no `/get`).
- **Staleness:** publish a command with `ts` > 120 s old → device ignores it (state unchanged).
- **Config gating:** publish config with `v` ≤ current → ignored.

## 3. Monitoring signals (infra — emit-only)

- **`mon/YOUR_DEVICE_ID/alive`** — non-retained, ~every 60 s, payload irrelevant. Subscribe and
  confirm it ticks. (This is the monitor's liveness signal, NOT the app heartbeat.)
- **`devices/YOUR_DEVICE_ID/online`** — Shelly built-in LWT (retained); `true` when connected,
  `false`/empty on ungraceful drop.

## 4. Watchdog + state-resume (the v2 safety feature — see [../spec/12-watchdog-validation.md](../spec/12-watchdog-validation.md))

Reboot-reason was already validated (reset_reason 3=software/resume, 1=power/OFF). Full-feature tests:

- **Resume on watchdog reboot:** turn on (e.g. t90), wait ~1 min, then `Shelly.Reboot` (RPC). On boot:
  switch returns ON, `remaining` ≈ pre-reboot minus reboot time, `mode` preserved.
- **OFF on power loss:** turn on, then physically power-cycle the plug. On boot: switch OFF, `rt_*` cleared.
- **Wi-Fi watchdog:** disconnect Wi-Fi ≥ 15 min → device reboots (then resumes per above).
- **Broker watchdog:** kill the broker (Wi-Fi up) ≥ 3 h → device reboots. The nightly Pi maintenance
  window must NOT trigger it (gap < 3 h).

## 5. Relay verification (laptop charger through the plug)

**This dev box: WSL2 is isolated from the LAN — use the Windows power state, not the WSL sysfs path.**

```powershell
# PowerShell: True when the charger (relay) is on
[bool](Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus).PowerOnline
```
It tracks the relay 1:1 and shows the ~8 s drop during a reboot (so it detects on / off / **rebooting**).
The old `/sys/class/power_supply/AC1/online` only works where Linux can see the AC adapter directly.

## 6. Script upload (chunked PutCode)

```
# Stop, then PutCode in <=1.5 KB chunks (first append=false, rest append=true), Start, enable auto-start:
curl -s -X POST -d '{"id":1,"method":"Script.Stop","params":{"id":1}}'   http://<SHELLY_IP>/rpc
curl -s -X POST -d '{"id":1,"method":"Script.PutCode","params":{"id":1,"code":"<CHUNK1>"}}'             http://<SHELLY_IP>/rpc
curl -s -X POST -d '{"id":1,"method":"Script.PutCode","params":{"id":1,"code":"<CHUNK2>","append":true}}' http://<SHELLY_IP>/rpc
curl -s -X POST -d '{"id":1,"method":"Script.Start","params":{"id":1}}'  http://<SHELLY_IP>/rpc
curl -s -X POST -d '{"id":1,"method":"Script.SetConfig","params":{"id":1,"config":{"enable":true}}}' http://<SHELLY_IP>/rpc
```
After upload: on a power-on boot the switch is OFF (safety); `coffee_status` responds. Then run §1–§4.
The MQTT transport (broker, mTLS cert, `topic_prefix`) is configured separately via RPC (spec 11 §5).
