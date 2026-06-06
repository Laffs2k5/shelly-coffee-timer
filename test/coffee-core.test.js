// Unit tests for the web fallback's pure logic (web/coffee-core.js).
// The MQTT-over-WSS connection + DOM wiring in index.html is NOT covered here
// (needs a live broker/browser); these cover payload/topic/parse correctness.
const { test } = require("node:test");
const assert = require("node:assert");
const C = require("../coffee-core.js");

test("topics match the device subscriptions", () => {
  const t = C.topics();
  assert.strictEqual(t.command, "devices/YOUR_DEVICE_ID/command");
  assert.strictEqual(t.config, "devices/YOUR_DEVICE_ID/config");
  assert.strictEqual(t.heartbeat, "devices/YOUR_DEVICE_ID/heartbeat");
});

test("buildCommand produces {c, ts}", () => {
  const obj = JSON.parse(C.buildCommand("t90", 1780000000));
  assert.deepStrictEqual(obj, { c: "t90", ts: 1780000000 });
});

test("buildConfig bumps version and preserves dur/max", () => {
  const cur = { v: 7, dur: 120, max: 180, sch: 0, h: 6, m: 0 };
  const next = C.buildConfig(cur, { sch: true, h: "08", m: "15" });
  assert.strictEqual(next.v, 8);
  assert.strictEqual(next.sch, 1);
  assert.strictEqual(next.h, 8);
  assert.strictEqual(next.m, 15);
  assert.strictEqual(next.dur, 120);
  assert.strictEqual(next.max, 180);
});

test("buildConfig defaults when no current config", () => {
  const next = C.buildConfig(null, { sch: false, h: "", m: "" });
  assert.strictEqual(next.v, 1);
  assert.strictEqual(next.sch, 0);
  assert.strictEqual(next.h, 0);
  assert.strictEqual(next.m, 0);
  assert.strictEqual(next.dur, 90);
  assert.strictEqual(next.max, 180);
});

test("parseHeartbeat: ON state", () => {
  const m = C.parseHeartbeat(JSON.stringify({ s: "on", r: 42, ack: "t90", sch: 1, h: 7, m: 0, ts: 123 }));
  assert.strictEqual(m.state, "on");
  assert.strictEqual(m.statusText, "ON with 42 min to go");
  assert.strictEqual(m.ackText, "Last command: 90");
  assert.strictEqual(m.sch, 1);
});

test("parseHeartbeat: OFF + ack mapping for ext/sub", () => {
  assert.strictEqual(C.parseHeartbeat(JSON.stringify({ s: "off", ack: "ext" })).ackText, "Last command: +30");
  assert.strictEqual(C.parseHeartbeat(JSON.stringify({ s: "off", ack: "sub" })).statusText, "OFF");
});

test("parseHeartbeat: malformed -> null", () => {
  assert.strictEqual(C.parseHeartbeat("garbage"), null);
  assert.strictEqual(C.parseHeartbeat("123"), null);
});

test("brokerUrl is wss on 8084 /mqtt", () => {
  assert.strictEqual(C.brokerUrl("ex.emqxsl.com"), "wss://ex.emqxsl.com:8084/mqtt");
});
