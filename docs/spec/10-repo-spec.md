# Shelly Coffee Maker — Repository Specification

## 1. Repository

Single private repository. Docs and code live together — the docs describe the code, the code implements the docs.

- **Name:** `shelly-coffee` (or similar)
- **Visibility:** Private (AIO key may appear in config files or the HTML fallback)
- **Host:** GitHub

---

## 2. Directory structure

```
shelly-coffee/
│
├── README.md
│
├── docs/
│   ├── spec/
│   │   ├── INDEX.md
│   │   ├── 00-landscape.md
│   │   ├── 01-requirements.md
│   │   ├── 02-communication.md
│   │   ├── 03-message-format.md
│   │   ├── 04-adafruit-io.md
│   │   ├── 05-state-machine.md
│   │   ├── 06-phone-interface.md
│   │   ├── 07-deployment.md
│   │   ├── 08-open-investigations.md
│   │   ├── 09-phase-plan.md
│   │   └── 10-repo-spec.md
│   │
│   └── (future: operational docs, API guides, etc.)
│
├── device/
│   └── coffee.js
│
├── app/
│   └── ...                        (Android Studio project tree)
│
├── web/
│   └── index.html
│
├── scripts/
│   ├── setup-feeds.sh
│   ├── test-mqtt.sh
│   ├── test-rest.sh
│   └── send-command.sh
│
└── .gitignore
```

### Directory purposes

| Directory | Contents | Notes |
|---|---|---|
| `docs/` | Project documentation for working with and understanding the repo | Grows over time: operational guides, API references, troubleshooting, changelogs |
| `docs/spec/` | Specification documents (00–10) used to design and build the system | The blueprint. Relatively static once implementation begins. These are the "how it should be built" docs. |
| `device/` | The mJS script that runs on the Shelly | Single file. This is what gets pasted into the Shelly web UI. |
| `app/` | Android Studio project (Kotlin/Compose) | Created by Android Studio's project wizard. Contains its own deep tree (`app/src/main/...`). |
| `web/` | HTML fallback control page | Self-contained single file. May be deployed to GitHub Pages from this directory. |
| `scripts/` | Bash utility scripts | Validation tests from doc 04, convenience wrappers for curl commands, setup automation. |

### The two documentation tiers

**`docs/spec/`** contains the documents we wrote before and during initial implementation — the architecture decisions, message formats, state machine design, phase plan, etc. These are the specification. They answer "what are we building and why." Once the system is built, these mostly freeze — they're updated only if the architecture changes fundamentally.

**`docs/`** (top level) will contain documentation that evolves with the code — things like: how to set up a development environment, the mJS script API reference (what each function does), the Android app build instructions, the Adafruit IO feed schema reference, operational runbooks derived from doc 07's conceptual content, and anything else that helps someone work with the repo day-to-day. This layer doesn't exist yet — it grows during and after implementation.

---

## 3. Key files

### `README.md`

Top-level entry point. Should contain:

- One-paragraph project description
- Link to `docs/spec/INDEX.md` for the full specification
- Link to `docs/` for operational and development documentation (once it exists)
- Quick start: what the system does, what hardware you need, where to begin
- Repo structure overview

### `device/coffee.js`

The single mJS script from doc 05. This file is the source of truth — it gets manually pasted into the Shelly web UI when deploying or updating. There's no build step, no compilation, no upload automation.

### `web/index.html`

Self-contained HTML file from doc 06 §6. Inline CSS, inline JavaScript, no external dependencies. Talks to Adafruit IO REST API only.

### `scripts/`

Bash scripts wrapping the curl commands from doc 04 §6 and doc 03 §6. These are helper tools, not part of the deployed system. Examples:

- `setup-feeds.sh` — creates the 3 Adafruit IO feeds
- `test-mqtt.sh` — runs the MQTT connectivity test (wraps mosquitto_sub/pub)
- `test-rest.sh` — runs the REST round-trip test
- `send-command.sh t90` — sends a command to the command feed (quick testing)

All scripts read credentials from environment variables (`AIO_USER`, `AIO_KEY`, `SHELLY_IP`), not hardcoded.

---

## 4. Doc filename mapping

The docs were authored with long descriptive filenames. In the repo they use shorter names for easier reference.

| Repo filename | Full title |
|---|---|
| `00-landscape.md` | Shelly Plug S Gen3 — Lightweight Home Automation |
| `01-requirements.md` | Shelly Coffee Maker — Functional Requirements |
| `02-communication.md` | Shelly Coffee Maker — Communication Architecture |
| `03-message-format.md` | Shelly Coffee Maker — Message Format Design |
| `04-adafruit-io.md` | Shelly Coffee Maker — Adafruit IO Setup & Validation |
| `05-state-machine.md` | Shelly Coffee Maker — On-Device State Machine |
| `06-phone-interface.md` | Shelly Coffee Maker — Phone Control Interface |
| `07-deployment.md` | Shelly Coffee Maker — Deployment & Operations |
| `08-open-investigations.md` | Shelly Coffee Maker — Open Investigations & Risk Items |
| `09-phase-plan.md` | Shelly Coffee Maker — Phase Plan |
| `10-repo-spec.md` | Shelly Coffee Maker — Repository Specification |

---

## 5. `.gitignore`

```gitignore
# Android Studio
app/.gradle/
app/build/
app/local.properties
app/.idea/
app/*.iml
app/captures/
app/.externalNativeBuild/

# Credentials — never commit these
*.env
secrets.*

# OS files
.DS_Store
Thumbs.db

# Editor files
*.swp
*.swo
*~
.vscode/
```

---

## 6. Branching and workflow

This is a single-person project. Keep it simple:

- **`main` branch** is the source of truth
- Commit directly to `main` for small changes
- Use a feature branch if working on something experimental that might break things (e.g., a major script rewrite)
- No CI/CD, no PRs, no code review process
- Tag releases if you want to mark milestones (e.g., `v1.0-device-working`, `v2.0-app-complete`)

---

## 7. Credentials handling

| Credential | Where it lives | In the repo? |
|---|---|---|
| Adafruit IO username | `scripts/*.sh` (via `$AIO_USER` env var), Android app SharedPreferences, `web/index.html` (if hardcoded) | No (env var in scripts). Possibly in `web/index.html` — repo is private. |
| Adafruit IO key | Same locations as username | Same handling. Never in committed scripts. |
| Shelly local IP | Android app SharedPreferences, `scripts/*.sh` (via `$SHELLY_IP` env var) | No (env var in scripts) |
| Shelly MQTT password | On-device only (set via `Mqtt.SetConfig`, redacted in `Mqtt.GetConfig`) | No |

The scripts directory should include a `README.md` or comments explaining which environment variables to set:

```bash
export AIO_USER="your_username"
export AIO_KEY="your_aio_key"
export SHELLY_IP="192.168.1.xxx"
```

---

## 8. Setup sequence

To go from a fresh clone to a working repo:

1. Clone the repo
2. Set environment variables (`AIO_USER`, `AIO_KEY`, `SHELLY_IP`)
3. Run `scripts/setup-feeds.sh` to create Adafruit IO feeds
4. Run `scripts/test-rest.sh` to verify connectivity
5. Configure the Shelly's MQTT (doc 04 §4.1)
6. Paste `device/coffee.js` into the Shelly web UI
7. Open `app/` in Android Studio, build, sideload APK
8. Optionally deploy `web/index.html` to GitHub Pages
