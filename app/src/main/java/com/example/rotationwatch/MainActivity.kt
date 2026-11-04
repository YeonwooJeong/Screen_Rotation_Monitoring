package com.example.rotationwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var toggle: Button

    private val prefs get() = getSharedPreferences("settings", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        status = TextView(this).apply { textSize = 15f }
        toggle = Button(this).apply { setOnClickListener { toggleService() } }
        val perm1 = Button(this).apply {
            text = "1) 알림 권한 허용"
            setOnClickListener { requestNotifPermission() }
        }
        val perm2 = Button(this).apply {
            text = "2) 사용 정보 접근 허용 (원인 추적용)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        val perm3 = Button(this).apply {
            text = "3) 배터리 제한 해제 (설정 열기)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val clear = Button(this).apply {
            text = "기록 지우기"
            setOnClickListener { LogStore.clear(this@MainActivity); refresh() }
        }
        log = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(log) }

        listOf<View>(status, perm1, perm2, perm3, toggle, clear).forEach { root.addView(it) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toggleService() {
        val on = !prefs.getBoolean("watch_enabled", false)
        prefs.edit().putBoolean("watch_enabled", on).apply()
        val i = Intent(this, RotationWatchService::class.java)
        if (on) startForegroundService(i) else stopService(i)
        refresh()
    }

    private fun refresh() {
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val usage = ContextCollector.hasUsageAccess(this)
        val on = prefs.getBoolean("watch_enabled", false)
        val rot = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)

        status.text = buildString {
            append("감시: ").append(if (on) "켜짐" else "꺼짐").append('\n')
            append("알림 권한: ").append(if (notif) "OK" else "필요").append('\n')
            append("사용 정보 접근: ").append(if (usage) "OK" else "필요(원인 추적 불가)").append('\n')
            append("현재 자동 회전: ").append(if (rot == 1) "켜짐" else "꺼짐")
        }
        toggle.text = if (on) "감시 끄기" else "감시 시작"
        log.text = LogStore.render(this)
    }
}
