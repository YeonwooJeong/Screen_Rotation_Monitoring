package com.example.rotationwatch

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면/충전 등 시스템 이벤트 시각을 서비스가 기록해 두는 곳 */
class EventTracker {
    @Volatile var screenOnAt = 0L
    @Volatile var screenOffAt = 0L
    @Volatile var unlockAt = 0L
    @Volatile var powerConnectedAt = 0L
    @Volatile var powerDisconnectedAt = 0L
}

data class Report(val time: Long, val verdict: String, val details: String)

object ContextCollector {

    private const val WINDOW_MS = 90_000L
    private const val ROUTINE_PKG = "com.samsung.android.app.routineplus"

    private val SYSTEM_HINTS = mapOf(
        ROUTINE_PKG to "모드 및 루틴",
        "com.samsung.android.bixby.agent" to "빅스비",
        "com.android.systemui" to "시스템UI(퀵패널/회전 버튼)",
        "com.android.settings" to "설정 앱",
        "com.samsung.android.game.gametools" to "게임 도구",
        "com.samsung.android.game.gamehome" to "게임 런처",
    )

    private val fmt get() = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun hasUsageAccess(ctx: Context): Boolean {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
        return stats != null && stats.isNotEmpty()
    }

    private fun label(ctx: Context, pkg: String): String {
        SYSTEM_HINTS[pkg]?.let { return "$it ($pkg)" }
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            "${ctx.packageManager.getApplicationLabel(info)} ($pkg)"
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun typeName(t: Int): String = when (t) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "화면 진입"
        UsageEvents.Event.ACTIVITY_PAUSED -> "화면 이탈"
        UsageEvents.Event.FOREGROUND_SERVICE_START -> "포그라운드 서비스 시작"
        UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "포그라운드 서비스 종료"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "화면 켜짐"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "화면 꺼짐"
        UsageEvents.Event.KEYGUARD_SHOWN -> "잠금화면 표시"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "잠금해제"
        UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "대기 버킷 변경"
        else -> "이벤트 $t"
    }

    fun collect(ctx: Context, tracker: EventTracker): Report {
        val now = System.currentTimeMillis()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val interactive = pm.isInteractive
        val locked = km.isKeyguardLocked

        val lines = mutableListOf<String>()
        lines += "화면: " + when {
            !interactive -> "꺼져 있었음"
            locked -> "켜짐(잠금화면)"
            else -> "켜짐(사용 중)"
        }

        fun ago(label: String, t: Long) {
            if (t > 0 && now - t < 10 * 60_000L) lines += "$label: ${(now - t) / 1000}초 전"
        }
        ago("마지막 화면 켜짐", tracker.screenOnAt)
        ago("마지막 화면 꺼짐", tracker.screenOffAt)
        ago("마지막 잠금해제", tracker.unlockAt)
        ago("충전기 연결", tracker.powerConnectedAt)
        ago("충전기 분리", tracker.powerDisconnectedAt)

        // 최근 90초간 앱 활동 (사용 정보 접근 권한 필요)
        val perPkg = LinkedHashMap<String, Pair<Long, Int>>() // pkg -> 마지막 (시각, 타입)
        var foregroundPkg: String? = null
        val usageOk = try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(now - WINDOW_MS, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName == ctx.packageName) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foregroundPkg = e.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (foregroundPkg == e.packageName) foregroundPkg = null
                }
                if (e.eventType in setOf(
                        UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.FOREGROUND_SERVICE_START, UsageEvents.Event.FOREGROUND_SERVICE_STOP
                    )
                ) {
                    perPkg.remove(e.packageName)
                    perPkg[e.packageName] = e.timeStamp to e.eventType
                }
            }
            true
        } catch (ex: SecurityException) {
            false
        }

        if (!usageOk) {
            lines += "앱 활동: 사용 정보 접근 권한이 없어 확인 불가"
        } else if (perPkg.isEmpty()) {
            lines += "최근 90초 앱 활동: 없음"
        } else {
            lines += "최근 90초 앱 활동(최신순):"
            perPkg.entries.reversed().take(8).forEach { (pkg, v) ->
                lines += "  ${fmt.format(Date(v.first))} ${label(ctx, pkg)} - ${typeName(v.second)}"
            }
        }

        val routineSeen = perPkg.containsKey(ROUTINE_PKG)
        val sysSeen = perPkg.keys.firstOrNull { it == "com.android.systemui" || it == "com.android.settings" }

        val verdict = when {
            routineSeen -> "모드 및 루틴이 실행된 흔적이 있어요 (루틴 가능성 높음)"
            !interactive -> "화면이 꺼진 상태에서 켜졌어요 (루틴·예약·백그라운드 앱 의심)"
            sysSeen != null -> "${label(ctx, sysSeen)} 조작 직후예요 (퀵패널/회전 버튼 직접 조작 가능성)"
            foregroundPkg != null -> "${label(ctx, foregroundPkg!!)} 사용 중에 켜졌어요 (앱이 설정을 바꿨거나 회전 제안 버튼)"
            else -> "뚜렷한 원인 흔적이 없어요 (백그라운드 동작 의심)"
        }
        return Report(now, verdict, lines.joinToString("\n"))
    }
}
