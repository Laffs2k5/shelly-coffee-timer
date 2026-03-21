# Shelly Coffee Maker — Message Format Design

## 1. Design constraints

| Constraint | Impact |
|---|---|
| mJS JSON parsing | `JSON.parse()` works but is limited. Flat objects only — no nested objects or arrays. Keep keys short. |
| MQTT payload size | No hard Shelly limit on MQTT payload, but keep small for memory. Target < 200 bytes. |
| HTTP request size (local) | 3072 bytes total (headers + body) for inbound HTTP to the Shelly. Not a concern — our payloads are tiny. |
| Adafruit IO data value | Stored as a string. Max 1KB per data point. JSON must be sent as a string value. |
| Timestamp precision | Unix epoch seconds (10 digits). Sufficient for 2-minute staleness window. |
| mJS string handling | No template literals. String concatenation with `+` operator. No `padStart`, limited `String` methods. |

---

## 2. Command format (`command` feed)

### 2.1 Remote commands (MQTT via Adafruit IO)

**Format:** Short JSON string.

```json
{"c":"t90","ts":1711000000}
```

| Field | Type | Description |
|---|---|---|
| `c` | string | Command code (see table below) |
| `ts` | number | Unix epoch seconds when command was sent |

**Command codes:**

| Code | Meaning | Behavior when off | Behavior when on |
|---|---|---|---|
| `on` | Turn on (default 90 min) | Start timer at 90 | Reset timer to 90 |
| `off` | Turn off | No-op | Turn off, clear timer |
| `ext` | Add 30 min | Turn on with 30 min | Add 30 min (cap 180) |
| `sub` | Subtract 30 min | No-op | Subtract 30 min (off if ≤ 30) |
| `t90` | Set timer to 90 min | Turn on with 90 min | Reset timer to 90 |

Note: `on` and `t90` are functionally identical. Both exist for clarity — `on` reads as intent ("turn on"), `t90` reads as mechanism ("set timer to 90"). The device treats them the same. We can drop one later if it feels redundant.

**Example messages:**

```json
{"c":"on","ts":1711036800}
{"c":"ext","ts":1711036812}
{"c":"off","ts":1711036900}
{"c":"sub","ts":1711036950}
```

**Device processing pseudocode:**

```
receive message → JSON.parse
if (now - msg.ts > 120) → discard (stale)
if (!ntp_synced) → discard (no clock)
execute command code
publish heartbeat with ack = msg.c
```

### 2.2 Local commands (HTTP direct)

**Request:** HTTP GET to the Shelly's RPC endpoint. No JSON body — command is in the URL.

```
GET /script/1/coffee_command?cmd=t90
GET /script/1/coffee_command?cmd=ext
GET /script/1/coffee_command?cmd=off
GET /script/1/coffee_command?cmd=sub
```

**Response:** JSON.

```json
{"ok":true,"state":"on","remaining":90,"ack":"t90"}
```

| Field | Type | Description |
|---|---|---|
| `ok` | boolean | Whether the command was accepted |
| `state` | string | Current switch state after command: `on` or `off` |
| `remaining` | number | Minutes remaining on timer (0 if off) |
| `ack` | string | The command that was executed |

**Error response:**

```json
{"ok":false,"error":"unknown command"}
```

No timestamp needed — local commands are synchronous. No staleness check.

### 2.3 Local status (HTTP direct)

**Request:**

```
GET /script/1/coffee_status
```

**Response:**

```json
{"state":"on","remaining":74,"mode":"manual","sch":0,"h":6,"m":10,"ntp":true,"ts":1711000000}
```

Same data as the heartbeat (section 4) but delivered synchronously with longer, more readable key names. The heartbeat uses short keys for wire efficiency (`s`, `r`); the local response uses full names (`state`, `remaining`) since it doesn't traverse MQTT. This is how the phone gets status when on the same wifi without going through Adafruit IO.

| Local key | Heartbeat key | Description |
|---|---|---|
| `state` | `s` | Switch state: `on` or `off` |
| `remaining` | `r` | Minutes remaining |
| `mode` | `mode` | What started the on-state |
| `sch` | `sch` | Schedule armed (1/0) |
| `h` | `h` | Schedule hour |
| `m` | `m` | Schedule minute |
| `ntp` | `ntp` | NTP synced |
| `ts` | `ts` | Timestamp |

---

## 3. Config format (`config` feed)

