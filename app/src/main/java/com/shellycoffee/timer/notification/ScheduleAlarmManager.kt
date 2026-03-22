package com.shellycoffee.timer.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object ScheduleAlarmManager {

    private const val REQUEST_CODE = 1001

    fun scheduleWakeUp(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        // On Android 12+, exact alarms require explicit user permission
        if (!alarmManager.canScheduleExactAlarms()) return

        val pendingIntent = getPendingIntent(context)

        // Cancel any existing alarm first
        alarmManager.cancel(pendingIntent)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // If the time has already passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } catch (_: SecurityException) {
            // Permission not granted — alarm won't fire, but app won't crash
        }
    }

    fun cancelWakeUp(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(getPendingIntent(context))
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
