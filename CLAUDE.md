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

- No Promises, no async/await, no template literals, no arrow functions
- No `Array.indexOf()`, no `String.split()`, no `String.padStart()`
- `JSON.parse()` returns `undefined` on failure (not `null`)
- KVS operations are async with callbacks — must be chained sequentially (see below)
- Single-threaded cooperative execution — no blocking loops
- HTTP request size limit: 3072 bytes total

### Lessons learned from implementation (Phase 2)

- **Max ~4-5 concurrent timers.** Exceeding this crashes the script. Consolidate into fewer timers with counter-based dispatch (e.g., one 30s timer handles tick, schedule check, and heartbeat).
- **Max ~3 concurrent Shelly.call().** Firing 6 parallel KVS.Get calls causes "too many calls in progress" crash. Chain them sequentially instead of parallel.
- **Shelly.call userdata (4th param) is unreliable** — always arrives as empty string in callbacks. Use closure-captured variables instead.
- **Plug S Gen3 has no separate Input component.** The physical button toggles the switch directly in firmware — there is no `single_push` or `btn_down` event. Both button presses and `Switch.Set` calls fire the same status change on `switch:0`. Use a `script_switching` flag: set it before calling `Switch.Set`, clear it in the callback. The status handler ignores changes when the flag is set, and treats changes when the flag is clear as physical button presses.
- **Script.PutCode append mode.** Large scripts may need multiple PutCode calls with `append: true` after the first chunk.
- **Script upload via RPC.** No need to paste into web UI — use `Script.Create` + `Script.PutCode` + `Script.Start` + `Script.SetConfig` (enable: true for auto-start).

## Communication architecture

```
Phone ──REST──> Adafruit IO <──MQTT──> Shelly
Phone ──HTTP (local, same wifi)──────> Shelly
```

Three Adafruit IO feeds: `command` (phone→device), `config` (phone→device), `heartbeat` (device→phone).

Adafruit IO does NOT support MQTT retain. Workaround: `/get` topic on connect.

### Adafruit IO operational notes

- **Single MQTT connection per account.** The Shelly holds the slot — `mosquitto_sub`/`mosquitto_pub` from the computer will fail to connect while the Shelly is connected. Test MQTT via REST-to-MQTT path instead.
- **Rate limit: 30 data points/min.** Exceeding triggers escalating bans (30s → 60s → up to 1 hour). Monitor via: `curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/throttle" -H "X-AIO-Key: ${AIO_KEY}"`
- **topic_prefix cannot be empty.** The Shelly resets it to the device ID. Set to `{AIO_USER}/feeds` to avoid rejected publishes.
- **Empty feed /get returns non-JSON.** The script must handle `JSON.parse()` returning `undefined` gracefully.

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
