package com.example.rotationwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 사용자가 켜 둔 경우에만 자동 시작
        val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("watch_enabled", false)
        if (enabled) {
            context.startForegroundService(Intent(context, RotationWatchService::class.java))
        }
    }
}
