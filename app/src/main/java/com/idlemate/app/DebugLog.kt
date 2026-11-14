package com.idlemate.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 簡易診斷紀錄（存在 SharedPreferences，只留最近 60 行），顯示在主畫面。 */
object DebugLog {
    private const val KEY = "debuglog"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(ctx: Context, msg: String) {
        val sp = ctx.getSharedPreferences("idlemate", Context.MODE_PRIVATE)
        val lines = (sp.getString(KEY, "") ?: "").lines().filter { it.isNotBlank() }
        val next = (lines + "${fmt.format(Date())} $msg").takeLast(60)
        sp.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun read(ctx: Context): String =
        ctx.getSharedPreferences("idlemate", Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("idlemate", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
