package com.example.rotationwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val PREFS = "rotation_log"
    private const val KEY = "entries"
    private const val MAX = 50

    @Synchronized
    fun add(ctx: Context, r: Report) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val list = ArrayList<JSONObject>()
        list += JSONObject().put("t", r.time).put("v", r.verdict).put("d", r.details)
        for (i in 0 until minOf(arr.length(), MAX - 1)) list += arr.getJSONObject(i)
        prefs.edit().putString(KEY, JSONArray(list).toString()).apply()
    }

    @Synchronized
    fun render(ctx: Context): String {
        val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        if (arr.length() == 0) return "아직 기록이 없어요."
        val f = SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            sb.append("■ ").append(f.format(Date(o.getLong("t")))).append('\n')
            sb.append(o.getString("v")).append('\n')
            sb.append(o.getString("d")).append("\n\n")
        }
        return sb.toString()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
