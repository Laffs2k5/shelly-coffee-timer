// Shelly Coffee Timer - mJS script for Shelly Plug S Gen3
// See docs/spec/05-state-machine.md for full design
//
// IMPORTANT: Replace YOUR_AIO_USERNAME with your real Adafruit IO username
// before uploading to the device.

let AIO_USER = "YOUR_AIO_USERNAME";
let TOPIC_CMD = AIO_USER + "/f/command";
let TOPIC_CFG = AIO_USER + "/f/config";
let TOPIC_HB = AIO_USER + "/f/heartbeat";
let TOPIC_CFG_GET = TOPIC_CFG + "/get";

let STALE_SEC = 120;

// In-memory state
let sw_on = false;
let remain = 0;
let mode = "";
let last_ack = "";
let ntp_synced = false;

// KVS-persisted config (defaults)
let cfg_v = 0;
let cfg_sch = 0;
let cfg_h = 6;
let cfg_m = 0;
let cfg_dur = 90;
let cfg_max = 180;

// Heartbeat tracking
let hb_last_ts = 0;
let hb_elapsed = 0;
let mqtt_init_done = false;

// Flag to distinguish script-initiated switch changes from physical button
let script_switching = false;

// Helper: get unix timestamp
function get_unixtime() {
  let s = Shelly.getComponentStatus("sys");
  if (s) return s.unixtime;
  return 0;
}

// Helper: parse query param from query string
function get_query_param(qs, name) {
  if (typeof qs !== "string" || qs.length === 0) return "";
  let search = name + "=";
  let idx = qs.indexOf(search);
  if (idx < 0) return "";
  let start = idx + search.length;
  let end = qs.indexOf("&", start);
  if (end < 0) end = qs.length;
  return qs.slice(start, end);
}

// Core: turn on
function turn_on(duration_min, new_mode) {
  if (duration_min > cfg_max) duration_min = cfg_max;
  remain = duration_min * 60;
  mode = new_mode;
  sw_on = true;
  script_switching = true;
  Shelly.call("Switch.Set", {id: 0, on: true}, function() {
    script_switching = false;
  });
}

// Core: turn off
function turn_off() {
  remain = 0;
  mode = "";
  sw_on = false;
  script_switching = true;
  Shelly.call("Switch.Set", {id: 0, on: false}, function() {
    script_switching = false;
  });
}

// Core: execute command
function execute_command(cmd) {
  if (cmd === "on" || cmd === "t90") {
    turn_on(cfg_dur, "remote");
  } else if (cmd === "off") {
    if (sw_on) turn_off();
  } else if (cmd === "ext") {
    if (sw_on) {
      let nr = remain + 30 * 60;
      let mr = cfg_max * 60;
      if (nr > mr) nr = mr;
      remain = nr;
    } else {
      turn_on(30, "remote");
    }
  } else if (cmd === "sub") {
    if (sw_on) {
      remain = remain - 30 * 60;
      if (remain <= 0) turn_off();
    }
  }
}

// Heartbeat publish with 2-second debounce
function do_publish_heartbeat() {
  if (!ntp_synced) return;
  let now = get_unixtime();
  if (now - hb_last_ts < 2) return;
  hb_last_ts = now;
  let st = "off";
  if (sw_on) st = "on";
  let hb = JSON.stringify({
    s: st,
    r: Math.floor(remain / 60),
    mode: mode,
    sch: cfg_sch,
    h: cfg_h,
    m: cfg_m,
    ack: last_ack,
    ts: now,
    ntp: true
  });
  MQTT.publish(TOPIC_HB, hb, 1, false);
  hb_elapsed = 0;
}

function publish_heartbeat() {
  do_publish_heartbeat();
}

// MQTT command handler
function on_mqtt_command(topic, message) {
  let msg = JSON.parse(message);
  if (typeof msg !== "object" || msg === null) return;
  if (!ntp_synced) return;
  let now = get_unixtime();
  if (typeof msg.ts !== "number") return;
  let delta = now - msg.ts;
  if (delta < 0) delta = -delta;
  if (delta > STALE_SEC) return;
  if (typeof msg.c !== "string") return;
  execute_command(msg.c);
  last_ack = msg.c;
  publish_heartbeat();
}

