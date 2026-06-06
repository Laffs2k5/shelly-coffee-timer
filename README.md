# Shelly Coffee Timer

[![Build](https://github.com/Laffs2k5/shelly-coffee-timer/actions/workflows/build.yml/badge.svg)](https://github.com/Laffs2k5/shelly-coffee-timer/actions/workflows/build.yml)

A safety-first home-automation project that turns a **Shelly Plug S Gen3** into a timed coffee-maker controller. Every on-state is a countdown timer — the plug can never be left on indefinitely. The device runs autonomously with an mJS state machine and a connectivity watchdog, takes commands over a **local MQTT broker (mTLS)** with a **cloud bridge** for off-LAN access, and is also controllable directly over Wi-Fi (local HTTP). Clients: an Android app, a web page, or curl.

**Live web control:** https://laffs2k5.github.io/shelly-coffee-timer/

**Documentation:**
[Specification](docs/spec/INDEX.md) · [Architecture](docs/ARCHITECTURE.md) · [AI Test Guide](docs/testing/AI-TEST-GUIDE.md) · [Regression Checklist](docs/testing/REGRESSION.md)

> **This is v2** — local MQTT broker + cloud bridge + connectivity watchdog, validated on hardware. The v1 release used **Adafruit IO**; that design is archived in [docs/archive/](docs/archive/) (frozen at the `adafruit-final` tag). v2 design: specs [11](docs/spec/11-local-mqtt.md)–[12](docs/spec/12-watchdog-validation.md) and [Architecture](docs/ARCHITECTURE.md).

## Hardware

- **Shelly Plug S Gen3** — smart plug with mJS scripting, MQTT, and local HTTP
- **Any drip coffee maker** with a physical on/off switch (left in the "on" position; the plug controls power)

## Repo structure

```
shelly-coffee-timer/
├── app/               Android app (Kotlin/Compose) with notification service
├── device/            mJS script for the Shelly (coffee.js)
├── web/               HTML control page (GitHub Pages)
├── scripts/           Bash utilities (feed setup, REST/MQTT testing)
├── docs/spec/         Specification documents (00–03, 05–12 + INDEX; 04 archived)
├── docs/              Architecture overview, test guides
├── docs/archive/      v1 (Adafruit) docs + v2 build/validation record
├── .github/workflows/ CI/CD: APK build, release, GitHub Pages deploy
├── .env.example       Template for credentials
└── CLAUDE.md          AI assistant context
```

## Quick start

### Device setup

1. `cp .env.example .env` and fill in your broker hosts, credentials, and Shelly IP; `source .env`
2. Point the Shelly's MQTT at your local broker over mTLS (`Mqtt.SetConfig` via RPC — see [doc 11 §5](docs/spec/11-local-mqtt.md))
3. Upload the device script: `scripts/build-device.sh` produces `device/coffee.min.js`; flash it via RPC (`Script.PutCode`) or paste into the Shelly web UI
4. Verify connectivity with the test scripts in `scripts/`

### Android app

1. Open `app/` in Android Studio
2. **For the local-broker mTLS path:** replace the placeholder `app/src/main/res/raw/mqtt_ca.crt` with your broker's private-CA **public** cert (PEM) before building. (HTTP-direct and cloud work without this.)
3. Build and sideload the APK to your phone
4. In Settings, enter the Shelly IP (HTTP-direct), the local broker host, the cloud broker user/pass, and import the client `.p12` for local mTLS

> The bundled CA is build-time today (the released APK ships a placeholder, so it can't do local mTLS without a rebuild). A future enhancement is to import the CA at runtime like the `.p12`, making the released APK fully functional after import.

### Web control

- Visit https://laffs2k5.github.io/shelly-coffee-timer/ and enter your cloud MQTT (EMQX) credentials — they persist in the browser only
- Or open `web/index.html` locally in a browser

## Credentials

All secrets live in `.env`, which is gitignored. **This repo is public — never commit real API keys.** See [doc 10 §7](docs/spec/10-repo-spec.md) for the full credentials policy.

## License

[ISC](LICENSE)
