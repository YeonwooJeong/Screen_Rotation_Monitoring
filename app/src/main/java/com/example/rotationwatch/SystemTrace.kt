package com.example.rotationwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * adb로 한 번 허용해 주는 개발자용 권한(DUMP)으로
 * "누가 자동 회전 설정을 바꿨는지" 시스템 기록(dumpsys settings)을 읽는다.
 *
 *   adb shell pm grant com.example.rotationwatch android.permission.DUMP
 *
 * logcat(READ_LOGS)은 Android 13부터 매번 사용자 동의 창을 기다리느라 멈추므로 쓰지 않는다.
 */
object SystemTrace {

    /** 설정 변경 기록 한 줄: 시각 문자열, 이전값, 새값, 바꾼 패키지 */
    data class Change(val time: String, val oldValue: String, val newValue: String, val pkg: String)

    private val HISTORY_LINE = Regex(
        """time:(\S+ \S+) mode:\S+ oldValue:(\S+) newValue:(\S+) package:(\S+)"""
    )

    fun canReadHistory(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** 자동 회전 설정의 최근 변경 기록 (오래된 것 → 최신 순). 권한이 없거나 실패하면 null */
    fun rotationHistory(ctx: Context): List<Change>? {
        if (!canReadHistory(ctx)) return null
        val out = exec(ctx, "dumpsys", "settings") ?: return null
        val changes = mutableListOf<Change>()
        var inBlock = false
        for (line in out.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("History (accelerometer_rotation)") -> inBlock = true
                inBlock && t.startsWith("time:") -> HISTORY_LINE.find(t)?.let { m ->
                    val (time, o, n, pkg) = m.destructured
                    changes += Change(time, o, n, pkg)
                }
                inBlock -> inBlock = false
            }
        }
        // 사용자(프로필)마다 블록이 따로 있으므로 시각순으로 합친다. "MM-dd HH:mm:ss.SSS"는 문자열 정렬 = 시간 정렬
        return changes.sortedBy { it.time }
    }

    /** 회전 잠금 변경 기록 한 줄: 시각, 모드(USER_ROTATION_FREE=자동 회전 켜짐), 호출한 코드 */
    data class LockChange(val time: String, val mode: String, val caller: String) {
        val enabled get() = mode == "USER_ROTATION_FREE"
    }

    private val LOCK_LINE = Regex(
        """^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}): mode=(\w+), rotation=\w+, caller=(.+)$"""
    )

    /**
     * 윈도우 관리자의 RotationLockHistory (오래된 것 → 최신 순).
     * 여기에는 회전 잠금을 바꾼 코드(caller)가 남아서 monkey·회전 버튼·퀵패널 등을 구분할 수 있다.
     * 앱에서는 `dumpsys window` 명령이 막혀 있어 윈도우 서비스에 직접 dump를 요청한다.
     */
    fun rotationLockHistory(ctx: Context): List<LockChange>? {
        if (!canReadHistory(ctx)) return null
        val out = dumpService(ctx, "window", "displays") ?: return null
        val changes = mutableListOf<LockChange>()
        var inBlock = false
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t == "RotationLockHistory") {
                inBlock = true
                continue
            }
            if (!inBlock) continue
            val m = LOCK_LINE.find(t)
            if (m == null) {
                inBlock = false
                continue
            }
            val (time, mode, caller) = m.destructured
            changes += LockChange(time, mode, caller)
        }
        return changes.sortedBy { it.time }
    }

    /** caller 이름을 사람이 읽을 설명으로 */
    fun describeCaller(caller: String): String = when {
        caller.contains("Monkey", true) -> "PC의 adb monkey 명령"
        caller.contains("UiAutomation", true) -> "PC의 UI 자동화 도구(uiautomator 등)"
        caller.contains("RotationButton", true) -> "화면의 회전 버튼"
        caller.contains("Tile", true) -> "퀵패널 자동 회전 버튼"
        caller.contains("Settings", true) -> "설정 앱"
        else -> "알 수 없는 코드"
    }

    /** 시스템 서비스에 직접 dump를 요청해 결과를 문자열로 받는다. 5초 안에 안 끝나면 포기 */
    private fun dumpService(ctx: Context, name: String, vararg args: String): String? {
        val out = File.createTempFile("dump", ".txt", ctx.cacheDir)
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, name) as? IBinder ?: return null
            val worker = Thread {
                try {
                    ParcelFileDescriptor.open(
                        out, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
                    ).use { binder.dump(it.fileDescriptor, arrayOf(*args)) }
                } catch (e: Exception) {
                    // 실패하면 빈 결과
                }
            }
            worker.start()
            worker.join(5_000L)
            if (worker.isAlive) return null
            return out.readText()
        } catch (e: Exception) {
            return null
        } finally {
            out.delete()
        }
    }

    /** 기록 시각 문자열("MM-dd HH:mm:ss.SSS", 올해)을 epoch ms로 */
    fun toEpoch(time: String): Long? {
        val parsed = try {
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).parse(time)
        } catch (e: Exception) {
            null
        } ?: return null
        val now = Calendar.getInstance()
        return Calendar.getInstance().apply {
            this.time = parsed
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            // 연말→연초 경계: 미래 시각이면 작년 기록
            if (timeInMillis > now.timeInMillis + 60_000L) add(Calendar.YEAR, -1)
        }.timeInMillis
    }

    /** 명령 실행. 출력은 임시 파일로 받아 5초 안에 끝나지 않으면 강제 종료한다 */
    private fun exec(ctx: Context, vararg cmd: String): String? {
        val out = File.createTempFile("trace", ".txt", ctx.cacheDir)
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(out).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else {
                out.readText()
            }
        } catch (e: Exception) {
            null
        } finally {
            out.delete()
        }
    }
}
