package com.shellycoffee.timer.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.shellycoffee.timer.api.CoffeeApi
import java.util.Timer
import java.util.TimerTask

class CoffeeNotificationService : Service() {

    private var pollTimer: Timer? = null
    private var countdownTimer: Timer? = null
    private var remainingMin = 0
    private var consecutiveFailures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannel(this)

        // Start foreground immediately with a placeholder notification
        startForeground(1, NotificationHelper.buildOngoing(this, 0))

        // Do an immediate poll, then schedule recurring
        pollDevice()
        startPollTimer()
        startCountdownTimer()

        return START_NOT_STICKY
    }

    private fun startPollTimer() {
        pollTimer?.cancel()
        pollTimer = Timer("poll", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    pollDevice()
                }
            }, 30_000L, 30_000L)
        }
    }

    private fun startCountdownTimer() {
        countdownTimer?.cancel()
        countdownTimer = Timer("countdown", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (remainingMin > 0) {
                        remainingMin--
                        NotificationHelper.showOngoing(this@CoffeeNotificationService, remainingMin)
                    }
                }
            }, 60_000L, 60_000L)
        }
    }

    private fun pollDevice() {
        val prefs = getSharedPreferences("coffee_settings", Context.MODE_PRIVATE)
        val ip = prefs.getString("shelly_ip", "") ?: ""
        val user = prefs.getString("aio_user", "") ?: ""
        val key = prefs.getString("aio_key", "") ?: ""

        val result = CoffeeApi.pollStatus(ip, user, key)

        if (result.status != null) {
            val status = result.status
            if (status.state == "on") {
                remainingMin = status.remaining
                consecutiveFailures = 0
                NotificationHelper.showOngoing(this, remainingMin)

                // Save schedule info for alarm manager
                prefs.edit()
                    .putInt("schedule_enabled", status.scheduleEnabled)
                    .putInt("schedule_h", status.scheduleHour)
                    .putInt("schedule_m", status.scheduleMinute)
                    .apply()
            } else {
                // Device is off — stop service
                NotificationHelper.cancel(this)
                stopSelf()
                return
            }
        } else {
            // Poll failed
            consecutiveFailures++
            if (consecutiveFailures >= 10) {
                // 10 consecutive failures at 30s each = ~5 minutes
                NotificationHelper.showConnectionLost(this)
            }
        }
    }

    override fun onDestroy() {
        pollTimer?.cancel()
        pollTimer = null
        countdownTimer?.cancel()
        countdownTimer = null
        NotificationHelper.cancel(this)
        super.onDestroy()
    }
}
