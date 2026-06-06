// Pure, transport-agnostic helpers for the web fallback page.
// No DOM, no network — so they're unit-testable in Node (see web/test/).
// Loaded by index.html as a plain <script> (exposes window.CoffeeCore) and by
// node tests via require() (module.exports).
(function (root) {
  "use strict";

  var DEVICE = "YOUR_DEVICE_ID";

  function topics(device) {
    device = device || DEVICE;
    return {
      command: "devices/" + device + "/command",   // publish, retain=false
      config: "devices/" + device + "/config",      // publish, retain=true
      heartbeat: "devices/" + device + "/heartbeat" // subscribe, retained
    };
  }

  // Command payload (matches device on_mqtt_command: {c, ts}). retain=false at publish.
  function buildCommand(cmd, nowSec) {
    return JSON.stringify({ c: cmd, ts: nowSec });
  }

  // Read-modify-write config with a monotonic version bump; preserve dur/max.
  function buildConfig(current, ui) {
    current = current || {};
    var h = parseInt(ui.h, 10);
    var m = parseInt(ui.m, 10);
    return {
      v: (current.v || 0) + 1,
      sch: ui.sch ? 1 : 0,
      h: isNaN(h) ? 0 : h,
      m: isNaN(m) ? 0 : m,
      dur: current.dur || 90,
      max: current.max || 180
    };
  }

  var ACK_MAP = { ext: "+30", sub: "-30", off: "off", t90: "90", sch: "schedule", on: "on", btn: "button" };

  // Parse a retained heartbeat value into a display model for the UI.
  function parseHeartbeat(valueString) {
    var val;
    try { val = JSON.parse(valueString); } catch (e) { return null; }
    if (!val || typeof val !== "object") return null;
    return {
      state: val.s === "on" ? "on" : "off",
      statusText: val.s === "on" ? ("ON with " + val.r + " min to go") : "OFF",
      ackText: val.ack ? ("Last command: " + (ACK_MAP[val.ack] || val.ack)) : "",
      sch: typeof val.sch !== "undefined" ? val.sch : null,
      h: typeof val.h !== "undefined" ? val.h : null,
      m: typeof val.m !== "undefined" ? val.m : null,
      ts: typeof val.ts === "number" ? val.ts : null
    };
  }

  // wss URL for EMQX Cloud Serverless (browser supplies TLS + ALPN). Port 8084, path /mqtt.
  function brokerUrl(host) {
    return "wss://" + host + ":8084/mqtt";
  }

  var api = {
    DEVICE: DEVICE,
    DEFAULT_HOST: "your-deployment.emqxsl.com",
    topics: topics,
    buildCommand: buildCommand,
    buildConfig: buildConfig,
    parseHeartbeat: parseHeartbeat,
    brokerUrl: brokerUrl,
    ACK_MAP: ACK_MAP
  };

  if (typeof module !== "undefined" && module.exports) { module.exports = api; }
  root.CoffeeCore = api;
})(typeof window !== "undefined" ? window : this);
