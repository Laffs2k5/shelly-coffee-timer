// Black-box unit tests for device/coffee.js v2, run against the mocked Shelly
// runtime in harness.js. Run: node --test device/test/
const { test } = require("node:test");
const assert = require("node:assert");
const { createDevice, T } = require("./harness.js");

const NOW = 1780000000; // matches harness default sys.unixtime

function status(dev) {
  return JSON.parse(dev.http("coffee_status").body);
}

// v2 persists runtime timer state as one compact JSON KVS key "rt" = {on,r,m}.
function rt(dev) {
  const v = dev.state.kvs.rt;
  return v ? JSON.parse(v) : {};
}
function rtJson(on, r, m) {
  return JSON.stringify({ on: on, r: r, m: m || "" });
}

// ---------------------------------------------------------------------------
// Phase 1 — transport / topics / retain / alive
// ---------------------------------------------------------------------------

test("boot subscribes to exactly command + config (no /get topic)", () => {
  const dev = createDevice();
  const subs = Object.keys(dev.state.subs);
  assert.deepStrictEqual(subs.sort(), [T.cmd, T.cfg].sort());
  // no config/get workaround anywhere
  assert.ok(!dev.state.published.some((p) => /\/get$/.test(p.topic)));
});

test("heartbeat publishes to devices/<id>/heartbeat with retain=true", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "on", ts: NOW });
  const hb = dev.lastTo(T.hb);
  assert.ok(hb, "a heartbeat was published");
  assert.strictEqual(hb.retain, true, "heartbeat is retained");
  const body = JSON.parse(hb.payload);
  assert.strictEqual(body.s, "on");
  assert.strictEqual(body.ack, "on");
  assert.strictEqual(body.ntp, true);
});

test("alive publishes to mon/<id>/alive non-retained every 60s, NTP-independent", () => {
  const dev = createDevice({ unixtime: 0 }); // NTP NOT synced
  dev.setNtp(false);
  dev.ticks(2); // 2 * 30s = 60s
  const alive = dev.publishesTo(T.alive);
  assert.strictEqual(alive.length, 1, "exactly one alive in 60s");
  assert.strictEqual(alive[0].retain, false);
  assert.strictEqual(alive[0].qos, 0);
  // heartbeat must NOT publish without NTP, but alive must
  assert.strictEqual(dev.publishesTo(T.hb).length, 0);
});

test("commands: t90/ext/sub/off behave correctly", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "t90", ts: NOW });
  assert.strictEqual(status(dev).remaining, 90);
  dev.sendCommand({ c: "ext", ts: NOW });
  assert.strictEqual(status(dev).remaining, 120);
  dev.sendCommand({ c: "sub", ts: NOW });
  assert.strictEqual(status(dev).remaining, 90);
  dev.sendCommand({ c: "off", ts: NOW });
  assert.strictEqual(status(dev).state, "off");
  assert.strictEqual(dev.state.relay, false);
});

test("timer is capped at cfg_max", () => {
  const dev = createDevice({ kvs: { cfg_v: 1, cfg_max: 180 } });
  // ext repeatedly should never exceed 180
  dev.sendCommand({ c: "t90", ts: NOW });
  for (let i = 0; i < 10; i++) dev.sendCommand({ c: "ext", ts: NOW });
  assert.ok(status(dev).remaining <= 180);
});

// ---------------------------------------------------------------------------
// Phase 1 — staleness + config version gating (unchanged invariants)
// ---------------------------------------------------------------------------

test("malformed command JSON is ignored (mJS JSON.parse -> undefined)", () => {
  const dev = createDevice();
  dev.sendCommandRaw("not json at all");
  dev.sendCommandRaw("");
  assert.strictEqual(dev.state.relay, false);
});

test("stale command is rejected", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "on", ts: NOW - 1000 }); // delta 1000s > 120
  assert.strictEqual(dev.state.relay, false);
});

test("config version gating: lower/equal version ignored", () => {
  const dev = createDevice();
  dev.sendConfig({ v: 5, sch: 1, h: 7, m: 30, dur: 90, max: 180 });
  let s = status(dev);
  assert.strictEqual(s.sch, 1);
  assert.strictEqual(s.h, 7);
  dev.sendConfig({ v: 3, sch: 0, h: 9, m: 0 }); // lower version
  s = status(dev);
  assert.strictEqual(s.sch, 1, "lower-version config ignored");
  assert.strictEqual(s.h, 7);
});

test("schedule fires at matching time then disarms", () => {
  const dev = createDevice();
  dev.sendConfig({ v: 2, sch: 1, h: 8, m: 15, dur: 90, max: 180 });
  dev.setClock(8, 15);
  dev.tick();
  const s = status(dev);
  assert.strictEqual(s.state, "on");
  assert.strictEqual(s.mode, "sch");
  assert.strictEqual(s.sch, 0, "schedule disarmed after firing");
});

// ---------------------------------------------------------------------------
// Phase 2 — persistence + boot/resume reset_reason gate
// ---------------------------------------------------------------------------