### 3.1 Payload

**Format:** Flat JSON string. Latest value retrievable via `/get` topic (MQTT) or `/data/last` (REST). See doc 04 §2.

```json
{"v":3,"sch":1,"h":6,"m":10,"dur":90,"max":180}
```

| Field | Type | Description | Default |
|---|---|---|---|
| `v` | number | Config version. Monotonically increasing. | — |
| `sch` | number | Schedule enabled: 1 = armed, 0 = disabled | 0 |
| `h` | number | Schedule hour (0–23) | 6 |
| `m` | number | Schedule minute (0–59) | 0 |
| `dur` | number | Default on-duration in minutes (used by schedule and `on` command) | 90 |
| `max` | number | Hard ceiling for timer in minutes | 180 |

### 3.2 Version field (`v`)

The version is a simple incrementing integer managed by the phone. Every time the phone writes a new config, it increments `v`.

**On device receive (MQTT):**
1. Parse JSON
2. Compare `v` to stored version in KVS
3. If incoming `v` > stored `v`: accept, cache to KVS
4. If incoming `v` ≤ stored `v`: ignore (stale or duplicate)

**On device boot (no MQTT):**
1. Load config from KVS
2. Use as-is until MQTT delivers a newer version

This handles the edge case where the device has a newer config in KVS (received before losing connectivity) than the latest value on the broker (which might be from an even earlier write).

### 3.3 Configurability of `dur` and `max`

The default duration and hard ceiling are in the config rather than hardcoded. This means:
- You can change the default from 90 to 60 or 45 without reflashing the device
- You can lower the safety ceiling if desired
- The device always enforces: `timer ≤ max`, regardless of commands received

These are expected to change rarely. Including them in config means one less reason to touch the device script.

### 3.4 What happens when schedule fires

The device sets `sch = 0` in its local KVS copy. It does NOT write back to the config feed (phone owns that). The heartbeat reports the current schedule state. If the phone later writes a new config with `sch = 1`, the version will be higher and the device accepts it.

---

## 4. Heartbeat format (`heartbeat` feed)

### 4.1 Payload

**Format:** Flat JSON string. Latest value retrievable via REST `/data/last`. See doc 04 §2.

```json
{"s":"on","r":74,"mode":"sch","sch":0,"h":6,"m":10,"ack":"ext","ts":1711000000,"ntp":true}
```

