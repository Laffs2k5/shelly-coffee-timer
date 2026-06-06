package com.shellycoffee.timer.api

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles all HTTP communication with:
 * - Shelly local HTTP API
 * - Adafruit IO REST API
 */
object CoffeeApi {

    // --- Data classes ---

    data class DeviceStatus(
        val state: String,        // "on" or "off"
        val remaining: Int,       // minutes remaining
        val mode: String,         // "manual", "schedule", etc.
        val scheduleEnabled: Int, // 0 or 1
        val scheduleHour: Int,
        val scheduleMinute: Int,
        val ntpSynced: Boolean,
        val timestamp: Long,
        // Config fields, now carried in the heartbeat + HTTP status (device exposes them so controllers
        // can write a version-gated config without a separate retained-config read). -1 version = unknown.
        val version: Int = -1,
        val duration: Int = 90,
        val maxMinutes: Int = 180
    )

    // Effective connection type, best→worst: HTTP_DIRECT (Wi-Fi·direct) > LOCAL_BROKER (Wi-Fi·broker, mTLS) > CLOUD.
    enum class ConnectionMode { HTTP_DIRECT, LOCAL_BROKER, CLOUD, OFFLINE }

    data class StatusResult(
        val status: DeviceStatus?,
        val mode: ConnectionMode
    )

    data class ConfigData(
        val version: Int,
        val scheduleEnabled: Int,
        val hour: Int,
        val minute: Int,
        val duration: Int,
        val maxMinutes: Int
    )

    // --- Local API ---

    fun fetchLocalStatus(shellyIp: String): DeviceStatus? {
        return try {
            val url = URL("http://$shellyIp/script/1/coffee_status")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val body = readResponse(conn)
                val json = JSONObject(body)
                DeviceStatus(
                    state = json.optString("state", "off"),
                    remaining = json.optInt("remaining", 0),
                    mode = json.optString("mode", "unknown"),
                    scheduleEnabled = json.optInt("sch", 0),
                    scheduleHour = json.optInt("h", 6),
                    scheduleMinute = json.optInt("m", 0),
                    ntpSynced = json.optBoolean("ntp", false),
                    timestamp = json.optLong("ts", 0),
                    version = json.optInt("v", -1),
                    duration = json.optInt("dur", 90),
                    maxMinutes = json.optInt("max", 180)
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun sendLocalCommand(shellyIp: String, cmd: String): DeviceStatus? {
        return try {
            val url = URL("http://$shellyIp/script/1/coffee_command?cmd=$cmd")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val body = readResponse(conn)
                val json = JSONObject(body)
                if (json.optBoolean("ok", false)) {
                    DeviceStatus(
                        state = json.optString("state", "off"),
                        remaining = json.optInt("remaining", 0),
                        mode = json.optString("mode", "unknown"),
                        scheduleEnabled = 0,
                        scheduleHour = 6,
                        scheduleMinute = 0,
                        ntpSynced = true,
                        timestamp = System.currentTimeMillis() / 1000
                    )
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // --- Remote API (v2: cloud MQTT over WSS via MqttTransport) ---
    // `user`/`key` are now the cloud MQTT username/password (kept as params so callers and
    // the notification service are unchanged). Status/config are read from MqttTransport's
    // cache of the retained heartbeat/config topics; commands/config are published.

    fun fetchRemoteStatus(user: String, key: String): DeviceStatus? {
        MqttTransport.ensureConnected(user, key)
        return MqttTransport.lastStatus
    }

    fun sendRemoteCommand(user: String, key: String, cmd: String): Boolean {
        return MqttTransport.publishCommand(user, key, cmd)
    }

    fun fetchRemoteConfig(user: String, key: String): ConfigData? {
        MqttTransport.ensureConnected(user, key)
        return MqttTransport.lastConfig
    }

    fun writeRemoteConfig(user: String, key: String, config: ConfigData): Boolean {
        return MqttTransport.publishConfig(user, key, config)
    }

    // --- Connection roaming: prefer HTTP-direct > local broker (mTLS) > cloud, re-evaluated every poll ---

    /**
     * Pure decision over the available inputs — unit-tested (app/src/test). Side effects (HTTP fetch,
     * MQTT connect/disconnect) live in pollStatus; this just maps results to the effective mode.
     */
    fun decide(local: DeviceStatus?, mqttVia: Broker, mqttStatus: DeviceStatus?): StatusResult {
        if (local != null) return StatusResult(local, ConnectionMode.HTTP_DIRECT)
        if (mqttStatus != null) when (mqttVia) {
            Broker.LOCAL -> return StatusResult(mqttStatus, ConnectionMode.LOCAL_BROKER)
            Broker.CLOUD -> return StatusResult(mqttStatus, ConnectionMode.CLOUD)
            Broker.NONE -> {}
        }
        return StatusResult(null, ConnectionMode.OFFLINE)
    }

    fun pollStatus(shellyIp: String, user: String, key: String): StatusResult {
        // 1) Best path: HTTP-direct on Wi-Fi (tried every poll so we always roam back to it).
        val local = if (shellyIp.isNotBlank()) fetchLocalStatus(shellyIp) else null
        if (local != null) {
            MqttTransport.disconnect()   // roam up: don't keep an MQTT socket while direct works
            return decide(local, Broker.NONE, null)
        }
        // 2) Fall back to MQTT (broker list: local mTLS preferred, else cloud).
        MqttTransport.ensureConnected(user, key)
        return decide(null, MqttTransport.connectedVia, MqttTransport.lastStatus)
    }

    fun sendCommand(
        shellyIp: String, user: String, key: String,
        cmd: String, currentMode: ConnectionMode
    ): DeviceStatus? {
        // Prefer the path we're currently on; fall back to the other transport.
        val preferLocal = currentMode == ConnectionMode.HTTP_DIRECT && shellyIp.isNotBlank()
        if (preferLocal) {
            val r = sendLocalCommand(shellyIp, cmd)
            if (r != null) return r
        }
        if (MqttTransport.publishCommand(user, key, cmd)) {
            Thread.sleep(500)
            return MqttTransport.lastStatus
        }
        if (!preferLocal && shellyIp.isNotBlank()) {
            val r = sendLocalCommand(shellyIp, cmd)
            if (r != null) return r
        }
        return null
    }

    // --- Helpers ---

    private fun readResponse(conn: HttpURLConnection): String {
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        return sb.toString()
    }
}
