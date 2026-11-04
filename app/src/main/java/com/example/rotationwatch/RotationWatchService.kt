package com.example.rotationwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

class RotationWatchService : Service() {

    private val tracker = EventTracker()
    private var lastValue = -1
    private lateinit var observer: ContentObserver

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = System.currentTimeMillis()
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> tracker.screenOnAt = now
                Intent.ACTION_SCREEN_OFF -> tracker.screenOffAt = now
                Intent.ACTION_USER_PRESENT -> tracker.unlockAt = now
                Intent.ACTION_POWER_CONNECTED -> tracker.powerConnectedAt = now
                Intent.ACTION_POWER_DISCONNECTED -> tracker.powerDisconnectedAt = now
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat()

        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })

        lastValue = readRotation()
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val v = readRotation()
                val prev = lastValue
                lastValue = v
                if (prev == 0 && v == 1) onRotationEnabled()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer
        )
    }

    private fun readRotation() =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)

    private fun onRotationEnabled() {
        // 상황 수집은 메인 스레드 밖에서
        Thread {
            val report = ContextCollector.collect(applicationContext, tracker)
            LogStore.add(applicationContext, report)
            notifyUser(report)
        }.start()
    }

    private fun notifyUser(r: Report) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("화면 자동 회전이 켜졌어요")
            .setContentText(r.verdict)
            .setStyle(Notification.BigTextStyle().bigText(r.verdict + "\n\n" + r.details))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), n)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "감시 상태", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "회전 켜짐 알림", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun startForegroundCompat() {
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("화면 회전 감시 중")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CH_SERVICE = "watch_state"
        const val CH_ALERT = "rotation_alert"
    }
}
