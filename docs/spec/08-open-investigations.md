# Shelly Coffee Maker — Open Investigations & Risk Items

## 1. Purpose

This document collects unresolved questions, implementation risks, and required validation work identified during the architecture review (performed after docs 00–07 were complete). Each item must be investigated and resolved before or during implementation. Items are ordered by risk — highest first.

---

## 2. Investigations

### 2.1 [HIGH RISK] Shelly.addRPCHandler API — does it exist as assumed?

**The problem:** Doc 05 §6 designs the local HTTP control path around custom RPC handlers:

```javascript
Shelly.addRPCHandler("Coffee.Command", function(params) { ... })
Shelly.addRPCHandler("Coffee.Status", function(params) { ... })
```

These would make the Shelly respond to `GET /rpc/Coffee.Command?cmd=t90` and `GET /rpc/Coffee.Status`. This is the entire local control path — without it, the Android app cannot talk to the Shelly directly on the same wifi.

**Why it's a risk:** The Shelly Gen2+ scripting docs mention `Shelly.call()` for invoking built-in RPC methods from scripts, but `Shelly.addRPCHandler()` for *registering new custom RPC endpoints* is less clearly documented. If this API doesn't exist, doesn't work as assumed, or has limitations (method name format restrictions, response size limits, parameter passing), the local HTTP path needs a completely different approach.

**Alternatives if it doesn't work:**
- Use `HTTPServer.registerEndpoint()` if the mJS API exposes a raw HTTP server
- Abuse an existing Shelly webhook or script endpoint to tunnel custom requests
- Expose state via a KVS key that can be read via the built-in `KVS.Get` RPC, and commands via `KVS.Set` + a polling script (ugly but functional)
- Fall back to remote-only control (Adafruit IO REST), giving up local control entirely

**Resolution:** Test on the actual device. Try registering a minimal RPC handler and calling it via HTTP from a browser. This is the single most important validation item — do it before writing the full script.

**Status:** TBD

---

### 2.2 [HIGH RISK] Timezone-aware local time in mJS

**The problem:** Doc 05 §4.7 (schedule checker) needs the current local hour and minute to compare against the scheduled time:

```
if now.hour === cfg_h and now.minute === cfg_m:
    // fire schedule
```

The schedule is set in local time (the user means "06:10 in my timezone"). The Shelly has a timezone configured via `Sys.SetConfig`. But the exact mJS API for getting the current local hour and minute is unclear.

**What we know:**
- `Shelly.getComponentStatus("sys").unixtime` gives Unix epoch seconds (UTC)
- The Shelly firmware knows the configured timezone (set via `location.tz` in `Sys.SetConfig`)
- Some firmware versions expose a `time` field in `Sys.GetStatus` that may contain local time
- mJS has no `Date` object, no `toLocaleTimeString()`, no timezone conversion functions

**If the firmware doesn't provide local time directly**, we'd need to:
- Store a UTC offset in config (phone calculates it and sends it)
- Compute local hour/minute from `unixtime + offset` in mJS
- Handle DST transitions (the phone would need to push a new config with updated offset when DST changes)

This adds complexity and a DST footgun. Much better if the firmware provides local time natively.

**Resolution:** On the actual device, check `Shelly.getComponentStatus("sys")` output after configuring a timezone. Look for fields like `time`, `local_time`, or similar. Also check the Shelly scripting API docs for any time-related helper functions.

**Status:** TBD

---

### 2.3 [MEDIUM RISK] First-ever boot with empty Adafruit IO feeds

**The problem:** No doc describes the complete flow when the system is deployed for the very first time — before anyone has ever written to the config, command, or heartbeat feeds.

**What happens step by step:**

1. Shelly boots, loads KVS — all keys are missing, defaults apply (v=0, sch=0, h=6, m=0, dur=90, max=180)
2. Shelly connects to MQTT, subscribes to command and config
3. Shelly publishes to `config/get` — Adafruit IO has no data for this feed
4. The `/get` response is either: empty string, null, or no message at all
5. `on_mqtt_config` receives this, `JSON.parse` fails → silently ignored
6. Device continues on KVS defaults — this is correct and safe
7. Device publishes a heartbeat with default values
8. Phone app reads heartbeat — sees defaults, shows "OFF"
9. Phone posts initial config (v=1, desired schedule) → device receives, accepts (1 > 0), persists

**This flow is actually fine** — the design's fallback-to-defaults behavior handles it gracefully. But it should be documented explicitly so there's no surprise during first deployment.

