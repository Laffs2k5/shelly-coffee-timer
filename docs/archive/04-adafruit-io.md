# Shelly Coffee Maker — Adafruit IO Setup & Validation

## 1. Account and service overview

### 1.1 What Adafruit IO is

Adafruit IO is a hosted IoT platform providing both MQTT and REST access to the same underlying data store ("feeds"). It is operated by Adafruit Industries and runs on infrastructure hosted in the US.

### 1.2 Free tier limits (confirmed)

| Resource | Free tier | IO+ ($10/month) |
|---|---|---|
| Feeds | 10 | Unlimited |
| Dashboards | 5 | Unlimited |
| Data rate | 30 data points/min | 60 data points/min |
| Data retention | 30 days | 60 days |
| Feed data size | 1 KB per data point | 1 KB per data point |
| Connection attempts | 20/min (MQTT) | 20/min (MQTT) |
| MQTT QoS | 0 and 1 | 0 and 1 |

We use **3 of 10 feeds** (command, config, heartbeat). Headroom for 7 more if needed.

Our peak data rate (see doc 03 section 5) is well under 5 msg/min. Even aggressive phone polling stays under 10/min. No risk of hitting 30/min.

Data retention of 30 days is irrelevant for our use case — we only care about the latest value per feed, not historical data.

### 1.3 Account setup

1. Create account at https://io.adafruit.com
2. Note the **username** (visible in URL: `io.adafruit.com/{username}`)
3. Obtain the **AIO key** from the key icon in the header bar
4. Create three feeds: `command`, `config`, `heartbeat`

Feed creation can be done via the web UI (Feeds → New Feed) or via REST API:

```bash
# Requires: source .env (see .env.example in repo root)

for feed in command config heartbeat; do
  curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds" \
    -H "X-AIO-Key: ${AIO_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"feed\": {\"name\": \"${feed}\"}}"
  echo ""
done
```

---

## 2. Critical finding: Adafruit IO does not support MQTT retain

### 2.1 The problem

Doc 02 (Communication Architecture) designed the system around MQTT retained messages:
- **Config feed**: retained, so the device receives the latest config on reconnect
- **Heartbeat feed**: retained, so the phone sees the last device state even if the device is offline

**Adafruit IO does not support the MQTT retain flag.** Publishing with `retain=true` has no effect. The broker does not store a last-known message per topic for delivery to new subscribers.

This is a documented platform limitation. Their reasoning: the mix of MQTT and HTTP APIs, scale concerns, and the fact that all data is already stored in their database make broker-level retain difficult to implement without degrading performance.

### 2.2 The workaround: `/get` topic

Adafruit IO provides a `/get` topic modifier as a replacement for retain. The mechanism:

1. Subscribe to `{username}/feeds/{feedkey}`
2. Publish anything (even an empty string) to `{username}/feeds/{feedkey}/get`
3. The broker immediately sends the most recent value on that feed — only to the requesting client

This is functionally equivalent to retained messages but requires an explicit request after subscribing.

### 2.3 Impact on our architecture

The core data flow remains the same. The change is in the **reconnect sequence**:

**What doc 02 assumed (with retain):**
```
Device connects to MQTT
  → subscribes to command and config
  → broker automatically delivers retained config message
  → device updates KVS
```

**What actually happens (with /get):**
```
Device connects to MQTT
  → subscribes to command and config
  → publishes to {user}/feeds/config/get
  → broker delivers latest config value
  → device updates KVS
```

For the **heartbeat** (device → phone direction), the phone side uses REST `GET /api/v2/{user}/feeds/heartbeat/data?limit=1` — this already retrieves the latest value from the database regardless of MQTT retain. No change needed on the phone side.

For the **config** feed, the device's mJS script must publish a `/get` request on each MQTT connect. This is a one-line addition to the MQTT `on_connect` handler.

For the **command** feed, no change. Commands were already non-retained by design (doc 02, decision D02.7). Missing a command while offline is correct behavior.

### 2.4 Revised decision

