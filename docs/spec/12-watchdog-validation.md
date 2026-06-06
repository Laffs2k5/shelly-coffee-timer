# 12 — Watchdog Reboot-Reason Validation (Q1)

> **Status:** ✅ DONE — validated on real hardware 2026-06-05. Resolves the HIGH blocker
> [11-local-mqtt.md](11-local-mqtt.md) §11 Q1. The state-resume + watchdog design (doc 11 §6) is viable.

## 1. Question

Can the device, **on boot**, reliably distinguish a **software/watchdog reboot** (power maintained →
should **resume** the persisted countdown) from a **mains power loss** (→ must boot **OFF** for safety)?
KVS survives power loss, so persisted timer state alone can't tell them apart — a reboot-reason signal
is required.

## 2. Setup

- **Device:** `shellyplugsg3-XXXXXX` (Plug S Gen3, model S3PL-00112EU), firmware **1.7.5**, at
  `192.168.x.x`. The plug powers this laptop's charger, giving an independent out-of-band signal.
- **Control transport:** WSL2 is network-isolated from the LAN here, so all device RPC went through the
  **Windows** stack (`pwsh` 7.6.2 + `Invoke-RestMethod` to the Shelly HTTP `/rpc`).
- **Out-of-band state check:** Windows `BatteryStatus.PowerOnline` (WMI) tracks the relay 1:1 (charger
  on/off), and shows the ~8 s relay drop during a reboot.
- **Boot probe:** a temporary mJS script snapshotted `sys.reset_reason`, `sys.unixtime`, and
  `sys.uptime` into KVS at boot (captured at boot+2 s) to test what is available *immediately* at boot.

## 3. Results

| Boot cause | `reset_reason` (Sys + boot probe) | `unixtime` at boot+2 s |
|---|---|---|
| Prior boot (≈55 min before test) | 1 | — |
| **`Shelly.Reboot` (software)** | **3** | `null` |
| **Mains power loss** (physical unplug/replug) | **1** | `null` |

- **`reset_reason` cleanly distinguishes the two: software reboot = `3`, power loss = `1`.** Values
  match the ESP reset-reason enum (`3` = `ESP_RST_SW`, `1` = `ESP_RST_POWERON`).
- `reset_reason` is **populated immediately at boot** (the probe read it correctly at boot+2 s).
- The watchdog reboots via `Shelly.Reboot`, so a watchdog reboot produces `reset_reason == 3`.
- Reboot downtime was **~8 s**; the relay auto-restored when `initial_state` was set to `on`.

**RTC-retention mechanism (alternative) — ruled out.** `sys.unixtime` was `null` at boot+2 s even after
a *software* reboot (NTP had not yet re-synced), so boot-time `unixtime` cannot distinguish boot types.

## 4. Conclusion / decision

**Resume the persisted timer only when `reset_reason == 3`; every other value boots OFF** (degrade to
safe — power-on `1`, brownout `9`, panic `4`, watchdog `5–7`, unknown `0` all stay OFF). This preserves
"power loss → OFF" while letting watchdog/software reboots resume. Wires into doc 11 §6.3–§6.4.

**To re-verify after a firmware update:** re-run a `Shelly.Reboot` and a physical power-cycle and
confirm the values stay `3` / `1`. (Shelly's mapping is not contractually guaranteed across major
firmware; 2.0.0-beta1 was available but not installed at validation time.)

## 5. Notes for implementation

- Read `reset_reason` once at boot via `Shelly.getComponentStatus("sys")` before the OFF/ON decision —
  no extra timer, no parallel calls (respects the mJS budget).
- Keep `initial_state` decisions in the script, not firmware: the resume logic owns the relay on boot.
- Reboot reset behaviour assumes the plug is on continuous power except for the event under test; a
  reboot mid-countdown briefly drops the relay (~8 s here) — acceptable for a coffee maker.
