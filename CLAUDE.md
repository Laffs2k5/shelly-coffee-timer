# CLAUDE.md

## Project overview

Shelly Coffee Timer — a Shelly Plug S Gen3 smart plug that controls a coffee maker via countdown timers. No home server, no hub. Three control paths: physical button, local HTTP (same wifi), remote MQTT via Adafruit IO. An Android app (Kotlin/Compose) and an HTML fallback page provide the phone/computer interface.

Safety-first: every "on" state is a countdown (max 180 min). Power loss = OFF. Schedule fires once then disarms.

## Tech stack

| Component | Tech | Location |
|-----------|------|----------|
| Device script | mJS (JavaScript subset on ESP32) | `device/coffee.js` |
| Android app | Kotlin, Jetpack Compose | `app/` |
| Web fallback | Vanilla HTML/CSS/JS, no frameworks | `web/index.html` |
| Helper scripts | Bash + curl/mosquitto | `scripts/` |
| Broker | Adafruit IO (MQTT + REST, free tier) | External service |

## Key directories

- `docs/spec/` — Specification documents (00-10). The design blueprint, mostly static.
- `docs/` — Operational docs (grows over time, largely empty now).
- `device/` — Single mJS script, pasted into Shelly web UI manually.
- `app/` — Android Studio project.
- `web/` — Self-contained HTML control page.
- `scripts/` — Bash utilities for feed setup, testing, sending commands.

## Credentials

IMPORTANT: This repo is **public**. No real API keys, usernames, or IPs in committed files.

- All secrets live in `.env` (gitignored via `*.env` pattern). Template: `.env.example`.
- Scripts expect `source .env` before running (provides `AIO_USER`, `AIO_KEY`, `SHELLY_IP`).
- `web/index.html` must use `localStorage` prompt, never hardcoded credentials.
- `device/coffee.js` uses a placeholder `AIO_USER` — replace when pasting to device.

## mJS constraints

The Shelly mJS runtime is severely limited. When writing or modifying `device/coffee.js`:

- No Promises, no async/await, no template literals
- No `Array.indexOf()`, no `String.split()`, no `String.padStart()`
- `JSON.parse()` returns `undefined` on failure (not `null`)
- KVS operations are async with callbacks (use counter pattern for multiple loads)
- Single-threaded cooperative execution — no blocking loops
- HTTP request size limit: 3072 bytes total

## Communication architecture

```
Phone ──REST──> Adafruit IO <──MQTT──> Shelly
Phone ──HTTP (local, same wifi)──────> Shelly
```

Three Adafruit IO feeds: `command` (phone→device), `config` (phone→device), `heartbeat` (device→phone).

Adafruit IO does NOT support MQTT retain. Workaround: `/get` topic on connect.

## Git workflow

- Commit directly to `main` unless otherwise specified.
- AI agents can commit and push to `main`.
- Use **semantic commit messages**, subject line max **70 characters**.
  ```
  feat: add schedule time picker to Android app
  fix: prevent timer extension beyond max cap
  docs: update deployment troubleshooting section
  refactor: extract heartbeat publishing to helper
  test: add REST round-trip validation script
  chore: update .gitignore for Android build artifacts
  ```
- Feature branches for experimental or breaking work.

## Testing

No CI/CD. Manual testing against real hardware.

```bash
source .env
scripts/test-rest.sh       # REST API round-trip
scripts/test-mqtt.sh       # MQTT connectivity
scripts/setup-feeds.sh     # Create Adafruit IO feeds
scripts/send-command.sh t90  # Send a test command
```

## Spec docs reference

Full specification: `docs/spec/INDEX.md`. Key docs by topic:

- Message formats: `docs/spec/03-message-format.md`
- Adafruit IO setup: `docs/spec/04-adafruit-io.md`
- Device state machine: `docs/spec/05-state-machine.md`
- Phone interface: `docs/spec/06-phone-interface.md`
- Phase plan: `docs/spec/09-phase-plan.md`