| # | Decision | Rationale |
|---|---|---|
| D04.27 | Use `/get` topic to fetch latest config on MQTT connect | Adafruit IO does not support MQTT retain; `/get` is the equivalent mechanism |
| D04.28 | Heartbeat retrieval via REST is unaffected | Phone reads latest value via REST API, which returns from the database regardless of retain |
| D04.29 | No architectural changes to feeds, data flow, or authority model | The `/get` workaround is transparent to the overall design |

---

## 3. MQTT connection configuration

### 3.1 Adafruit IO MQTT broker details

| Parameter | Value |
|---|---|
| Host | `io.adafruit.com` |
| TLS port | `8883` |
| Non-TLS port | `1883` |
| WebSocket port | `443` |
| Username | Adafruit IO username |
| Password | Adafruit IO AIO key |
| Client ID | Leave blank (broker assigns random ID), or use a unique string |
| QoS | 0 or 1 (QoS 2 not supported) |
| Protocol | MQTT 3.1.1 |

### 3.2 MQTT topic format

Adafruit IO uses a specific topic structure. For our feeds:

| Feed | Full topic | Short topic |
|---|---|---|
| command | `{username}/feeds/command` | `{username}/f/command` |
| config | `{username}/feeds/config` | `{username}/f/config` |
| heartbeat | `{username}/feeds/heartbeat` | `{username}/f/heartbeat` |

The short `/f/` form saves bytes on the wire — relevant for the constrained Shelly.

**Wildcard subscription caveat:** Subscribing to `{username}/f/#` will produce messages on three sub-topics per feed update: `{username}/f/{feedkey}`, `{username}/f/{feedkey}/json`, and `{username}/f/{feedkey}/csv`. To avoid duplicates, subscribe to specific feed topics rather than using wildcards. Alternatively, use the `+` wildcard (`{username}/f/+`) to get only the base topic without `/json` and `/csv` variants.

### 3.3 Error and throttle topics

The device should subscribe to these diagnostic topics during development:

| Topic | Purpose |
|---|---|
| `{username}/errors` | Publish/subscribe errors, ban notifications |
| `{username}/throttle` | Rate limit warnings |

These are useful for debugging but can be omitted in production to save memory.

### 3.4 Time topic (bonus NTP alternative)

Adafruit IO provides a built-in time service via MQTT:

| Topic | Format |
|---|---|
| `time/seconds` | Unix epoch seconds (published every second) |
| `time/millis` | Unix epoch milliseconds |
| `time/ISO-8601` | ISO 8601 UTC string |

Subscribing to `time/seconds` delivers the current Unix time every second. This could serve as a **backup NTP source** if the Shelly's built-in NTP fails. However, it adds message volume (1/sec) so it should only be used as a fallback, not primary time source. The Shelly's firmware NTP is preferred.

---

## 4. Shelly Gen3 MQTT configuration

### 4.1 Configuration via RPC

The Shelly Plug S Gen3 uses `MQTT.SetConfig` to configure its MQTT connection. All configuration is done via the device's local HTTP API.

```bash
SHELLY="192.168.1.xxx"  # Device IP

curl -X POST -d '{
  "id": 1,
  "method": "Mqtt.SetConfig",
  "params": {
    "config": {
      "enable": true,
      "server": "io.adafruit.com:8883",
      "user": "YOUR_AIO_USERNAME",
      "pass": "YOUR_AIO_KEY",
      "ssl_ca": "ca.pem",
      "topic_prefix": "YOUR_AIO_USERNAME/feeds",
      "enable_rpc": false,
      "status_ntf": false,
      "rpc_ntf": false,
      "enable_control": false
    }
  }
}' http://${SHELLY}/rpc
```

Then reboot:

```bash
curl -X POST -d '{"id":1, "method":"Shelly.Reboot"}' http://${SHELLY}/rpc
```

### 4.2 Configuration fields explained

