# docs/archive/

Superseded and historical documents, kept for reference. Not part of the active doc set.

## v1 (Adafruit IO) — superseded by the v2 local-MQTT architecture

- [ARCHITECTURE-adafruit.md](ARCHITECTURE-adafruit.md) — frozen snapshot of the v1.x Adafruit IO
  architecture (the `adafruit-final` tag). Superseded by [../ARCHITECTURE.md](../ARCHITECTURE.md).
- [04-adafruit-io.md](04-adafruit-io.md) — the Adafruit IO setup/validation spec (account, feeds, the
  `/get` retain workaround, rate limits). Superseded by [../spec/11-local-mqtt.md](../spec/11-local-mqtt.md).

The other v1 specs (`00`, `01`, `02`, `03`, `05`–`10`) stay under `docs/spec/` as the foundational
design — their transport bits are superseded by doc 11, but requirements, message format, state machine,
phone interface, and deployment remain the basis for v2. See [../spec/INDEX.md](../spec/INDEX.md).

## v2 work docs — the build/validation record (process docs, not the live spec)

- [IMPLEMENTATION.md](IMPLEMENTATION.md) — the v2 phase/build tracker. All phases done; the device, web
  fallback, and Android app are implemented and hardware-verified.
- [ANDROID-V2-PLAN.md](ANDROID-V2-PLAN.md) — the v2 Android app plan (broker-list transport + HTTP-direct).
- [HW-VALIDATION-v2.md](HW-VALIDATION-v2.md) — the hardware + phone validation log across sessions
  (reset-reason gate, all 4 connection types, roaming, event log, keepalive fix, schedule set/clear/fire).

The live design lives in [../spec/](../spec/) (00–03, 05–12), [../ARCHITECTURE.md](../ARCHITECTURE.md),
and the test guides in [../testing/](../testing/).