// Sequential KVS saver
let save_queue = [];
let saving = false;

function save_next() {
  if (save_queue.length === 0) {
    saving = false;
    return;
  }
  saving = true;
  let item = save_queue[0];
  // shift manually since no Array.shift in mJS
  let nq = [];
  let i = 1;
  while (i < save_queue.length) {
    nq.push(save_queue[i]);
    i = i + 1;
  }
  save_queue = nq;
  Shelly.call("KVS.Set", {key: item.k, value: item.v}, function() {
    save_next();
  });
}

function kvs_save(key, val) {
  save_queue.push({k: key, v: val});
  if (!saving) save_next();
}

// MQTT config handler
function on_mqtt_config(topic, message) {
  let msg = JSON.parse(message);
  if (typeof msg !== "object" || msg === null) return;
  if (typeof msg.v !== "number") return;
  if (msg.v <= cfg_v) return;
  cfg_v = msg.v;
  if (typeof msg.sch === "number") cfg_sch = msg.sch;
  if (typeof msg.h === "number") cfg_h = msg.h;
  if (typeof msg.m === "number") cfg_m = msg.m;
  if (typeof msg.dur === "number") cfg_dur = msg.dur;
  if (typeof msg.max === "number") cfg_max = msg.max;
  kvs_save("cfg_v", cfg_v);
  kvs_save("cfg_sch", cfg_sch);
  kvs_save("cfg_h", cfg_h);
  kvs_save("cfg_m", cfg_m);
  kvs_save("cfg_dur", cfg_dur);
  kvs_save("cfg_max", cfg_max);
  if (sw_on && remain > cfg_max * 60) {
    remain = cfg_max * 60;
  }
  publish_heartbeat();
}

// MQTT connect handler
function on_mqtt_connect() {
  MQTT.publish(TOPIC_CFG_GET, "", 0, false);
  do_publish_heartbeat();
}

// Main loop: single 30-second repeating timer handles everything
// - Tick countdown (every 2 cycles = 60s)
// - Schedule check (every cycle = 30s)
// - Periodic heartbeat (counter-based)
// - Cooldown decrement
let tick_counter = 0;

function main_loop() {
  // Tick countdown (every 2 cycles = 60 seconds)
  tick_counter = tick_counter + 1;
  if (tick_counter >= 2) {
    tick_counter = 0;
    if (sw_on) {
      remain = remain - 60;
      if (remain < 0) remain = 0;
      if (remain <= 0) {
        turn_off();
        publish_heartbeat();
      }
    }
  }

  // Periodic heartbeat
  hb_elapsed = hb_elapsed + 30;
  let hb_interval = 900;
  if (sw_on) hb_interval = 300;
  if (hb_elapsed >= hb_interval) {
    hb_elapsed = 0;
    do_publish_heartbeat();
  }

  // Schedule check
  if (cfg_sch === 1 && ntp_synced && !sw_on) {
    let d = new Date();
    let h = d.getHours();
    let m = d.getMinutes();
    if (h === cfg_h && m === cfg_m) {
      cfg_sch = 0;
      kvs_save("cfg_sch", 0);
      turn_on(cfg_dur, "sch");
      publish_heartbeat();
    }
  }

  // MQTT init (delayed, runs once ~30s after boot)
  if (!mqtt_init_done) {
    mqtt_init_done = true;
    let mqtt = Shelly.getComponentStatus("mqtt");
    if (mqtt && mqtt.connected === true) {
      on_mqtt_connect();
    }
  }
}

