package com.shellycoffee.timer.api

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.File
import javax.net.ssl.SSLSocketFactory

/**
 * v2 MQTT transport for the phone, implementing the spec 11 §7 broker list with failover:
 *
 *   1. LOCAL broker (Mosquitto, mTLS) — ssl://<localHost>:8883, client cert from the imported
 *      PKCS#12 (route B). Used on Wi-Fi when a client identity has been imported.
 *   2. CLOUD broker (EMQX Serverless) — wss://<cloudHost>:8084/mqtt, username/password. Off-LAN
 *      fallback (WSS supplies the TLS+ALPN Serverless requires).
 *
 * `ensureConnected` tries local first (short timeout), then cloud. A persistent connection caches
 * the retained heartbeat/config so the existing poll-based UI / notification service read it
 * synchronously. Commands publish retain=false; config publishes retain=true.
 *
 * NOTE: this is the reference implementation for future apps. It is UNVERIFIED at runtime (no device
 * available); it compiles and the logic mirrors the device contract. Production hardening TODO:
 * store the PKCS#12 password in EncryptedSharedPreferences / AndroidKeyStore (here it is plain prefs).
 */
/** Which broker an MQTT connection is on. Top-level (no Android/Paho deps) so it's usable from
 *  pure, JVM-unit-testable logic like CoffeeApi.decide(). */
enum class Broker { NONE, LOCAL, CLOUD }

object MqttTransport {

    private const val DEFAULT_DEVICE = "YOUR_DEVICE_ID"
    private const val DEFAULT_CLOUD_HOST = "your-deployment.emqxsl.com"
    private const val DEFAULT_LOCAL_HOST = "192.168.x.x"
    private const val LOCAL_PORT = 8883
    // Stable client identity (= the cert CN / cloud username), per spec 11 §7. Safe because this
    // object is a process-wide singleton shared by MainActivity + the notification service, so there
    // is never a second concurrent connection with the same id. The web fallback uses a distinct id.
    private const val CLIENT_ID = "YOUR_PHONE_ID"

