# Shelly Coffee Maker — Specification Index

> This index and the numbered documents live in `docs/spec/`. These are the specification documents — the blueprint used to design and build the system. For operational and development documentation, see `docs/`.

## Project status

**v2 (local MQTT + cloud bridge + connectivity watchdog) is the current system, validated on hardware (2026-06-06).** Device script, Android app, and web fallback are all implemented and hardware-verified — see docs **11** (design), **12** (watchdog/reset-reason validation), and [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md). The build/validation record is archived in [`docs/archive/IMPLEMENTATION.md`](../archive/IMPLEMENTATION.md) and [`docs/archive/HW-VALIDATION-v2.md`](../archive/HW-VALIDATION-v2.md).

**v1 (Adafruit IO)** was the prior release (frozen at the `adafruit-final` tag). Its transport is superseded by doc 11; its Adafruit setup spec is archived at [`docs/archive/04-adafruit-io.md`](../archive/04-adafruit-io.md).

### v1 vs v2 — what still applies

Docs **00–10** are the original v1 design. For v2, the **transport** parts are superseded by doc 11:
- **00** (service selection → Adafruit), **02** (Adafruit feed model), **04** (Adafruit setup — *archived*).

The rest remains the design basis for v2, with v2 deltas noted in 11/12: **01** (requirements), **03** (message format — *updated for v2: heartbeat/status now carry `v`/`dur`/`max`*), **05** (state machine), **06** (phone interface), **07** (deployment), **08–10**.

## Project summary

A smart plug (Shelly Plug S Gen3) controlling a coffee maker. Every on-state is a countdown timer — no "on indefinitely." The device operates autonomously with a connectivity watchdog, local-first control (physical button + local HTTP over Wi-Fi), and remote access via a local MQTT broker (mTLS) bridged to the cloud. Controlled from an Android app or web browser.

---

## Documents

### 00 — Landscape Exploration & Initial Research
`00-landscape.md`

What exists and what's possible. Covers: device hardware and capabilities, scripting environment (mJS), communication options (MQTT, REST, webhooks), Shelly Cloud evaluation (excluded), third-party service candidates (Adafruit IO selected), protocol comparison (MQTT vs REST). Establishes the core design constraints and principles.