| Field | Value | Why |
|---|---|---|
| `enable` | `true` | Enable MQTT connection |
| `server` | `io.adafruit.com:8883` | Adafruit IO MQTT broker, TLS port |
| `user` | AIO username | MQTT authentication |
| `pass` | AIO key | MQTT authentication |
| `ssl_ca` | `"ca.pem"` | TLS verified by Shelly's built-in CA bundle. Adafruit IO uses a publicly-trusted certificate. |
| `topic_prefix` | See section 4.3 | Custom prefix for Shelly's built-in MQTT features |
| `enable_rpc` | `false` | Prevents external MQTT clients from calling Shelly RPC methods (e.g. `Switch.Set`) — our script is the sole switch controller |
| `status_ntf` | `false` | Prevents firmware from auto-publishing component status JSON on every change — our script publishes its own heartbeat with exactly the fields we need |
| `rpc_ntf` | `false` | Prevents firmware from publishing `NotifyStatus`/`NotifyEvent` — unnecessary noise |
| `enable_control` | `false` | Prevents firmware from subscribing to `{prefix}/command/switch:0` — this would bypass our script's timer and safety logic entirely |

**What the firmware still provides (and we depend on):** The MQTT transport layer — TCP/TLS connection management, auto-reconnect with backoff, authentication, and the `MQTT.subscribe()` / `MQTT.publish()` APIs exposed to mJS scripts. The four flags above only disable application-level features the firmware layers on top of that transport.

### 4.3 Topic prefix — important constraint

The Shelly's `topic_prefix` setting controls where the firmware publishes its built-in application-level MQTT messages (online status, RPC notifications, status updates). Since we disabled all of these application features (`enable_rpc`, `status_ntf`, `rpc_ntf`, `enable_control` all false), the topic prefix is less critical — nothing uses it.

However, the **mJS script** uses `MQTT.publish()` and receives messages via the firmware's MQTT subscription handler, which operates on raw topics. The script addresses Adafruit IO feeds directly by their full topic path:

- Subscribe to: `{username}/f/command` and `{username}/f/config`
- Publish to: `{username}/f/heartbeat` and `{username}/f/config/get`

The mJS script specifies these topics explicitly, independent of the firmware's `topic_prefix` setting. This means the `topic_prefix` can be left as `null` (device ID) or set to anything — it won't interfere with our script's topic addressing.

**Recommendation:** Leave `topic_prefix` as `null` (default). The firmware's built-in topics will use the device ID as prefix, which keeps them separate from our Adafruit IO feed topics.

### 4.4 TLS options on Shelly Gen3

The `ssl_ca` field supports several modes:

| Value | Behavior |
|---|---|
| `null` | Plain TCP, no TLS (port 1883) |
| `"*"` | TLS with disabled certificate validation (insecure) |
| `"ca.pem"` | TLS verified against Shelly's built-in CA bundle |
| `"user_ca.pem"` | TLS verified against a user-uploaded CA certificate |

