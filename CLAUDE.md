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
- `docs/testing/` — Test guides: AI-TEST-GUIDE.md (automated), REGRESSION.md (manual checklist).
- `docs/` — Operational docs: ARCHITECTURE.md (system diagram and flows).
- `device/` — Single mJS script, uploaded via RPC or pasted into Shelly web UI.
- `app/` — Android Studio project (Kotlin/Compose).
- `app/.../notification/` — Foreground notification service (4 files).
- `web/` — Self-contained HTML control page (deployed to GitHub Pages).
- `scripts/` — Bash utilities for feed setup, testing, sending commands.
- `.github/workflows/` — CI/CD: APK build, release, GitHub Pages deploy.

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

## CI/CD

GitHub Actions workflows in `.github/workflows/`:

- **build.yml** — Builds debug APK on every push to `main`. APK uploaded as GitHub Actions artifact (downloadable from the workflow run page).
- **release.yml** — On push of a `v*` tag: builds APK, generates changelog from commits, creates GitHub Release with APK attached.
- **deploy-pages.yml** — Deploys `web/` to GitHub Pages on push to `main` (only when `web/**` changes).

### Downloading APKs

- **Latest build:** Go to the [Actions tab](https://github.com/Laffs2k5/shelly-coffee-timer/actions/workflows/build.yml), click the most recent run, scroll to "Artifacts", download `debug-apk`.
- **Release builds:** Go to [Releases](https://github.com/Laffs2k5/shelly-coffee-timer/releases), download the APK from the latest release.

## Notification service

The Android app includes a foreground notification service (`notification/` package, 4 files):

| Component | Purpose |
|-----------|---------|
| `CoffeeNotificationService` | Foreground service that polls device every 30s, shows "Coffee ON -- N min remaining" notification, self-stops when coffee turns off |
| `NotificationHelper` | Creates notification channel, builds/updates/cancels notifications |
| `ScheduleAlarmManager` | Sets `AlarmManager` exact alarm for scheduled coffee time, starts notification service when alarm fires |
| `ScheduleAlarmReceiver` | `BroadcastReceiver` that starts the service on alarm fire |

Key behavior:
- Service only runs while coffee is actively ON (no background drain when off).
- Between polls, service counts down locally (1 min/min) for smooth display.
- After 10 consecutive poll failures (~5 min), notification shows "Connection lost".
- Schedule alarm is re-armed on every successful poll where `sch=1`, catching schedules set from any client.

## Testing

Test scripts in `scripts/` run against real hardware:

```bash
source .env
scripts/test-all.sh          # Run all tests in sequence
scripts/test-local-api.sh    # Test local HTTP endpoints
scripts/test-remote-api.sh   # Test Adafruit IO REST endpoints
scripts/test-staleness.sh    # Verify stale command rejection
scripts/test-config.sh       # Config version gating
scripts/test-schedule.sh     # Schedule fire + auto-disarm
scripts/test-rest.sh         # REST API round-trip
scripts/test-mqtt.sh         # MQTT connectivity
scripts/setup-feeds.sh       # Create Adafruit IO feeds
scripts/send-command.sh t90  # Send a test command
```

All test scripts support `--dry-run` mode for safe review. See `docs/testing/AI-TEST-GUIDE.md` for AI agent instructions and `docs/testing/REGRESSION.md` for the manual checklist.

## Development environment

This project is developed on a **Windows ARM64 Surface laptop** running **WSL2 (Ubuntu, aarch64)**.

### Build constraints
- **Android APK cannot be built in WSL** — the Android SDK's `aapt2` is x86_64 only. Build on Windows via Android Studio or Gradle with Windows SDK.
- The built APK lives at `C:\Users\peder\temp\shelly-coffee-app\build\outputs\apk\debug\` on Windows.
- Install to phone: `/mnt/c/Users/peder/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r <apk_path>`

### Android emulator — does NOT work
The x86_64 emulator can't run ARM64 AVDs (architecture mismatch), and x86_64 AVDs require hardware virtualization (unavailable on ARM). No Linux ARM64 emulator exists in the SDK. Test on a physical device.

### WSL interop
- Windows executables (`.exe`) can be called from WSL via binfmt_misc.
- The `WSLInterop` binfmt handler can get unregistered. Re-register: `sudo sh -c 'echo ":WSLInterop:M::MZ::/init:PF" > /proc/sys/fs/binfmt_misc/register'`
- `claude.exe` can be invoked from WSL for Windows-side tasks: use `-p "prompt"` and `--dangerously-skip-permissions` for non-interactive mode.
- Background execution of Windows binaries via the Bash tool's `run_in_background` fails (exec format error). Must run in foreground.

### Relay verification
The user's laptop charger runs through the Shelly plug. Verify relay state: `cat /sys/class/power_supply/AC1/online` (1=on, 0=off).

## Spec docs reference

Full specification: `docs/spec/INDEX.md`. Key docs by topic:

- Message formats: `docs/spec/03-message-format.md`
- Adafruit IO setup: `docs/spec/04-adafruit-io.md`
- Device state machine: `docs/spec/05-state-machine.md`
- Phone interface: `docs/spec/06-phone-interface.md`
- Phase plan: `docs/spec/09-phase-plan.md`

Operational docs:

- Architecture overview: `docs/ARCHITECTURE.md`
- AI test guide: `docs/testing/AI-TEST-GUIDE.md`
- Manual regression: `docs/testing/REGRESSION.md`
