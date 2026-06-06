package com.shellycoffee.timer.api

/**
 * Pure (no Android deps) helpers for the connection indicator + event log — unit-tested in
 * app/src/test. The footer label and the bottom event log both use [label]; the log uses
 * [pushIfChanged] to keep a newest-first list of connection-type changes, capped at [MAX_LOG].
 */
object ConnectionUi {

    const val MAX_LOG = 4

    fun label(mode: CoffeeApi.ConnectionMode): String = when (mode) {
        CoffeeApi.ConnectionMode.HTTP_DIRECT -> "Wi-Fi · direct"
        CoffeeApi.ConnectionMode.LOCAL_BROKER -> "Wi-Fi · broker"
        CoffeeApi.ConnectionMode.CLOUD -> "Cloud"
        CoffeeApi.ConnectionMode.OFFLINE -> "Offline"
    }

    data class LogEntry(val time: String, val mode: CoffeeApi.ConnectionMode)

    /**
     * Prepend a new entry only when [mode] differs from the newest entry's mode; keep newest-first
     * and cap at [MAX_LOG]. Returns the same list (unchanged) when the mode hasn't changed.
     */
    fun pushIfChanged(log: List<LogEntry>, mode: CoffeeApi.ConnectionMode, time: String): List<LogEntry> {
        if (log.firstOrNull()?.mode == mode) return log
        return (listOf(LogEntry(time, mode)) + log).take(MAX_LOG)
    }
}