For Adafruit IO, `"ca.pem"` is correct — their server certificate is signed by a publicly-trusted CA (Let's Encrypt or similar) which should be in the Shelly's built-in bundle.

If `"ca.pem"` fails during validation testing, fall back to `"*"` temporarily, then investigate the certificate chain. Do not ship with `"*"` in production — it disables TLS verification and allows man-in-the-middle attacks.

---

## 5. MQTT data format on Adafruit IO

### 5.1 How Adafruit IO handles JSON payloads

Adafruit IO has specific behavior around JSON data that affects how our messages are received:

**If the payload contains a `"value"` key at the top level**, Adafruit IO extracts and stores the value. It interprets the message as IO-formatted data:
```json
{"value": "22.5", "lat": 0, "lon": 0, "ele": 0}
```
The `value` field is what gets stored and what subscribers receive.

**If the payload does NOT contain a `"value"` key**, Adafruit IO treats the entire blob as a plain text string and stores it as-is. This is our case — our message format uses keys like `c`, `ts`, `s`, `r`, etc., not `value`.

This means our messages from doc 03 — `{"c":"t90","ts":1711036800}`, `{"v":3,"sch":1,"h":6,"m":10,"dur":90,"max":180}`, etc. — will be stored and forwarded verbatim. No interference from Adafruit IO's processing system.

### 5.2 What MQTT subscribers receive

When the device subscribes to `{username}/f/command` and someone publishes a command via REST, the MQTT subscriber receives the raw value string. If the phone sent:

```bash
curl -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"value": "{\"c\":\"t90\",\"ts\":1711036800}"}'
```

The MQTT subscriber receives the string: `{"c":"t90","ts":1711036800}` — which is exactly what the mJS script expects to `JSON.parse()`.

**Important:** The value sent via REST must be wrapped in the Adafruit IO envelope `{"value": "..."}`. The inner JSON is a string value. On the MQTT side, only the inner string is delivered.

### 5.3 What REST consumers receive

When the phone reads the heartbeat via REST:

```bash
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data?limit=1" \
  -H "X-AIO-Key: ${AIO_KEY}"
```

The response is an array containing a data point object. The heartbeat JSON is in the `value` field as a string:

```json
[
  {
    "id": "0ABCDEF",
    "value": "{\"s\":\"on\",\"r\":74,\"mode\":\"sch\",\"sch\":0,\"h\":6,\"m\":10,\"ack\":\"ext\",\"ts\":1711000000,\"ntp\":true}",
    "feed_id": 12345,
    "feed_key": "heartbeat",
    "created_at": "2024-03-22T06:15:00Z",
    ...
  }
]
```

The phone extracts `response[0].value` and parses it as JSON.

**Simpler alternative:** The `/data/last` endpoint returns a single data point (not wrapped in an array):

```bash
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}"
```

This returns the same data point object directly, avoiding the need to index into an array.

---

## 6. Validation test plan

These tests should be run once during initial setup to confirm the architecture works end-to-end before writing the full mJS script.

### 6.1 Pre-requisites

- Adafruit IO account created with three feeds (command, config, heartbeat)
- Shelly Plug S Gen3 on the same wifi network as your computer
- `mosquitto_pub` and `mosquitto_sub` installed (for manual MQTT testing)
- `curl` for REST API testing

### 6.2 Test 1: REST API round-trip

**Goal:** Confirm feeds are accessible and data format works.

```bash
# Requires: source .env (see .env.example in repo root)

# Write a test value to the heartbeat feed
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"value": "{\"s\":\"off\",\"r\":0,\"mode\":\"\",\"sch\":0,\"h\":6,\"m\":10,\"ack\":\"\",\"ts\":1711000000,\"ntp\":true}"}'

# Read it back
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}" | python3 -m json.tool
```

**Expected:** The `value` field in the response matches the JSON string you sent.

### 6.3 Test 2: MQTT connectivity (from computer)

**Goal:** Confirm MQTT TLS connection works with Adafruit IO credentials.

```bash
# Requires: source .env (see .env.example in repo root)

# Terminal 1: Subscribe to command feed
mosquitto_sub -h io.adafruit.com -p 8883 \
  --capath /etc/ssl/certs \
  -u "${AIO_USER}" -P "${AIO_KEY}" \
  -t "${AIO_USER}/f/command" -v

# Terminal 2: Publish a test command
mosquitto_pub -h io.adafruit.com -p 8883 \
  --capath /etc/ssl/certs \
  -u "${AIO_USER}" -P "${AIO_KEY}" \
  -t "${AIO_USER}/f/command" \
  -m '{"c":"t90","ts":1711036800}'
```

**Expected:** Terminal 1 receives the message immediately.

### 6.4 Test 3: `/get` topic for latest value

**Goal:** Confirm the retain-replacement mechanism works.

```bash
# Requires: source .env (see .env.example in repo root)

# First, publish a config value via REST (so there's data in the feed)
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/config/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"value": "{\"v\":1,\"sch\":0,\"h\":6,\"m\":0,\"dur\":90,\"max\":180}"}'

# Terminal 1: Subscribe to config feed
mosquitto_sub -h io.adafruit.com -p 8883 \
  --capath /etc/ssl/certs \
  -u "${AIO_USER}" -P "${AIO_KEY}" \
  -t "${AIO_USER}/f/config" -v

# Terminal 2: Trigger /get
mosquitto_pub -h io.adafruit.com -p 8883 \
  --capath /etc/ssl/certs \
  -u "${AIO_USER}" -P "${AIO_KEY}" \
  -t "${AIO_USER}/f/config/get" \
  -m ""
```

**Expected:** Terminal 1 receives the config JSON that was previously posted via REST. This confirms that the device can retrieve the latest config on connect without MQTT retain.

### 6.5 Test 4: Shelly connects to Adafruit IO

**Goal:** Confirm the Shelly Gen3 can establish a TLS MQTT connection to Adafruit IO.

```bash
SHELLY="192.168.1.xxx"

# Step 1: Configure MQTT
curl -X POST -d '{
  "id": 1,
  "method": "Mqtt.SetConfig",
  "params": {
    "config": {
      "enable": true,
      "server": "io.adafruit.com:8883",
      "user": "YOUR_AIO_USERNAME",
      "pass": "YOUR_AIO_KEY",
      "ssl_ca": "ca.pem",
      "enable_rpc": false,
      "status_ntf": false,
      "rpc_ntf": false,
      "enable_control": false
    }
  }
}' http://${SHELLY}/rpc

# Step 2: Reboot
curl -X POST -d '{"id":1, "method":"Shelly.Reboot"}' http://${SHELLY}/rpc

# Step 3: Wait ~15 seconds, then check status
curl -s -X POST -d '{"id":1, "method":"Mqtt.GetStatus"}' http://${SHELLY}/rpc
```

**Expected:** `{"connected": true}` in the response.

**If connection fails:** Try `ssl_ca: "*"` to rule out TLS certificate issues. If that works, the Shelly's CA bundle may not include the CA that signed Adafruit IO's certificate. In that case, download the CA certificate and upload it via `Shelly.PutUserCA`, then set `ssl_ca: "user_ca.pem"`.

### 6.6 Test 5: Shelly mJS script publishes and subscribes

**Goal:** Confirm the mJS script can interact with Adafruit IO feeds.

Upload a minimal test script via the Shelly web UI (Settings → Scripts → New Script):

```javascript
// Minimal Adafruit IO test script
// Replace YOUR_USERNAME with your Adafruit IO username

let AIO_USER = "YOUR_USERNAME";
let CMD_TOPIC = AIO_USER + "/f/command";
let CFG_TOPIC = AIO_USER + "/f/config";
let HB_TOPIC  = AIO_USER + "/f/heartbeat";

MQTT.subscribe(CMD_TOPIC, function(topic, msg) {
  print("CMD:", msg);
});

MQTT.subscribe(CFG_TOPIC, function(topic, msg) {
  print("CFG:", msg);
});

// On startup, request latest config
Timer.set(5000, false, function() {
  MQTT.publish(CFG_TOPIC + "/get", "", 0, false);
  print("Requested config via /get");
});

// Publish a test heartbeat every 30 seconds
Timer.set(30000, true, function() {
  let hb = JSON.stringify({
    s: "off",
    r: 0,
    mode: "",
    sch: 0,
    h: 6,
    m: 0,
    ack: "",
    ts: Shelly.getComponentStatus("sys").unixtime,
    ntp: true
  });
  MQTT.publish(HB_TOPIC, hb, 1, false);
  print("HB published:", hb);
});
```

Then from your computer, send a test command via REST:

```bash
curl -s -X POST "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/command/data" \
  -H "X-AIO-Key: ${AIO_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"value": "{\"c\":\"t90\",\"ts\":1711036800}"}'
```

**Expected:** The Shelly's script console shows `CMD: {"c":"t90","ts":1711036800}`. The heartbeat feed on Adafruit IO shows data when you read it via REST.

### 6.7 Test 6: Cross-protocol verification

**Goal:** Confirm that data written via REST is readable via MQTT, and vice versa.

1. Phone writes command via REST → device receives via MQTT (test 5 covers this)
2. Device writes heartbeat via MQTT → phone reads via REST:

```bash
curl -s "https://io.adafruit.com/api/v2/${AIO_USER}/feeds/heartbeat/data/last" \
  -H "X-AIO-Key: ${AIO_KEY}" | python3 -m json.tool
```

**Expected:** The `value` field contains the heartbeat JSON published by the Shelly script.

---

## 7. Rate limit safety

### 7.1 What counts against the limit

Only **data modifications** count: creating, updating, or deleting data points. This means:

- MQTT PUBLISH to a feed topic → counts (1 per publish)
- REST POST to a feed's data endpoint → counts (1 per post)
- REST GET (reading data) → does **not** count
- MQTT SUBSCRIBE → does **not** count against data rate (has its own 100/min limit)
- `/get` topic publish → counts as 1 data read (unclear if it counts against the 30/min data rate — test during validation)

### 7.2 Our worst-case budget

From doc 03 section 5, a typical 90-minute coffee session:

| Action | Messages | Direction |
|---|---|---|
| Schedule fires → heartbeat | 1 | Device → AIO |
| Periodic heartbeats (every 5 min, 90 min) | 18 | Device → AIO |
| Timer expires → heartbeat | 1 | Device → AIO |
| Idle heartbeats (every 15 min, 1 hour) | 4 | Device → AIO |
| Phone sends 1 command | 1 | Phone → AIO |
| Phone reads heartbeat 5 times | 0 | Reads don't count |

**Total for a 2.5-hour window: ~25 data modifications.** Average rate: ~0.17/min. Far below 30/min.

### 7.3 Ban avoidance

Bans escalate: 30s, 60s, 90s, up to 1 hour. They're triggered by:
- More than 30 data modifications per minute
- More than 100 SUBSCRIBE requests per minute
- More than 10 failed SUBSCRIBE requests per minute
- More than 20 connection attempts per minute

Our device makes 2 SUBSCRIBE calls on connect (command + config) and connects at most once per boot. Even rapid reconnection during flaky wifi won't hit 20/min because the Shelly firmware has its own backoff.

---

## 8. Failure modes specific to Adafruit IO

| Scenario | Behavior | Recovery |
|---|---|---|
| Adafruit IO is down | Device continues on last KVS config. Timer runs locally. Physical button works. No remote control. | Shelly firmware auto-reconnects when service returns. |
| Rate limit exceeded | Publish rejected. Warning on `{username}/throttle`. | Wait. Our usage is far below the limit — this would indicate a script bug. |
| Temporary ban | All MQTT operations fail for 30s–1h. | Firmware auto-reconnects after ban expires. Script should not panic. |
| AIO key regenerated | MQTT auth fails. Device disconnects. | Reconfigure via local HTTP: `Mqtt.SetConfig` with new key. Requires physical access to same wifi. |
| Feed deleted accidentally | MQTT publishes fail. REST writes create a new feed automatically (if writing to a nonexistent feed). | Recreate the feed via web UI or REST. Device will recover on next publish/subscribe cycle. |
| Account suspended | All API access fails. | Contact Adafruit support. Device runs on KVS config indefinitely. |

---

## 9. API key management

### 9.1 Current state

Adafruit IO uses a single API key per account. The same key is used for both REST (as `X-AIO-Key` header) and MQTT (as the password field). Regenerating the key immediately invalidates the old one.

### 9.2 Key rotation procedure

If the key is compromised or needs to be rotated:

1. Generate a new key on Adafruit IO (key icon → Regenerate Key)
2. Update the phone-side scripts/shortcuts with the new key
3. Update the Shelly via local HTTP:

```bash
# Use values from .env, or set manually for a one-off rotation:
# SHELLY_IP is already in .env; NEW_KEY is the freshly regenerated key.
NEW_KEY="new_aio_key_here"

curl -X POST -d "{
  \"id\": 1,
  \"method\": \"Mqtt.SetConfig\",
  \"params\": {
    \"config\": {
      \"pass\": \"${NEW_KEY}\"
    }
  }
}" http://${SHELLY_IP}/rpc

curl -X POST -d '{"id":1, "method":"Shelly.Reboot"}' http://${SHELLY_IP}/rpc
```

This requires the phone/computer to be on the same wifi as the Shelly. There is no way to remotely rotate the Shelly's MQTT credentials — once the old key is invalidated, the device loses MQTT connectivity until reconfigured locally.

### 9.3 Key exposure risk

The AIO key appears in:
- Shelly's MQTT configuration (stored on-device, not visible via `Mqtt.GetConfig` — the `pass` field is redacted)
- Phone-side Android app settings, curl commands
- Adafruit IO dashboard URLs (if using query parameter auth — avoid this)

**Mitigation:** Always use the `X-AIO-Key` header for REST calls, never the URL query parameter. Keep the key out of version control — all credentials live in the gitignored `.env` file (see §7 of doc 10).

---

## 10. Adafruit IO dashboard (free bonus UI)

Adafruit IO includes a web-based dashboard builder. While our primary phone interface is designed separately (doc 06), the built-in dashboard provides a zero-effort status display and basic control surface during development and as a fallback.

### 10.1 Useful dashboard blocks

| Block type | Feed | Purpose |
|---|---|---|
| Text | heartbeat | Show raw heartbeat JSON (debugging) |
| Gauge | heartbeat | Show remaining timer (requires parsing — limited without custom formatting) |
| Toggle | command | Send on/off (limited — can only send predefined values, not our JSON format) |
| Stream | heartbeat | Scrolling log of heartbeat updates |

### 10.2 Limitations for our use case

The dashboard blocks expect simple scalar values (numbers, strings). Our feeds contain JSON strings, which the dashboard displays as raw text. This is fine for debugging but not a great user experience. The purpose-built phone interface (doc 06) will handle proper presentation.

The dashboard is most useful as a "is it working?" monitor — you can see heartbeat updates arriving in real time without any setup.

---

## 11. Decisions made

| # | Decision | Rationale |
|---|---|---|
| D04.27 | Use Adafruit IO `/get` topic to fetch latest config on MQTT connect | Adafruit IO does not support MQTT retain; `/get` is the documented equivalent |
| D04.28 | Heartbeat retrieval via REST is unaffected by lack of retain | Phone reads from the data API, which returns the latest stored value |
| D04.29 | No architectural changes needed to feeds, data flow, or authority model | The `/get` workaround is a one-line addition to the device's MQTT connect handler |
| D04.30 | Use `ssl_ca: "ca.pem"` for TLS with Shelly's built-in CA bundle | Adafruit IO uses a publicly-trusted certificate; avoids manual CA upload |
| D04.31 | Disable Shelly's built-in MQTT *application features* (RPC, status_ntf, enable_control) while relying on the firmware's MQTT transport layer (connection, TLS, auto-reconnect, subscribe/publish APIs) | The firmware manages the MQTT connection and exposes `MQTT.subscribe()`/`MQTT.publish()` to mJS — we depend on this. But the higher-level features layered on top (RPC-over-MQTT, status broadcasting, direct switch control) would bypass our script's safety logic, waste rate limit budget, and pollute the topic space. |
| D04.32 | Leave `topic_prefix` as default (device ID) | Script addresses Adafruit IO feeds by full topic path; prefix is irrelevant for our feeds |
| D04.33 | Use `/f/` short topic form on device | Saves bytes on every publish/subscribe; matters on constrained ESP32 |
| D04.34 | Use `/data/last` REST endpoint for phone-side heartbeat reads | Returns single object (not array); simpler parsing |
| D04.35 | Key rotation requires local wifi access to the Shelly | No remote credential update mechanism exists; accepted trade-off for zero-infrastructure design |

---

## 12. Open items for next layer (doc 05: on-device state machine)

- [ ] mJS MQTT connect handler must subscribe to command + config, then publish to `config/get`
- [ ] MQTT message handler must route by topic (command vs config) and parse JSON
- [ ] Heartbeat publish function must use QoS 1 for reliable delivery
- [ ] Script should monitor `{username}/errors` during development (optional in production)
- [ ] Test whether the Shelly's `sys.unixtime` is available immediately or only after NTP sync
- [ ] Confirm `MQTT.publish()` behavior when MQTT is disconnected (does it silently fail or throw?)
- [ ] Test reconnection behavior: does the Shelly firmware auto-reconnect, and does it re-trigger subscriptions?