    // Device ID is runtime-configurable (Settings → "Device ID"), so topics are computed per use.
    // Like the host fields, a change takes effect on the next fresh connect.
    private fun device(): String =
        prefs()?.getString("mqtt_device", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE
    private fun topicHeartbeat() = "devices/${device()}/heartbeat"
    private fun topicConfig() = "devices/${device()}/config"
    private fun topicCommand() = "devices/${device()}/command"

    @Volatile private var appCtx: Context? = null
    @Volatile private var cloudPolls = 0   // for periodic cloud->local roam-up
    @Volatile private var client: MqttClient? = null

    @Volatile var connectedVia: Broker = Broker.NONE
        private set
    @Volatile var lastStatus: CoffeeApi.DeviceStatus? = null
        private set
    @Volatile var lastConfig: CoffeeApi.ConfigData? = null
        private set

    val isConnected: Boolean
        get() = client?.isConnected == true

    /** Idempotent; call from MainActivity.onCreate and the notification service onCreate. */
    fun init(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    private fun prefs() =
        appCtx?.getSharedPreferences("coffee_settings", Context.MODE_PRIVATE)

    private fun clientP12(): ByteArray? {
        val ctx = appCtx ?: return null
        val f = File(ctx.filesDir, "client.p12")
        return if (f.exists()) try { f.readBytes() } catch (_: Exception) { null } else null
    }

    private fun p12Password(): CharArray? =
        prefs()?.getString("mqtt_p12_pass", "")?.takeIf { it.isNotEmpty() }?.toCharArray()

    private fun localHost(): String =
        prefs()?.getString("mqtt_local_host", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCAL_HOST

    private fun cloudHost(): String =
        prefs()?.getString("mqtt_host", "")?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_HOST

    /** Tear down any MQTT connection (used when HTTP-direct takes over, to roam up). */
    @Synchronized
    fun disconnect() {
        val c = client
        if (c != null) {
            try { c.disconnectForcibly(250, 250) } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
        client = null
        connectedVia = Broker.NONE
        cloudPolls = 0
    }

    @Synchronized
    fun ensureConnected(user: String, pass: String) {
        val c = client
        if (c != null && c.isConnected) {
            // Roam-up: if we're on cloud, periodically retry the local broker and switch if reachable.
            if (connectedVia == Broker.CLOUD) {
                cloudPolls++
                if (cloudPolls >= 6) {
                    cloudPolls = 0
                    val ctx0 = appCtx
                    val sf0 = if (ctx0 != null) MqttTls.localSocketFactory(ctx0, clientP12(), p12Password()) else null
                    if (sf0 != null) {
                        val oldCloud = client
                        if (tryConnect("ssl://${localHost()}:$LOCAL_PORT", sf0, null, null, 4)) {
                            connectedVia = Broker.LOCAL
                            if (oldCloud != null && oldCloud !== client) {
                                try { oldCloud.disconnectForcibly(200, 200) } catch (_: Exception) {}
                                try { oldCloud.close() } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
            return
        }
        if (c != null) {
            try { c.disconnectForcibly(250, 250) } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
            client = null
            connectedVia = Broker.NONE
        }

        // Candidate 1: LOCAL broker over mTLS — only when a client identity has been imported.
        val ctx = appCtx
        if (ctx != null) {
            val sf = MqttTls.localSocketFactory(ctx, clientP12(), p12Password())
            if (sf != null && tryConnect("ssl://${localHost()}:$LOCAL_PORT", sf, null, null, 4)) {
                connectedVia = Broker.LOCAL
                return
            }
        }

        // Candidate 2: CLOUD broker over WSS with username/password.
        if (user.isNotBlank() && pass.isNotBlank()) {
            if (tryConnect("wss://${cloudHost()}:8084/mqtt", null, user, pass, 8)) {
                connectedVia = Broker.CLOUD
                return
            }
        }
        connectedVia = Broker.NONE
    }

    private fun tryConnect(
        uri: String,
        socketFactory: SSLSocketFactory?,
        user: String?,
        pass: String?,
        timeoutSec: Int
    ): Boolean {
        return try {
            val cli = MqttClient(uri, CLIENT_ID, MemoryPersistence())
            cli.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {}
                override fun messageArrived(topic: String, message: MqttMessage) {
                    onMessage(topic, String(message.payload))
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                // Persistent session (spec 11 §7.4): QoS-1 messages queue across reconnects.
                // We still re-subscribe on each connect, so retained heartbeat/config are redelivered.
                isCleanSession = false
                // Auto-reconnect OFF (was on): it was the churn engine. When the app is backgrounded
                // with no foreground service, Android Doze suspends Paho's ping thread → the broker's
                // keepalive grace lapses → drop → auto-reconnect → repeat every ~45-70 s (maintainer
                // flag 2026-06-06). Instead, reconnection is poll-driven: ensureConnected() runs on
                // each poll, and polling only happens when the UI is foregrounded or the FG service is
                // active — i.e. only when the process is actually alive to PING. No background churn.
                isAutomaticReconnect = false
                connectionTimeout = timeoutSec
                // 60 s keepalive (was 30): broker grace ~90 s, comfortably above both the 10 s
                // foreground poll and the 30 s FG-service poll, so a live connection never times out
                // between polls; fewer PINGREQs on the wire.
                keepAliveInterval = 60
                if (socketFactory != null) this.socketFactory = socketFactory
                if (user != null) userName = user
                if (pass != null) password = pass.toCharArray()
                // LWT (presence) intentionally omitted: no phone-presence topic/consumer is defined
                // in the design — the system's presence signal is the DEVICE's own `online` LWT, not
                // the phone's. Add one here if a phone-presence consumer is ever introduced.
            }
            cli.connect(opts)
            // Retained heartbeat + config arrive immediately on subscribe.
            cli.subscribe(topicHeartbeat(), 1)
            cli.subscribe(topicConfig(), 1)
            client = cli
            true
        } catch (_: Exception) {
            client = null
            false
        }
    }

    private fun onMessage(topic: String, payload: String) {
        try {
            val json = JSONObject(payload)
            when (topic) {
                topicHeartbeat() -> lastStatus = CoffeeApi.DeviceStatus(
                    state = json.optString("s", "off"),
                    remaining = json.optInt("r", 0),
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
                topicConfig() -> lastConfig = CoffeeApi.ConfigData(
                    version = json.optInt("v", 0),
                    scheduleEnabled = json.optInt("sch", 0),
                    hour = json.optInt("h", 6),
                    minute = json.optInt("m", 0),
                    duration = json.optInt("dur", 90),
                    maxMinutes = json.optInt("max", 180)
                )
            }
        } catch (_: Exception) {
            // ignore malformed payloads
        }
    }

    fun publishCommand(user: String, pass: String, cmd: String): Boolean {
        ensureConnected(user, pass)
        val cli = client ?: return false
        return try {
            val ts = System.currentTimeMillis() / 1000
            val payload = JSONObject().apply {
                put("c", cmd)
                put("ts", ts)
            }.toString()
            cli.publish(topicCommand(), MqttMessage(payload.toByteArray()).apply {
                qos = 1
                isRetained = false
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    fun publishConfig(user: String, pass: String, config: CoffeeApi.ConfigData): Boolean {
        ensureConnected(user, pass)
        val cli = client ?: return false
        return try {
            val payload = JSONObject().apply {
                put("v", config.version)
                put("sch", config.scheduleEnabled)
                put("h", config.hour)
                put("m", config.minute)
                put("dur", config.duration)
                put("max", config.maxMinutes)
            }.toString()
            cli.publish(topicConfig(), MqttMessage(payload.toByteArray()).apply {
                qos = 1
                isRetained = true
            })
            lastConfig = config
            true
        } catch (_: Exception) {
            false
        }
    }
}
