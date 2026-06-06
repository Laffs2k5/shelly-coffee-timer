// Test harness: loads device/coffee.js into a sandboxed VM context with a mocked
// Shelly runtime, so the mJS logic can be black-box tested in Node (node:test).
//
// We drive INPUTS (MQTT messages, timer ticks, status events, HTTP calls, the
// reset_reason / KVS preconditions at boot) and assert on captured OUTPUTS
// (Switch.Set relay state, MQTT.publish calls, KVS writes, HTTP responses,
// Shelly.Reboot calls). Top-level `function` declarations in coffee.js become
// callable on the VM global; `let` state vars are intentionally observed only
// through outputs (true black-box).

const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

const COFFEE = path.join(__dirname, "..", "coffee.js");
const DEVICE = "YOUR_DEVICE_ID";
const T = {
  cmd: "devices/" + DEVICE + "/command",
  cfg: "devices/" + DEVICE + "/config",
  hb: "devices/" + DEVICE + "/heartbeat",
  alive: "mon/" + DEVICE + "/alive",
};

// Build a device instance. opts: { kvs, reset_reason, unixtime, uptime, wifi, mqtt }
function createDevice(opts) {
  opts = opts || {};
  const state = {
    relay: false,
    script_switch_calls: [], // {on} for every Switch.Set
    kvs: Object.assign({}, opts.kvs || {}),
    sys: {
      unixtime: opts.unixtime != null ? opts.unixtime : 1780000000,
      uptime: opts.uptime != null ? opts.uptime : 5,
      reset_reason: opts.reset_reason != null ? opts.reset_reason : 1,
    },
    wifi: { status: opts.wifi != null ? opts.wifi : "got ip" },
    mqttConnected: opts.mqtt != null ? opts.mqtt : true,
    published: [], // {topic, payload, qos, retain}
    subs: {},
    endpoints: {},
    statusHandlers: [],
    timers: [],
    reboots: 0,
    clock: { h: 0, m: 0 },
    logs: [],
  };

  const Shelly = {
    call: function (method, params, cb) {
      let res = null;
      const err = 0;
      if (method === "Switch.Set") {
        state.relay = !!(params && params.on);
        state.script_switch_calls.push({ on: state.relay });
        res = {};
      } else if (method === "KVS.Get") {
        res = Object.prototype.hasOwnProperty.call(state.kvs, params.key)
          ? { value: state.kvs[params.key] }
          : {};
      } else if (method === "KVS.Set") {
        state.kvs[params.key] = params.value;
        res = {};
      } else if (method === "KVS.Delete") {
        delete state.kvs[params.key];
        res = {};
      } else if (method === "Shelly.Reboot") {
        state.reboots++;
        res = {};
      }
      if (cb) cb(res, err, ""); // synchronous callbacks → deterministic tests
    },
    getComponentStatus: function (name) {
      if (name === "sys") {
        return { unixtime: state.sys.unixtime, uptime: state.sys.uptime, reset_reason: state.sys.reset_reason };
      }
      if (name === "wifi") return { status: state.wifi.status };
      if (name === "mqtt") return { connected: state.mqttConnected };
      return null;
    },
    addStatusHandler: function (cb) { state.statusHandlers.push(cb); },
  };

  const MQTT = {
    publish: function (topic, payload, qos, retain) {
      state.published.push({ topic: topic, payload: payload, qos: qos, retain: retain });
    },
    subscribe: function (topic, cb) { state.subs[topic] = cb; },
  };

  const Timer = {
    set: function (ms, repeat, cb) { state.timers.push({ ms: ms, repeat: repeat, cb: cb }); return state.timers.length; },
  };

  const HTTPServer = {
    registerEndpoint: function (name, cb) { state.endpoints[name] = cb; },
  };

  function print() {
    state.logs.push(Array.prototype.join.call(arguments, " "));
  }

  // Minimal Date mock: coffee.js only uses new Date().getHours()/getMinutes() for the schedule.
  function FakeDate() {
    return { getHours: function () { return state.clock.h; }, getMinutes: function () { return state.clock.m; } };
  }

  // mJS JSON.parse returns undefined on malformed input (does not throw). Emulate that
  // so the device code (which relies on it) behaves identically under test.
  const mjsJSON = {
    parse: function (s) { try { return JSON.parse(s); } catch (e) { return undefined; } },
    stringify: function (v) { return JSON.stringify(v); },
  };
  const sandbox = { Shelly: Shelly, MQTT: MQTT, Timer: Timer, HTTPServer: HTTPServer, print: print, Date: FakeDate, JSON: mjsJSON, Math: Math, parseInt: parseInt, parseFloat: parseFloat };
  vm.createContext(sandbox);
  const src = fs.readFileSync(COFFEE, "utf8");
  vm.runInContext(src, sandbox, { filename: "coffee.js" }); // runs boot synchronously

  function mkRes() {
    return { code: 0, headers: null, body: null, sent: false, send: function () { this.sent = true; } };
  }

  return {
    state: state,
    T: T,
    // main_loop is the (only) 30s repeating timer callback
    tick: function () { state.timers[0].cb(); },
    ticks: function (n) { for (let i = 0; i < n; i++) state.timers[0].cb(); },
    sendCommand: function (obj) { state.subs[T.cmd](T.cmd, JSON.stringify(obj)); },
    sendCommandRaw: function (s) { state.subs[T.cmd](T.cmd, s); },
    sendConfig: function (obj) { state.subs[T.cfg](T.cfg, JSON.stringify(obj)); },
    fireStatus: function (ev) { for (let i = 0; i < state.statusHandlers.length; i++) state.statusHandlers[i](ev); },
    http: function (name, query) { const res = mkRes(); state.endpoints[name]({ query: query || "" }, res); return res; },
    setClock: function (h, m) { state.clock.h = h; state.clock.m = m; },
    setWifi: function (status) { state.wifi.status = status; },
    setMqtt: function (connected) { state.mqttConnected = connected; },
    setNtp: function (synced) { state.sys.unixtime = synced ? 1780000000 : 0; },
    publishesTo: function (topic) { return state.published.filter(function (p) { return p.topic === topic; }); },
    lastTo: function (topic) { const a = this.publishesTo(topic); return a.length ? a[a.length - 1] : null; },
  };
}

module.exports = { createDevice: createDevice, T: T, DEVICE: DEVICE };