| Field | Type | Description |
|---|---|---|
| `s` | string | Switch state: `on` or `off` |
| `r` | number | Remaining timer in minutes (0 if off) |
| `mode` | string | What started the current on-state: `manual` (physical button), `remote` (MQTT/HTTP command), `sch` (schedule). Empty string if off. |
| `sch` | number | Schedule currently armed: 1 or 0 (reflects device's local state, may differ from config feed after schedule fires) |
| `h` | number | Schedule hour (from current config) |
| `m` | number | Schedule minute (from current config) |
| `ack` | string | Last command code successfully processed. Empty string if none since boot. |
| `ts` | number | Unix epoch seconds when this heartbeat was generated |
| `ntp` | boolean | Whether the device has NTP sync |

### 4.2 When to publish

| Trigger | Reason |
|---|---|
| State change (on→off or off→on) | Phone needs to know immediately |
| Command processed | Updates `ack` field |
| Schedule fires or disarms | Updates `sch` field |
| Every 5 minutes while on | Keeps `r` (remaining) reasonably fresh |
| Every 15 minutes while off | "I'm alive" signal — updates `ts` for last-seen |
| On MQTT connect/reconnect | Fresh snapshot after any connectivity gap |

### 4.3 Heartbeat size

Worst case: `{"s":"on","r":180,"mode":"remote","sch":1,"h":23,"m":59,"ack":"ext","ts":1711036800,"ntp":true}` = ~95 bytes. Well within limits.

---

## 5. Message budget revisited

With concrete formats, let's verify the 30 msg/min Adafruit IO free tier:

**Typical morning scenario:** Schedule fires at 06:10.

| Time | Event | Messages |
|---|---|---|
| 06:10 | Schedule fires → heartbeat published | 1 |
| 06:15 | Periodic heartbeat (on, r=85) | 1 |
| 06:20 | Periodic heartbeat (on, r=80) | 1 |
| ... | Every 5 min while on | 1 each |
| 07:40 | Timer hits 0 → off → heartbeat | 1 |
| 07:55 | Periodic heartbeat (off, alive) | 1 |

**Total for a 90-min session:** ~20 messages over 90 minutes. Peak rate: 1 per 5 min = 0.2/min. Nowhere near the 30/min limit.

**Worst case burst:** User rapidly presses ext, ext, ext from phone. Each command = 1 msg in + 1 heartbeat out = 2 messages per interaction. Even 10 rapid presses = 20 messages. Fine as a one-time burst.

---

## 6. Adafruit IO specifics

### 6.1 Publishing commands (phone → command feed)

```bash
curl -X POST "https://io.adafruit.com/api/v2/{user}/feeds/command/data" \
  -H "X-AIO-Key: {key}" \
  -H "Content-Type: application/json" \
  -d '{"value": "{\"c\":\"t90\",\"ts\":1711036800}"}'
```

Note: Adafruit IO wraps everything in a `{"value": "..."}` envelope. The actual command JSON is a string inside `value`.

### 6.2 Publishing config (phone → config feed)

```bash
curl -X POST "https://io.adafruit.com/api/v2/{user}/feeds/config/data" \
  -H "X-AIO-Key: {key}" \
  -H "Content-Type: application/json" \
  -d '{"value": "{\"v\":3,\"sch\":1,\"h\":6,\"m\":10,\"dur\":90,\"max\":180}"}'
```

### 6.3 Reading heartbeat (phone → heartbeat feed)

```bash
curl "https://io.adafruit.com/api/v2/{user}/feeds/heartbeat/data?limit=1" \
  -H "X-AIO-Key: {key}"
```

Returns an array with one data point. The `value` field contains the heartbeat JSON string.

### 6.4 MQTT topics (device side)

| Feed | Subscribe/Publish | Topic |
|---|---|---|
| command | Subscribe | `{user}/feeds/command` |
| config | Subscribe | `{user}/feeds/config` |
| heartbeat | Publish | `{user}/feeds/heartbeat` |

The device subscribes to `command` and `config` on connect. The firmware delivers incoming messages to the mJS script's MQTT handler.

---

## 7. Encoding rationale

**Why JSON and not plain strings for commands?**

The timestamp must travel with the command. Options were:
- `t90:1711036800` — colon-separated, custom parsing
- `t90 1711036800` — space-separated, custom parsing
- `{"c":"t90","ts":1711036800}` — JSON, parsed with `JSON.parse()`

JSON wins because mJS has `JSON.parse()` built in. Custom string parsing in mJS is fragile and verbose (no `split()`, limited string methods). The overhead is ~30 extra bytes per message, which is irrelevant at our volumes.

**Why flat JSON and not nested?**

mJS handles `obj.field` access on flat objects reliably. Nested objects (`obj.schedule.hour`) add parsing complexity and risk. Every field is a top-level key.

**Why short keys (`s`, `r`, `c`, `ts`) and not readable names?**

Payload size and mJS memory. `{"state":"on","remaining":74}` is readable but ~40% larger than `{"s":"on","r":74}`. On an ESP32 with ~200KB free RAM and cooperative single-threaded execution, every byte in a parsed JSON object costs. Short keys also reduce the chance of hitting any payload size limits. The mapping is documented here — readability lives in the docs, not on the wire.

---

## 8. Design decisions made

| # | Decision | Rationale |
|---|---|---|
| 19 | Commands are flat JSON with `c` (code) and `ts` (timestamp) | Minimal, parseable by mJS, carries staleness timestamp |
| 20 | Config is flat JSON with version field `v` | Version allows device to detect stale vs newer config on reconnect |
| 21 | Heartbeat is flat JSON, latest value always available via REST | Single snapshot of device state; phone reads latest on demand |
| 22 | Short key names throughout | Memory efficiency on ESP32, smaller payloads |
| 23 | Local HTTP uses URL params for commands, JSON for responses | GET requests are simple (curl, browser, bookmarks); JSON responses are parseable |
| 24 | `dur` and `max` are configurable via config feed, not hardcoded | Change defaults without reflashing; safety ceiling remains device-enforced |
| 25 | `on` and `t90` are both defined (functionally identical) | `on` reads as intent, `t90` as mechanism; can drop one later if redundant |
| 26 | Heartbeat frequency: 5 min while on, 15 min while off | Balances freshness against message budget; event-triggered publishes fill the gaps |
