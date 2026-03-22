# Shelly Coffee Maker — Deployment & Operations

## 1. Scope

This document covers the operational lifecycle: how to get the system running, how to maintain it, and how to recover when things go wrong. It is deliberately conceptual — specifics will solidify during implementation and real-world use.

---

## 2. Initial deployment

### 2.1 Adafruit IO

- Create account, create 3 feeds (command, config, heartbeat)
- Note username and AIO key
- Seed the config feed with an initial config JSON (v=1, defaults)
- Validation tests from doc 04 §6 confirm everything works before touching the Shelly

### 2.2 Shelly Plug S Gen3

- Connect to wifi via Shelly's AP mode or web UI
- Configure MQTT via `Mqtt.SetConfig` (doc 04 §4.1)
- Reboot, verify `Mqtt.GetStatus` shows connected
- Upload the mJS script via web UI (Settings → Scripts) or via RPC (`Script.Create` + `Script.PutCode` + `Script.Start` + `Script.SetConfig`)
- Enable "run on startup" (or set `enable: true` via `Script.SetConfig`)
- Test with a manual command from curl or the phone

### 2.3 Phone (Android app)

- Build APK in Android Studio, or download from GitHub Actions artifacts / Releases
- Sideload onto phone(s)
- Grant notification permission when prompted (for the "Coffee ON" notification)
- Enter Shelly IP, AIO username, AIO key in settings
- Verify status shows up, buttons work

### 2.4 HTML fallback

- Host on GitHub Pages or similar
- Configure AIO credentials
- Bookmark on any device that needs remote access without the app

---

## 3. Updating the mJS script

The script lives on the Shelly. Two methods for updating:

**Method A: Web UI (manual).** Paste into the Shelly web UI (Settings → Scripts), save, restart. The web UI provides a console log for debugging.

**Method B: RPC upload (scriptable).** Use `Script.Stop` + `Script.PutCode` + `Script.Start` via HTTP RPC calls. Large scripts need chunked upload with `append: true`. See `docs/testing/AI-TEST-GUIDE.md` §4 for the full procedure. Remember to replace `YOUR_AIO_USERNAME` in the script before uploading.

**Version tracking:** The git repo is the source of truth; the device has the running copy.

**Rollback:** The Shelly doesn't version scripts. If an update breaks things, upload the previous version from git. Worst case, the plug is off and safe — a broken script can't leave the coffee maker on because the switch defaults to off on script restart.

**CI/CD:** The APK is built automatically on push to `main` (GitHub Actions). Tagged commits create GitHub Releases with the APK attached. The web control page is deployed to GitHub Pages automatically. There is no automated device script deployment — the Shelly is a single device, not a fleet.

---

## 4. Wifi and network changes

### 4.1 Moving house

The Shelly needs to be reconfigured for the new wifi network. This requires physical access (AP mode or temporary connection to old network if available).

**Rough process:**
1. Power up the Shelly — if it can't find the configured wifi, it opens its own AP after a timeout
2. Connect to the Shelly's AP from a phone/laptop
3. Configure new wifi credentials via the web UI
4. MQTT settings (Adafruit IO) are unchanged — they persist across wifi changes
5. Update the Shelly's local IP in the Android app settings (new DHCP reservation on new router)

### 4.2 IP address change

If the Shelly's local IP changes (new router, DHCP lease expired), the Android app's local auto-detect will fail and fall back to remote. Update the IP in the app settings. Consider a DHCP reservation on the router to prevent this.

### 4.3 Internet provider change

No impact on the system. The Shelly connects to Adafruit IO via whatever internet path is available. No port forwarding, no DNS entries, no static public IP required.

---

## 5. Credential management

### 5.1 Adafruit IO key rotation

If the AIO key needs to be regenerated (compromised, routine rotation):

1. Generate new key on Adafruit IO website
2. Update the Android app settings and HTML fallback with the new key
3. Update the Shelly via local HTTP: `Mqtt.SetConfig` with new `pass` field, then reboot
4. Step 3 requires being on the same wifi as the Shelly — there is no remote credential update path

This is an accepted limitation of the zero-infrastructure design (doc 04 decision D04.35).

### 5.2 Shelly device auth

