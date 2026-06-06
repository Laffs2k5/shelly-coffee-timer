package com.shellycoffee.timer

import com.shellycoffee.timer.api.Broker
import com.shellycoffee.timer.api.CoffeeApi
import com.shellycoffee.timer.api.CoffeeApi.ConnectionMode
import com.shellycoffee.timer.api.ConnectionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** JVM unit tests for the pure connection logic (no Android/Paho deps). Run: ./gradlew testDebugUnitTest */
class ConnectionLogicTest {

    private fun status(state: String = "on", remaining: Int = 90) =
        CoffeeApi.DeviceStatus(state, remaining, "remote", 0, 6, 0, true, 0L)

    // --- decide(): priority HTTP-direct > local broker > cloud > offline ---

    @Test fun httpDirectWinsWhenLocalPresent() {
        val r = CoffeeApi.decide(status(), Broker.CLOUD, status()) // even with MQTT available
        assertEquals(ConnectionMode.HTTP_DIRECT, r.mode)
        assertEquals("on", r.status?.state)
    }

    @Test fun localBrokerWhenMqttViaLocal() {
        val r = CoffeeApi.decide(null, Broker.LOCAL, status())
        assertEquals(ConnectionMode.LOCAL_BROKER, r.mode)
    }

    @Test fun cloudWhenMqttViaCloud() {
        val r = CoffeeApi.decide(null, Broker.CLOUD, status())
        assertEquals(ConnectionMode.CLOUD, r.mode)
    }

    @Test fun offlineWhenNothing() {
        val r = CoffeeApi.decide(null, Broker.NONE, null)
        assertEquals(ConnectionMode.OFFLINE, r.mode)
        assertNull(r.status)
    }

    @Test fun offlineWhenMqttConnectedButNoStatusYet() {
        val r = CoffeeApi.decide(null, Broker.LOCAL, null)
        assertEquals(ConnectionMode.OFFLINE, r.mode)
    }

    // --- ConnectionUi.label() ---

    @Test fun labels() {
        assertEquals("Wi-Fi · direct", ConnectionUi.label(ConnectionMode.HTTP_DIRECT))
        assertEquals("Wi-Fi · broker", ConnectionUi.label(ConnectionMode.LOCAL_BROKER))
        assertEquals("Cloud", ConnectionUi.label(ConnectionMode.CLOUD))
        assertEquals("Offline", ConnectionUi.label(ConnectionMode.OFFLINE))
    }

    // --- ConnectionUi.pushIfChanged(): newest-first, max 4, only on change ---

    @Test fun appendsOnChangeNewestFirst() {
        var log = emptyList<ConnectionUi.LogEntry>()
        log = ConnectionUi.pushIfChanged(log, ConnectionMode.OFFLINE, "10:00")
        log = ConnectionUi.pushIfChanged(log, ConnectionMode.CLOUD, "10:01")
        log = ConnectionUi.pushIfChanged(log, ConnectionMode.HTTP_DIRECT, "10:02")
        assertEquals(3, log.size)
        assertEquals(ConnectionMode.HTTP_DIRECT, log[0].mode) // newest first
        assertEquals("10:02", log[0].time)
        assertEquals(ConnectionMode.OFFLINE, log[2].mode)
    }

    @Test fun noEntryWhenModeUnchanged() {
        val log = ConnectionUi.pushIfChanged(emptyList(), ConnectionMode.CLOUD, "10:00")
        val again = ConnectionUi.pushIfChanged(log, ConnectionMode.CLOUD, "10:05")
        assertSame(log, again) // unchanged → same list, no new entry
        assertEquals(1, again.size)
    }

    @Test fun capsAtFour() {
        var log = emptyList<ConnectionUi.LogEntry>()
        val seq = listOf(
            ConnectionMode.OFFLINE, ConnectionMode.CLOUD, ConnectionMode.LOCAL_BROKER,
            ConnectionMode.HTTP_DIRECT, ConnectionMode.CLOUD, ConnectionMode.OFFLINE
        )
        seq.forEachIndexed { i, m -> log = ConnectionUi.pushIfChanged(log, m, "t$i") }
        // changes: OFFLINE,CLOUD,LOCAL_BROKER,HTTP_DIRECT,CLOUD,OFFLINE -> keep newest 4
        assertEquals(4, log.size)
        assertEquals(ConnectionMode.OFFLINE, log[0].mode)      // newest (t5)
        assertEquals(ConnectionMode.CLOUD, log[1].mode)        // t4
        assertEquals(ConnectionMode.HTTP_DIRECT, log[2].mode)  // t3
        assertEquals(ConnectionMode.LOCAL_BROKER, log[3].mode) // oldest kept (t2)
    }
}
