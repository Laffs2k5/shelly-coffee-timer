# Shelly Coffee Timer

[![Build](https://github.com/Laffs2k5/shelly-coffee-timer/actions/workflows/build.yml/badge.svg)](https://github.com/Laffs2k5/shelly-coffee-timer/actions/workflows/build.yml)

A safety-first home-automation project that turns a **Shelly Plug S Gen3** into a timed coffee-maker controller. Every on-state is a countdown timer — the plug can never be left on indefinitely. The device runs autonomously with an mJS state machine, accepts commands via MQTT through **Adafruit IO**, and is controllable from an Android app, a web page, or curl.

**Live web control:** https://laffs2k5.github.io/shelly-coffee-timer/

**Specification docs:** [docs/spec/INDEX.md](docs/spec/INDEX.md)

## Hardware

- **Shelly Plug S Gen3** — smart plug with mJS scripting, MQTT, and local HTTP
- **Any drip coffee maker** with a physical on/off switch (left in the "on" position; the plug controls power)

## Repo structure

```
shelly-coffee-timer/
├── app/               Android app (Kotlin/Compose)
├── device/            mJS script for the Shelly (coffee.js)
├── web/               HTML control page (GitHub Pages)
├── scripts/           Bash utilities (feed setup, REST/MQTT testing)
├── docs/spec/         Specification documents (00–10 + INDEX)
├── .github/workflows/ GitHub Pages deployment
├── .env.example       Template for credentials
└── CLAUDE.md          AI assistant context
```

## Quick start

### Device setup

1. `cp .env.example .env` and fill in your Adafruit IO username, key, and Shelly IP
2. `source .env`
3. Run `scripts/setup-feeds.sh` to create the Adafruit IO feeds
4. Run `scripts/test-rest.sh` to verify connectivity
5. Configure the Shelly's MQTT settings (see [doc 04](docs/spec/04-adafruit-io.md) §4.1)
6. Paste `device/coffee.js` into the Shelly web UI script editor

### Android app

1. Open `app/` in Android Studio
2. Build and sideload the APK to your phone
3. Open Settings in the app and enter your Shelly IP, Adafruit IO username, and key

### Web control

- Visit https://laffs2k5.github.io/shelly-coffee-timer/ and enter your Adafruit IO credentials
- Or open `web/index.html` locally in a browser

## Credentials

All secrets live in `.env`, which is gitignored. **This repo is public — never commit real API keys.** See [doc 10 §7](docs/spec/10-repo-spec.md) for the full credentials policy.
