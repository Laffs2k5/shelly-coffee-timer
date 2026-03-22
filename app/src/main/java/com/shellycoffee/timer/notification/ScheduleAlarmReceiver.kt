package com.shellycoffee.timer.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val serviceIntent = Intent(context, CoffeeNotificationService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