**Key decisions:** No home server (#1), Shelly Cloud excluded (#2), MQTT for device (#3), REST for phone (#4), Adafruit IO selected (#5), autonomous operation (#6).

---

### 01 — Functional Requirements
`01-requirements.md`

What the plug does. Covers: three control paths (physical button, local HTTP, remote MQTT), timer rules (every on-state is a countdown, 180-min cap), schedule behavior (one-off, auto-disarm), safety requirements (power-loss → off, staleness check, NTP dependency), and the autonomous behavior matrix.

**Key content:** Timer arithmetic (§3), schedule fire-and-disarm logic (§4), safety invariants (§5), NTP rules (§5.4), staleness window (§5.5).

---

### 02 — Communication Architecture
`02-communication.md`

How the pieces talk. Covers: remote path (Shelly ↔ Adafruit IO ↔ phone via MQTT+REST), local path (phone → Shelly direct HTTP), three-feed structure (command, config, heartbeat), data flow scenarios, authority model, feed mapping, message budget analysis, and failure modes.

**Key content:** Feed properties and direction (§2), seven data flow diagrams (§3), authority table (§4), failure mode matrix (§7).

**Note:** Retain references updated to reflect Adafruit IO's `/get` workaround (see doc 04).

---

### 03 — Message Format Design
`03-message-format.md`

What the bytes look like. Covers: JSON encoding for all three feeds, command codes (`on`, `off`, `ext`, `sub`, `t90`), config payload (versioned, with `dur` and `max`), heartbeat payload, local HTTP API (endpoints and responses), Adafruit IO-specific formatting (value envelope), and encoding rationale (why JSON, why flat, why short keys).

**Key content:** Command format with timestamp (§2.1), config version field (§3.2), heartbeat fields (§4.1), local vs remote key name mapping (§2.3), message budget verification (§5).

---

### 04 — Adafruit IO Setup & Validation — ⚠️ ARCHIVED (v1)
[`../archive/04-adafruit-io.md`](../archive/04-adafruit-io.md)

The Adafruit IO setup/validation spec. **Superseded by doc 11** for v2 (local MQTT broker with native
retain — no `/get` workaround, no rate limit). Kept for v1 history. Covered account setup, free-tier
limits, the no-retain `/get` workaround, `Mqtt.SetConfig`, topic format, and a 6-step validation plan.

---

### 05 — On-Device State Machine
`05-state-machine.md`

The mJS brain. Covers: state model (in-memory vs KVS-persisted), 10-step boot sequence, all event handlers (button, MQTT command, MQTT config, MQTT connect, timer tick, heartbeat, schedule check), core functions (turn_on, turn_off, execute_command, publish_heartbeat), local HTTP endpoints (`HTTPServer.registerEndpoint()`), NTP sync detection, timer precision model, heartbeat publishing strategy, config processing, mJS implementation considerations, state transition diagram, and three complete event flow examples.

**Key content:** Boot sequence with sequential KVS loading (§3), command execution shared by MQTT and HTTP paths (§5.3), heartbeat debounce (§9.3), mJS pitfalls (§11), state transition diagram (§12).

**Implementation notes:** Several spec assumptions were corrected during Phase 2 — see doc 08 §4 for lessons learned (timer limits, call concurrency, event feedback loops).

---

### 06 — Phone Control Interface
`06-phone-interface.md`

How the human interacts. Covers: requirements (live status, instant controls, schedule config, auto-detect local/remote), technology evaluation (why CORS kills pure web → native Android app), Kotlin/Compose app design, UI layout matching mockup, auto-detect logic (local-first with 2s timeout), command routing (local vs remote), schedule changes (always via Adafruit IO), app configuration, HTML fallback for computers, and data flow diagrams for all operations.

**Key content:** CORS analysis driving the native app decision (§2.1), auto-detect algorithm (§4.1), schedule change flow with config versioning (§3.4, §7.3), native Android TimePickerDialog for schedule time.

---

### 07 — Deployment & Operations
`07-deployment.md`

Keeping it running. Covers: initial deployment steps, script update process (manual via web UI, version-controlled in git), wifi/network changes (house move, IP change), credential management (AIO key rotation requires local wifi access), monitoring and troubleshooting checklist, common failure scenarios with fixes, nuclear recovery option, backup strategy, total-loss recovery, and future considerations.

**Key content:** Troubleshooting table (§6.2), total-loss recovery procedure (§7.2), future ideas list (§8).

---

### 08 — Open Investigations & Risk Items
`08-open-investigations.md`

What was validated before and during implementation. Five items ordered by risk:

1. ~~**[HIGH]** `Shelly.addRPCHandler()`~~ — RESOLVED: does not exist. Use `HTTPServer.registerEndpoint()`.
2. ~~**[HIGH]** Timezone-aware local time in mJS~~ — RESOLVED: `new Date().getHours()/getMinutes()` works, DST-aware.
3. ~~**[MEDIUM]** First-ever boot with empty feeds~~ — RESOLVED: `/get` on empty feed returns non-JSON, script handles gracefully.
4. **[MEDIUM]** Stale remote status after command — UX decision for the Android app.
5. **[LOW]** Multi-phone config version race — accepted limitation, documented.

Plus two cleanup items (decision renumbering, doc 00 audit) deferred to Phase 5.

Also includes Phase 2 implementation lessons (§4): timer limits, call concurrency, event feedback loops, and other mJS gotchas discovered during development.

---

### 09 — Phase Plan
`09-phase-plan.md`

How to get from docs to a working system. Six phases, all complete: prove the unknowns (Phase 1), build the device side (Phase 2), build the phone side (Phase 3), UI polish with notification service (Phase 4), testing and quality (Phase 5), CI/CD pipeline (Phase 6). Each phase has a gate with explicit pass criteria.

**Key content:** Phase 1 blockers (§ tasks 1.1, 1.2), stage 2B incremental build order with 13 test steps, stage 3A app build order with 12 test steps, Phase 4B notification service architecture, Phase 5 test scripts and docs, Phase 6 GitHub Actions workflows.

---

### 10 — Repository Specification
`10-repo-spec.md`

Single **public** GitHub repo structure. Covers: directory layout (`docs/`, `device/`, `app/`, `web/`, `scripts/`), file purposes, doc filename mapping (long authoring names → short repo names), `.gitignore`, branching approach (commit to main, branches for experiments), credentials handling (gitignored `.env` file, never committed), and the setup sequence from fresh clone to working system.

**Key content:** Directory tree (§2), credentials handling rules (§7), setup sequence (§8).

---

### 11 — Local-MQTT Re-Architecture + Connectivity Watchdog
`11-local-mqtt.md`

The v2 migration off Adafruit IO onto the hybrid MQTT stack (local Mosquitto on the Pi, mTLS, bridged to EMQX Cloud Serverless). Covers: topology, identities/auth (mTLS client cert), connection parameters, the new `devices/YOUR_DEVICE_ID/<channel>` topic scheme with native retain (dropping the `/get` and REST `/data/last` workarounds), app "last seen" vs. infra monitoring separation (`alive`/`online`), per-component porting notes, and a **new connectivity watchdog with state-resume across reboot**.

**Key content:** rip-and-replace decision table (§1), topic mapping with retain semantics (§4), device script changes (§5), watchdog + reboot-reason safety gate (§6), behaviour-by-connectivity (§8), expected MQTT usage for the operator's free-tier quota (§9), web/GitHub Pages port feasibility (§10), open investigations incl. the HIGH-risk reboot-reason detection (§11), decision log D11.59–D11.67 (§12).

**Status:** ✅ IMPLEMENTED & HARDWARE-VERIFIED (2026-06-06). Supersedes the Adafruit transport in docs 02 §1 and 04 (archived). Build/validation record: [`../archive/IMPLEMENTATION.md`](../archive/IMPLEMENTATION.md), [`../archive/HW-VALIDATION-v2.md`](../archive/HW-VALIDATION-v2.md).

---

### 12 — Watchdog Reboot-Reason Validation (Q1)
`12-watchdog-validation.md`

Hardware validation (2026-06-05, Plug S Gen3 fw 1.7.5) resolving the HIGH blocker behind the watchdog/state-resume design (doc 11 §6, §11 Q1). Confirms `sys.reset_reason` is available at boot and distinguishes a **software/watchdog reboot (`3`, resume)** from a **mains power loss (`1`, boot OFF)**; the RTC-retention alternative is ruled out. Includes method, results, the `resume iff reset_reason == 3` decision, and a re-verify-after-firmware note.

**Status:** ✅ DONE — unblocks doc 11 §6 implementation.

---

## Reading order

For someone new to the project (v2): **11 → 12 → 01 → 03 → 05 → 06 → 07** (then 00/02/04-archived for v1 transport history)

For implementation reference: **10 (repo structure) → 08 (investigations and lessons learned) → 09 (phase plan) → 05 (device script) → 06 (Android app) → 07 (deployment)**

---

## Decision log

Decisions use a prefix scheme: `D{doc}.{number}`, e.g., D00.1 is the first decision in doc 00. This was consolidated from the original per-doc numbering in Phase 5.

| Doc | Range | Topic |
|---|---|---|
| 00 | D00.1–D00.6 | Architecture, service selection |
| 02 | D02.7–D02.18 | Feeds, retain, staleness, control paths |
| 03 | D03.19–D03.26 | Encoding, key names, message format |
| 04 | D04.27–D04.35 | Adafruit IO specifics, `/get` workaround, TLS, topic format *(archived — see [archive/04](../archive/04-adafruit-io.md))* |
| 11 | D11.59–D11.67 | v2 local-MQTT re-arch, mTLS, topic scheme, watchdog/resume |
| 05 | D05.36–D05.46 | Timer model, boot sequence, NTP, mJS patterns |
| 06 | D06.47–D06.55 | Android app, CORS, auto-detect, schedule UX |
| 07 | D07.56–D07.58 | Deployment, auth, recovery |