test("RESUME: software reboot (reset_reason=3) with persisted ON timer resumes", () => {
  const dev = createDevice({
    reset_reason: 3,
    kvs: { rt: rtJson(1, 900, "manual") },
  });
  assert.strictEqual(dev.state.relay, true, "relay resumed ON");
  const s = status(dev);
  assert.strictEqual(s.state, "on");
  assert.strictEqual(s.remaining, 15); // 900s
  assert.strictEqual(s.mode, "manual");
});

test("NO RESUME: power loss (reset_reason=1) boots OFF and clears rt", () => {
  const dev = createDevice({
    reset_reason: 1,
    kvs: { rt: rtJson(1, 900, "manual") },
  });
  assert.strictEqual(dev.state.relay, false, "relay OFF after power loss");
  assert.strictEqual(status(dev).state, "off");
  assert.strictEqual(rt(dev).on, 0, "rt.on cleared");
  assert.strictEqual(rt(dev).r, 0, "rt.r cleared");
});

test("NO RESUME: software reboot but nothing was running (rt.on=0)", () => {
  const dev = createDevice({ reset_reason: 3, kvs: { rt: rtJson(0, 0) } });
  assert.strictEqual(dev.state.relay, false);
});

test("NO RESUME: software reboot but rt.r=0", () => {
  const dev = createDevice({ reset_reason: 3, kvs: { rt: rtJson(1, 0) } });
  assert.strictEqual(dev.state.relay, false);
});

test("NO RESUME on unknown reset_reason (degrade-to-safe)", () => {
  const dev = createDevice({ reset_reason: 9, kvs: { rt: rtJson(1, 900, "manual") } });
  assert.strictEqual(dev.state.relay, false);
});

test("turn_on persists rt {on:1, r}", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "t90", ts: NOW });
  assert.strictEqual(rt(dev).on, 1);
  assert.strictEqual(rt(dev).r, 90 * 60);
});

test("countdown persists rt.r each minute while ON", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "t90", ts: NOW });
  dev.ticks(2); // 60s elapsed
  assert.strictEqual(rt(dev).r, 89 * 60);
});

test("no periodic rt write churn while OFF", () => {
  const dev = createDevice();
  const before = dev.state.kvs.rt; // value after boot's clear_rt
  dev.ticks(10); // 5 minutes OFF
  assert.strictEqual(dev.state.kvs.rt, before, "rt untouched while OFF");
});

test("auto-off at zero turns relay off and persists", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "ext", ts: NOW }); // 30 min on (not running -> turn_on 30)
  assert.strictEqual(status(dev).remaining, 30);
  dev.ticks(60); // 30 minutes
  assert.strictEqual(dev.state.relay, false);
  assert.strictEqual(rt(dev).on, 0);
});

// ---------------------------------------------------------------------------
// Phase 3 — two-tier connectivity watchdog
// ---------------------------------------------------------------------------

test("Wi-Fi tier: reboot after 900s of Wi-Fi down", () => {
  const dev = createDevice();
  dev.setWifi("disconnected");
  dev.ticks(29); // 870s
  assert.strictEqual(dev.state.reboots, 0, "no reboot before threshold");
  dev.tick(); // 900s
  assert.strictEqual(dev.state.reboots, 1, "reboot at threshold");
});

test("Wi-Fi tier: recovery resets the counter (no reboot)", () => {
  const dev = createDevice();
  dev.setWifi("disconnected");
  dev.ticks(20); // 600s down
  dev.setWifi("got ip"); // recovered
  dev.ticks(40); // 1200s up
  assert.strictEqual(dev.state.reboots, 0);
});

test("Broker tier: reboot after 10800s broker-down while Wi-Fi up", () => {
  const dev = createDevice();
  dev.setMqtt(false); // broker unreachable, Wi-Fi stays up
  dev.ticks(359); // 10770s
  assert.strictEqual(dev.state.reboots, 0, "no reboot before broker threshold");
  dev.tick(); // 10800s
  assert.strictEqual(dev.state.reboots, 1);
});

test("Nightly Pi maintenance blip does NOT reboot (broker down 1h, Wi-Fi up)", () => {
  const dev = createDevice();
  dev.setMqtt(false);
  dev.ticks(120); // 3600s = 1h, well under 10800s
  assert.strictEqual(dev.state.reboots, 0);
});

test("Healthy connectivity never reboots", () => {
  const dev = createDevice(); // wifi up + mqtt up
  dev.ticks(500);
  assert.strictEqual(dev.state.reboots, 0);
});

// ---------------------------------------------------------------------------
// Physical button (firmware toggles switch directly)
// ---------------------------------------------------------------------------

test("physical button ON syncs state and persists", () => {
  const dev = createDevice();
  // firmware reports switch went on, not from our script
  dev.fireStatus({ component: "switch:0", delta: { output: true } });
  const s = status(dev);
  assert.strictEqual(s.state, "on");
  assert.strictEqual(s.mode, "manual");
  assert.strictEqual(rt(dev).on, 1);
});

test("script-initiated switch change is ignored by button handler", () => {
  const dev = createDevice();
  dev.sendCommand({ c: "on", ts: NOW }); // sets script_switching during Switch.Set
  // after the call script_switching is cleared; a subsequent genuine button
  // press OFF should register
  dev.fireStatus({ component: "switch:0", delta: { output: false } });
  assert.strictEqual(status(dev).state, "off");
});