// Boot complete: set up everything
function boot_complete() {
  // Force switch OFF on boot (safety)
  script_switching = true;
  Shelly.call("Switch.Set", {id: 0, on: false}, function() {
    script_switching = false;
  });

  // MQTT subscriptions
  MQTT.subscribe(TOPIC_CMD, on_mqtt_command);
  MQTT.subscribe(TOPIC_CFG, on_mqtt_config);

  // Single main loop timer
  Timer.set(30000, true, main_loop);

  // Combined status handler: NTP sync, MQTT connect, physical button
  Shelly.addStatusHandler(function(event) {
    // NTP sync detection
    if (event.component === "sys") {
      if (event.delta && typeof event.delta.unixtime === "number") {
        if (event.delta.unixtime > 1700000000) {
          ntp_synced = true;
        }
      }
    }
    // MQTT connect
    if (event.component === "mqtt") {
      if (event.delta && event.delta.connected === true) {
        on_mqtt_connect();
      }
    }
    // Physical button detection
    // The Plug S Gen3 has no separate Input component; the button toggles
    // the switch directly in firmware. We use script_switching flag to
    // distinguish our Switch.Set calls from physical button presses.
    if (event.component === "switch:0") {
      if (event.delta && typeof event.delta.output !== "undefined") {
        if (script_switching) return;
        // Physical button was pressed - firmware already toggled the switch
        if (event.delta.output) {
          remain = cfg_dur * 60;
          mode = "manual";
          sw_on = true;
        } else {
          remain = 0;
          mode = "";
          sw_on = false;
        }
        last_ack = "btn";
        publish_heartbeat();
      }
    }
  });

  // HTTP endpoints
  HTTPServer.registerEndpoint("coffee_status", function(req, res) {
    let ts = 0;
    if (ntp_synced) ts = get_unixtime();
    let st = "off";
    if (sw_on) st = "on";
    res.code = 200;
    res.headers = [["Content-Type", "application/json"]];
    res.body = JSON.stringify({
      state: st,
      remaining: Math.floor(remain / 60),
      mode: mode,
      sch: cfg_sch,
      h: cfg_h,
      m: cfg_m,
      ntp: ntp_synced,
      ts: ts
    });
    res.send();
  });

  HTTPServer.registerEndpoint("coffee_command", function(req, res) {
    let cmd = get_query_param(req.query, "cmd");
    if (cmd === "") {
      res.code = 400;
      res.headers = [["Content-Type", "application/json"]];
      res.body = JSON.stringify({ok: false, error: "missing cmd"});
      res.send();
      return;
    }
    if (cmd !== "on" && cmd !== "off" && cmd !== "ext" && cmd !== "sub" && cmd !== "t90") {
      res.code = 400;
      res.headers = [["Content-Type", "application/json"]];
      res.body = JSON.stringify({ok: false, error: "unknown command"});
      res.send();
      return;
    }
    execute_command(cmd);
    last_ack = cmd;
    publish_heartbeat();
    let st = "off";
    if (sw_on) st = "on";
    res.code = 200;
    res.headers = [["Content-Type", "application/json"]];
    res.body = JSON.stringify({
      ok: true,
      state: st,
      remaining: Math.floor(remain / 60),
      ack: cmd
    });
    res.send();
  });

  // Check if NTP is already synced at boot
  let sys = Shelly.getComponentStatus("sys");
  if (sys && typeof sys.unixtime === "number" && sys.unixtime > 1700000000) {
    ntp_synced = true;
  }

  print("Coffee timer booted. NTP:" + (ntp_synced ? "yes" : "no"));
}

// KVS boot loader - sequential chain to avoid "too many calls"
let kvs_keys = ["cfg_v", "cfg_sch", "cfg_h", "cfg_m", "cfg_dur", "cfg_max"];
let kvs_idx = 0;

function set_kvs_val(key, value) {
  if (key === "cfg_v" && typeof value === "number") cfg_v = value;
  if (key === "cfg_sch" && typeof value === "number") cfg_sch = value;
  if (key === "cfg_h" && typeof value === "number") cfg_h = value;
  if (key === "cfg_m" && typeof value === "number") cfg_m = value;
  if (key === "cfg_dur" && typeof value === "number") cfg_dur = value;
  if (key === "cfg_max" && typeof value === "number") cfg_max = value;
}

function load_next_kvs() {
  if (kvs_idx >= kvs_keys.length) {
    boot_complete();
    return;
  }
  let key = kvs_keys[kvs_idx];
  kvs_idx = kvs_idx + 1;
  Shelly.call("KVS.Get", {key: key}, function(res, err) {
    let v = null;
    if (res && typeof res.value !== "undefined") {
      v = res.value;
    }
    set_kvs_val(key, v);
    load_next_kvs();
  });
}

// Start boot
load_next_kvs();
