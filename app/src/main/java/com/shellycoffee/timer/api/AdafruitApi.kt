package com.shellycoffee.timer.api

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
        val timestamp: Long
    )

    enum class ConnectionMode { LOCAL, REMOTE, OFFLINE }

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
                    timestamp = json.optLong("ts", 0)
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

    // --- Remote API (Adafruit IO) ---

    fun fetchRemoteStatus(user: String, key: String): DeviceStatus? {
        return try {
            val url = URL("https://io.adafruit.com/api/v2/$user/feeds/heartbeat/data/last")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-AIO-Key", key)

            if (conn.responseCode == 200) {
                val body = readResponse(conn)
                val outer = JSONObject(body)
                val value = outer.optString("value", "{}")
                val json = JSONObject(value)
                DeviceStatus(
                    state = json.optString("s", "off"),
                    remaining = json.optInt("r", 0),
                    mode = json.optString("mode", "unknown"),
                    scheduleEnabled = json.optInt("sch", 0),
                    scheduleHour = json.optInt("h", 6),
                    scheduleMinute = json.optInt("m", 0),
                    ntpSynced = json.optBoolean("ntp", false),
                    timestamp = json.optLong("ts", 0)
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun sendRemoteCommand(user: String, key: String, cmd: String): Boolean {
        return try {
            val url = URL("https://io.adafruit.com/api/v2/$user/feeds/command/data")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-AIO-Key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val ts = System.currentTimeMillis() / 1000
            val innerJson = JSONObject().apply {
                put("c", cmd)
                put("ts", ts)
            }
            val outerJson = JSONObject().apply {
                put("value", innerJson.toString())
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(outerJson.toString())
            writer.flush()
            writer.close()

            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    fun fetchRemoteConfig(user: String, key: String): ConfigData? {
        return try {
            val url = URL("https://io.adafruit.com/api/v2/$user/feeds/config/data/last")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-AIO-Key", key)

            if (conn.responseCode == 200) {
                val body = readResponse(conn)
                val outer = JSONObject(body)
                val value = outer.optString("value", "{}")
                val json = JSONObject(value)
                ConfigData(
                    version = json.optInt("v", 0),
                    scheduleEnabled = json.optInt("sch", 0),
                    hour = json.optInt("h", 6),
                    minute = json.optInt("m", 0),
                    duration = json.optInt("dur", 90),
                    maxMinutes = json.optInt("max", 120)
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun writeRemoteConfig(user: String, key: String, config: ConfigData): Boolean {
        return try {
            val url = URL("https://io.adafruit.com/api/v2/$user/feeds/config/data")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-AIO-Key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val innerJson = JSONObject().apply {
                put("v", config.version)
                put("sch", config.scheduleEnabled)
                put("h", config.hour)
                put("m", config.minute)
                put("dur", config.duration)
                put("max", config.maxMinutes)
            }
            val outerJson = JSONObject().apply {
                put("value", innerJson.toString())
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(outerJson.toString())
            writer.flush()
            writer.close()

            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    // --- Auto-detect: local first, then remote ---

    fun pollStatus(shellyIp: String, user: String, key: String): StatusResult {
        // Try local first
        if (shellyIp.isNotBlank()) {
            val local = fetchLocalStatus(shellyIp)
            if (local != null) {
                return StatusResult(local, ConnectionMode.LOCAL)
            }
        }

        // Fall back to remote
        if (user.isNotBlank() && key.isNotBlank()) {
            val remote = fetchRemoteStatus(user, key)
            if (remote != null) {
                return StatusResult(remote, ConnectionMode.REMOTE)
            }
        }

        return StatusResult(null, ConnectionMode.OFFLINE)
    }

    fun sendCommand(
        shellyIp: String, user: String, key: String,
        cmd: String, currentMode: ConnectionMode
    ): DeviceStatus? {
        // If currently local, try local first
        if (currentMode == ConnectionMode.LOCAL && shellyIp.isNotBlank()) {
            val result = sendLocalCommand(shellyIp, cmd)
            if (result != null) return result
        }

        // Remote
        if (user.isNotBlank() && key.isNotBlank()) {
            val sent = sendRemoteCommand(user, key, cmd)
            if (sent) {
                // Brief pause then poll for updated status
                Thread.sleep(500)
                return fetchRemoteStatus(user, key)
            }
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
