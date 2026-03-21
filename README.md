# Shelly Coffee Timer

A lightweight home-automation project that turns a **Shelly Plug S Gen3** into a timed coffee-maker controller. The Shelly runs a small mJS script (state machine) that accepts commands via MQTT through **Adafruit IO**, controllable from an Android app or a self-contained web page.

## Documentation

- **[Specification](docs/spec/INDEX.md)** — architecture decisions, message formats, state machine design, and phase plan.
- **[docs/](docs/)** — operational and development documentation (grows over time).

## Quick start

1. Clone the repo
2. `cp .env.example .env` and fill in your Adafruit IO username, key, and Shelly IP
3. `source .env`
4. Run `scripts/setup-feeds.sh` to create Adafruit IO feeds
5. Run `scripts/test-rest.sh` to verify connectivity
6. Configure the Shelly's MQTT (see [doc 04](docs/spec/04-adafruit-io.md) §4.1)
7. Paste `device/coffee.js` into the Shelly web UI
8. Open `app/` in Android Studio, build, and sideload the APK
9. Optionally deploy `web/index.html` to GitHub Pages

> **Credentials:** All secrets live in `.env` which is gitignored. Never commit real API keys — this repo is public. See [doc 10 §7](docs/spec/10-repo-spec.md) for details.

## Repo structure

```
shelly-coffee-timer/
├── docs/spec/     Specification documents (00–10)
├── device/        mJS script for the Shelly
├── app/           Android Studio project (Kotlin/Compose)
├── web/           HTML fallback control page
└── scripts/       Bash utility scripts
```

## Hardware

- Shelly Plug S Gen3
- Any drip coffee maker with a physical on/off switch