**Resolution needed:** Verify empirically that Adafruit IO's `/get` response for an empty feed doesn't crash the mJS JSON parser or cause unexpected behavior. Add a note to doc 07 §2.1 documenting the expected first-boot sequence.

**Status:** TBD

---

### 2.4 [MEDIUM RISK] App shows stale remote status after sending a command

**The problem:** When the Android app sends a command via the remote path (Adafruit IO), there's a propagation delay:

1. App POSTs command to Adafruit IO REST
2. Adafruit IO delivers via MQTT to the device
3. Device processes command, publishes heartbeat
4. Heartbeat appears in Adafruit IO database
5. App polls heartbeat on next 10-second cycle

Steps 2–4 take somewhere between 500ms and several seconds. If the app polls immediately after sending the command (as doc 06 §3.3 specifies), the heartbeat may not yet reflect the new state.

**The user experience:** User taps "+30", the status still shows "OFF" for a few seconds, then updates to "ON with 30 min to go" on the next poll. This is confusing — did the command work?

**Possible mitigations:**
- **Optimistic UI update:** After sending a remote command, immediately show the expected new state (with a "pending" indicator), then verify on next poll. If the heartbeat doesn't match within 30 seconds, show a warning.
- **Rapid polling burst:** After sending a command, poll every 2 seconds for 10 seconds instead of waiting for the normal 10-second cycle.
- **Accept the lag:** Show "Command sent, waiting for confirmation..." and let the normal 10-second poll catch it. Simple, honest, no complex state.

**No change needed to the device or Adafruit IO** — this is purely a phone-side UX decision.

**Resolution:** Design decision needed during Android app implementation. The rapid-polling-burst approach is probably the best balance of simplicity and UX.

**Status:** TBD — decide during app development

---

### 2.5 [LOW RISK] Config version race condition with multiple phones

**The problem:** The config version (`v`) is managed by read-increment-write on the phone (doc 06 §3.4). If two phones simultaneously:

1. Phone A reads config: v=3, sch=0, h=6, m=10
2. Phone B reads config: v=3, sch=0, h=6, m=10
3. Phone A writes: v=4, sch=1, h=6, m=10 (enables schedule)
4. Phone B writes: v=4, sch=0, h=7, m=0 (changes time)

The device receives both. It accepts the first one (v=4 > v=3) and ignores the second (v=4 is not > v=4). Phone B's change is silently lost.

**Why it's low risk:** This is a single-user project with one or two phones. Simultaneous config writes are extremely unlikely — you'd have to be actively changing the schedule on two phones within seconds of each other. And even if it happens, the worst outcome is "the schedule time didn't change" — the user would notice and try again.

**Resolution:** Document this as a known limitation. No fix needed for a single-device project. If it ever becomes a real problem, the fix is to have the phone read-before-write with a short retry loop if the version changed between read and write (compare-and-swap pattern).

**Status:** Accepted limitation — documented here

---

## 3. Cleanup items (non-blocking)

### 3.1 Decision numbering consolidation

Decisions are numbered sequentially within each doc but collide across docs: docs 00–03 use 1–26, doc 04 uses 27–35, doc 05 uses 36–46, doc 06 uses 36a–44a, doc 07 uses 45a–47a. A single-pass renumbering across all docs should be done before implementation, or (simpler) adopt a prefix scheme: `D00.1`, `D02.7`, `D05.36`, etc.

### 3.2 Doc 00 open questions — audit for answered items

Many of doc 00's open questions (§5) have been answered by later docs. A pass through that list to mark items as resolved (with cross-references) would keep the document useful as a historical record rather than confusing as an outdated checklist.

---

## 4. Summary of required actions before implementation

| # | Item | Risk | Blocks |
|---|---|---|---|
| 2.1 | Test `Shelly.addRPCHandler()` on device | High | Local HTTP control path (doc 05 §6, doc 06 §4) |
| 2.2 | Test timezone-aware local time API | High | Schedule checker (doc 05 §4.7) |
| 2.3 | Test `/get` response on empty feed | Medium | First deployment (doc 07 §2) |
| 2.4 | Design post-command UX for remote path | Medium | Android app implementation (doc 06) |
| 2.5 | Multi-phone config race | Low | Nothing — accepted limitation |
| 3.1 | Decision renumbering | None | Nothing — cosmetic |
| 3.2 | Doc 00 open questions audit | None | Nothing — housekeeping |
