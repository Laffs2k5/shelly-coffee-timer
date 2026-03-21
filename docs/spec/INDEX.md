# Shelly Coffee Maker — Specification Index

> This index and all numbered documents (00–10) live in `docs/spec/`. These are the specification documents — the blueprint used to design and build the system. For operational and development documentation, see `docs/`.

## Project summary

A smart plug (Shelly Plug S Gen3) controlling a coffee maker. Every on-state is a countdown timer — no "on indefinitely." The device operates autonomously with local-first control and optional remote access via Adafruit IO. Controlled from an Android app or web browser.

---

## Documents

### 00 — Landscape Exploration & Initial Research
`00-shelly-automation-initial-looking-around.md`

What exists and what's possible. Covers: device hardware and capabilities, scripting environment (mJS), communication options (MQTT, REST, webhooks), Shelly Cloud evaluation (excluded), third-party service candidates (Adafruit IO selected), protocol comparison (MQTT vs REST). Establishes the core design constraints and principles.

**Key decisions:** No home server (#1), Shelly Cloud excluded (#2), MQTT for device (#3), REST for phone (#4), Adafruit IO selected (#5), autonomous operation (#6).

---

### 01 — Functional Requirements
`01-functional-requirements.md`

What the plug does. Covers: three control paths (physical button, local HTTP, remote MQTT), timer rules (every on-state is a countdown, 180-min cap), schedule behavior (one-off, auto-disarm), safety requirements (power-loss → off, staleness check, NTP dependency), and the autonomous behavior matrix.

**Key content:** Timer arithmetic (§3), schedule fire-and-disarm logic (§4), safety invariants (§5), NTP rules (§5.4), staleness window (§5.5).

---

### 02 — Communication Architecture
`02-communication-architecture.md`

How the pieces talk. Covers: remote path (Shelly ↔ Adafruit IO ↔ phone via MQTT+REST), local path (phone → Shelly direct HTTP), three-feed structure (command, config, heartbeat), data flow scenarios, authority model, feed mapping, message budget analysis, and failure modes.

**Key content:** Feed properties and direction (§2), seven data flow diagrams (§3), authority table (§4), failure mode matrix (§7).

**Note:** Retain references updated to reflect Adafruit IO's `/get` workaround (see doc 04).

---

### 03 — Message Format Design
`03-message-format.md`

What the bytes look like. Covers: JSON encoding for all three feeds, command codes (`on`, `off`, `ext`, `sub`, `t90`), config payload (versioned, with `dur` and `max`), heartbeat payload, local HTTP API (RPC endpoints and responses), Adafruit IO-specific formatting (value envelope), and encoding rationale (why JSON, why flat, why short keys).

**Key content:** Command format with timestamp (§2.1), config version field (§3.2), heartbeat fields (§4.1), local vs remote key name mapping (§2.3), message budget verification (§5).

---

### 04 — Adafruit IO Setup & Validation
`04-adafruit-io-setup-and-validation.md`

Proving the theory works. Covers: account setup, free tier limits (confirmed: 10 feeds, 30 msg/min), the critical finding that **Adafruit IO does not support MQTT retain** and the `/get` topic workaround, MQTT connection details, Shelly MQTT configuration (`Mqtt.SetConfig`), TLS options, topic format, JSON payload behavior on Adafruit IO, and a 6-step validation test plan.

**Key content:** The retain problem and solution (§2), Shelly MQTT config with all fields explained (§4), what the firmware provides vs what we disabled (§4.2), validation tests (§6), rate limit analysis (§7), failure modes specific to AIO (§8).

---

### 05 — On-Device State Machine
`05-on-device-state-machine.md`

The mJS brain. Covers: state model (in-memory vs KVS-persisted), 10-step boot sequence, all event handlers (button, MQTT command, MQTT config, MQTT connect, timer tick, heartbeat, schedule check), core functions (turn_on, turn_off, execute_command, publish_heartbeat), local HTTP RPC endpoints, NTP sync detection, timer precision model, heartbeat publishing strategy, config processing, mJS implementation considerations, state transition diagram, and three complete event flow examples.

**Key content:** Boot sequence with async KVS loading (§3), command execution shared by MQTT and HTTP paths (§5.3), heartbeat debounce (§9.3), mJS pitfalls (§11), state transition diagram (§12).

---

### 06 — Phone Control Interface
`06-phone-control-interface.md`

How the human interacts. Covers: requirements (live status, instant controls, schedule config, auto-detect local/remote), technology evaluation (why CORS kills pure web → native Android app), Kotlin/Compose app design, UI layout matching mockup, auto-detect logic (local-first with 2s timeout), command routing (local vs remote), schedule changes (always via Adafruit IO), app configuration, HTML fallback for computers, and data flow diagrams for all operations.

**Key content:** CORS analysis driving the native app decision (§2.1), auto-detect algorithm (§4.1), schedule change flow with config versioning (§3.4, §7.3), native Android TimePickerDialog for schedule time.

---

### 07 — Deployment & Operations
`07-deployment-and-operations.md`

Keeping it running. Covers: initial deployment steps, script update process (manual via web UI, version-controlled in git), wifi/network changes (house move, IP change), credential management (AIO key rotation requires local wifi access), monitoring and troubleshooting checklist, common failure scenarios with fixes, nuclear recovery option, backup strategy, total-loss recovery, and future considerations.

**Key content:** Troubleshooting table (§6.2), total-loss recovery procedure (§7.2), future ideas list (§8).

---

### 08 — Open Investigations & Risk Items
`08-open-investigations.md`

What must be validated before implementation. Five items ordered by risk:

1. ~~**[HIGH]** `Shelly.addRPCHandler()`~~ — RESOLVED: does not exist. Use `HTTPServer.registerEndpoint()`.
2. ~~**[HIGH]** Timezone-aware local time in mJS~~ — RESOLVED: `new Date().getHours()/getMinutes()` works, DST-aware.
3. **[MEDIUM]** First-ever boot with empty feeds — verify `/get` on empty feed doesn't crash.
4. **[MEDIUM]** Stale remote status after command — UX decision for the Android app.
5. **[LOW]** Multi-phone config version race — accepted limitation, documented.

Plus two cleanup items: decision renumbering and doc 00 open questions audit.

---

### 09 — Phase Plan
`09-phase-plan.md`

How to get from docs to a working system. Three phases: prove the unknowns (test RPC handlers and timezone API on the device), build the device side (Adafruit IO setup → mJS script, incremental), build the phone side (Android app → HTML fallback). Each phase has a gate with explicit pass criteria. Includes an incremental test matrix for each stage and a risk-adjusted time estimate (~2–3 weeks realistic).

**Key content:** Phase 1 blockers (§ tasks 1.1, 1.2), stage 2B incremental build order with 13 test steps, stage 3A app build order with 12 test steps, parallelism notes, time estimates.

---

### 10 — Repository Specification
`10-repo-spec.md`

Single **public** GitHub repo structure. Covers: directory layout (`docs/`, `device/`, `app/`, `web/`, `scripts/`), file purposes, doc filename mapping (long authoring names → short repo names), `.gitignore`, branching approach (commit to main, branches for experiments), credentials handling (gitignored `.env` file, never committed), and the setup sequence from fresh clone to working system.

**Key content:** Directory tree (§2), credentials handling rules (§7), setup sequence (§8).

---

## Reading order

For someone new to the project: **00 → 01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10**

For implementation: **10 (set up repo) → 08 (review blockers) → 09 (follow the phase plan) → 04 §6 (validation tests) → 05 (write mJS script) → 06 (build Android app) → 07 (deploy)**

---

## Decision log

Decisions are numbered within each document. A consolidation pass (doc 08 §3.1) is pending.

| Doc | Range | Topic |
|---|---|---|
| 00 | #1–6 | Architecture, service selection |
| 02 | #7–18 | Feeds, retain, staleness, control paths |
| 03 | #19–26 | Encoding, key names, message format |
| 04 | #27–35 | Adafruit IO specifics, `/get` workaround, TLS, topic format |
| 05 | #36–46 | Timer model, boot sequence, NTP, mJS patterns |
| 06 | #36a–44a | Android app, CORS, auto-detect, schedule UX |
| 07 | #45a–47a | Deployment, auth, recovery |