The Shelly's local HTTP API can optionally be password-protected via `Shelly.SetAuth`. Currently not used (local network is trusted). Could be added if the threat model changes.

---

## 6. Monitoring and troubleshooting

### 6.1 "Is it working?" checks

- **Heartbeat age:** The phone shows "last updated" timestamp. If this is more than 15 minutes old while the device should be online, something is wrong.
- **MQTT status:** `curl -X POST -d '{"id":1,"method":"Mqtt.GetStatus"}' http://{shelly_ip}/rpc` — check `connected: true`.
- **Script running:** The Shelly web UI shows script status (running/stopped) and console output.
- **Adafruit IO monitor page:** Shows connection status, rate limit usage, ban history.

### 6.2 Common failure scenarios

| Symptom | Likely cause | Check | Fix |
|---|---|---|---|
| Phone shows stale heartbeat | Shelly offline or MQTT disconnected | Check Shelly web UI, MQTT status | Verify wifi, reboot Shelly |
| Commands not acknowledged | MQTT down, or command stale (clock skew) | Check heartbeat `ack` field | Verify MQTT connected, check NTP |
| Schedule didn't fire | NTP not synced, or schedule not armed | Check heartbeat `ntp` and `sch` fields | Verify internet for NTP, re-arm schedule |
| App shows "Offline" | Shelly IP changed, or wifi/internet down | Try accessing Shelly web UI directly | Check IP, check network |
| Plug turned off unexpectedly | Timer expired (correct behavior) | Check heartbeat history on Adafruit IO | This is normal — extend next time |
| Script stopped | mJS crash or manual stop | Shelly web UI → Scripts | Restart script, check console for errors |

### 6.3 Nuclear option

If everything is confused and state is inconsistent:

1. Reboot the Shelly (power cycle or `Shelly.Reboot`)
2. It boots off (safe), loads config from KVS, reconnects to MQTT, fetches latest config
3. Publish a fresh config from the phone to reset the schedule to known state
4. Verify with a status check

The system is designed so that rebooting always produces a safe, known state.

---

## 7. Backup and disaster recovery

### 7.1 What to back up

| Item | Location | Backup method |
|---|---|---|
| mJS script source | Git repo | Git (already version controlled) |
| Adafruit IO credentials | Phone app, Shelly config | Password manager or secure notes |
| Shelly MQTT config | On-device | Documented in doc 04 (reproducible from credentials) |
| Android app source | Git repo | Git |
| Current config state | Adafruit IO config feed | Always readable via REST; also cached in Shelly KVS |

### 7.2 Total loss recovery

If the Shelly is factory reset or replaced:

1. Connect to wifi
2. Apply MQTT config from doc 04
3. Upload script from git repo
4. The device fetches config from Adafruit IO on first MQTT connect
5. Done — operational within minutes

If Adafruit IO account is lost:

1. Create new account, new feeds
2. Update credentials on Shelly and phone
3. Seed config feed with desired config
4. Done — the device runs on KVS cache until it connects to the new broker

---

## 8. Future considerations

Not planned, but noted for if/when they become relevant:

- **OTA script updates** — the Shelly supports firmware OTA but not script OTA via the same mechanism. Script updates remain manual via web UI.
- **Multiple devices** — the current architecture is single-device. Multiple plugs would need separate feeds or a topic hierarchy.
- **Alerting** — Adafruit IO's paid tier supports email/SMS triggers on feed values. Could alert if the coffee maker has been on for an unusual duration.
- **Power monitoring** — the Shelly Plug S Gen3 has power metering. Could expose wattage in the heartbeat for "is the coffee maker actually drawing power or is it plugged in but empty."
- **Recurring schedules** — currently one-shot only. A recurring schedule could be implemented as a phone-side job that re-arms the schedule daily, keeping the device-side logic simple.

---

## 9. Decisions made

| # | Decision | Rationale |
|---|---|---|
| D07.56 | Script updates are manual (web UI paste from git repo) | Single device, no fleet; automated deployment adds complexity for no gain |
| D07.57 | No device authentication on local HTTP API (for now) | Local network is trusted; can add `Shelly.SetAuth` later if needed |
| D07.58 | Reboot is the universal recovery action | System designed so reboot always produces a safe, known state |
