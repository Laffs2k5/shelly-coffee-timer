# Manual Regression Checklist — Shelly Coffee Timer (v2, local-MQTT)

Use before releases, after script changes, or after config updates. **v2 transport** = local Mosquitto
(mTLS) bridged to EMQX Cloud (see [../spec/11-local-mqtt.md](../spec/11-local-mqtt.md)); Adafruit IO is gone.

> Status: the **Automated** section runs today (no hardware). The **hardware/runtime** sections are
> written for v2 but **not yet executed against v2 hardware** — run them in the final validation pass.

---

## Automated (no hardware) — run first

- [ ] `scripts/test-device.sh` → **33 tests pass** (25 device logic + 8 web logic)
- [ ] Android `assembleDebug` → **BUILD SUCCESSFUL** (Windows toolchain; see archive/IMPLEMENTATION.md)

---

## Device

### Physical button
- [ ] Press while off: plug turns on, timer starts at default duration, mode=manual
- [ ] Press while on: plug turns off, timer clears

### MQTT commands (via local broker or cloud → bridge)
- [ ] `t90` with current timestamp: on, remaining=90, mode=remote
- [ ] `ext` / `sub` while on: remaining ±30
- [ ] `off` while on: off
- [ ] Stale command (ts > 2 min old): ignored
- [ ] command published with **retain=false**; config with **retain=true**

### Local HTTP (route A — hard req, broker-independent)
- [ ] `coffee_status` returns all fields (state, remaining, mode, sch, h, m, ntp, ts)
- [ ] `coffee_command?cmd=t90|ext|sub|off` work; `cmd=bogus` → 400; missing cmd → 400
- [ ] Works even while the broker/Pi is down

### Schedule
- [ ] Arm via retained config (sch=1, h, m ~2 min out); fires at time; mode=sch; auto-disarms (sch=0)
- [ ] Does not fire if sch=0

### Topics / retain / monitoring
- [ ] `heartbeat` is **retained** (a fresh subscriber gets last value immediately; no `/get` used)
- [ ] `config` is **retained** (device gets latest on reconnect)
- [ ] `mon/<id>/alive` published **non-retained** ~every 60 s, NTP-independent
- [ ] `devices/<id>/online` LWT present (retained) — true on connect

### Watchdog + state-resume (v2)
- [ ] **Resume:** ON + `Shelly.Reboot` → boots ON, remaining preserved (reset_reason=3)
- [ ] **Safety:** ON + power-cycle → boots OFF, `rt_*` cleared (reset_reason=1)
- [ ] Wi-Fi down ≥ 15 min → reboots; broker down (Wi-Fi up) ≥ 3 h → reboots
- [ ] Nightly Pi maintenance (broker down briefly) does NOT reboot the plug
- [ ] Config preserved across reboot (KVS); MQTT reconnects after reboot

### Heartbeat
- [ ] Published on state change / command ack; periodic ~5 min (on) / ~15 min (off)
- [ ] Fields: s, r, mode, sch, h, m, ack, ts, ntp

### Config gating
- [ ] Higher version applied; same/lower ignored; dur/max changes take effect; persists across reboot

### Relay verification (laptop charger through plug — this dev box)
- [ ] `[bool](Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus).PowerOnline` = True when on, False when off
  (Windows WMI — WSL2 is LAN-isolated here, so the old `/sys/.../AC1/online` path does not apply)

---

## Android App

### Settings persistence
- [ ] Shelly IP, **Cloud MQTT username/password**, **local broker host**, **.p12 password** persist across restart
- [ ] **Import client certificate (.p12)** stores the identity; status shows "client.p12 imported"

### Transport (broker list: local mTLS → cloud)
- [ ] On Wi-Fi with broker up: connects to local broker (mTLS) — `connectedVia=LOCAL`
- [ ] Off-LAN: falls back to cloud (user/pass over WSS) — `connectedVia=CLOUD`
- [ ] HTTP-direct still works on Wi-Fi (route A), broker up or down
- [ ] Stable clientId `YOUR_PHONE_ID`, persistent session (does not fight the web fallback's id)

### Control + status
- [ ] Status card refreshes; connection footer shows Wi-Fi / Internet / Offline
- [ ] Timer buttons (90, +30, -30, 0) work via the active path
- [ ] Schedule toggle + time picker post retained config with incremented version; heartbeat reflects it

### Notification service
- [ ] Coffee ON → persistent notification ("Coffee ON -- N min remaining"); countdown updates
- [ ] Coffee OFF → notification clears, service stops; survives app kill while ON
- [ ] Schedule alarm fires → service starts; "Connection lost" after ~5 min without contact

---

## Web fallback (web/index.html) — cloud-only escape hatch

- [ ] Prompts for **cloud MQTT username/password** (+ optional host); stored in localStorage, never hard-coded
- [ ] Connects to EMQX over WSS (`wss://host:8084/mqtt`); status from retained heartbeat
- [ ] Timer buttons publish `command`; schedule publishes retained `config` with bumped version
- [ ] Cannot do local control (HTTPS page → LAN HTTP/mTLS blocked) — expected, remote-only

---

## Cross-platform / cleanup
- [ ] Command from app visible in web heartbeat and vice-versa; schedule changes propagate
- [ ] Switch OFF, schedule disarmed (sch=0), config defaults (dur=90, max=180), no stuck state
