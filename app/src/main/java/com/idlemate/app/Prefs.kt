package com.idlemate.app

import android.content.Context
import android.content.pm.ApplicationInfo

class Prefs(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("idlemate", Context.MODE_PRIVATE)

    /** 是否已跑過首次一鍵設定 */
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit().putBoolean("setupDone", v).apply()

    /** 邊緣把手是否啟用（預設開，開啟 App 時自動出現） */
    var edgeOn: Boolean
        get() = sp.getBoolean("edgeOn", true)
        set(v) = sp.edit().putBoolean("edgeOn", v).apply()

    var edgeLeft: Boolean
        get() = sp.getBoolean("edgeLeft", false)
        set(v) = sp.edit().putBoolean("edgeLeft", v).apply()

    /** 橫屏時把手與螢幕頂端的距離（px）；-1 表示用預設（右上角） */
    var edgeOffsetYLand: Int
        get() = sp.getInt("edgeOffsetYLand", -1)
        set(v) = sp.edit().putInt("edgeOffsetYLand", v).apply()

    /** 把手垂直偏移（px，相對螢幕中央） */
    var edgeOffsetY: Int
        get() = sp.getInt("edgeOffsetY", 0)
        set(v) = sp.edit().putInt("edgeOffsetY", v).apply()

    /** 「螢幕關閉掛機」要出現的 App；null 表示使用預設（所有遊戲） */
    var keepApps: Set<String>?
        get() = sp.getStringSet("keepApps", null)?.toSet()
        set(v) = putSet("keepApps", v)

    /** 「待機聽劇」要出現的 App；null 表示使用預設（YouTube） */
    var mediaApps: Set<String>?
        get() = sp.getStringSet("mediaApps", null)?.toSet()
        set(v) = putSet("mediaApps", v)

    private fun putSet(key: String, v: Set<String>?) {
        sp.edit().apply { if (v == null) remove(key) else putStringSet(key, v) }.apply()
    }

    fun appliesKeep(pkg: String?): Boolean {
        if (pkg == null) return false
        keepApps?.let { return pkg in it }
        return isGame(pkg)
    }

    fun appliesMedia(pkg: String?): Boolean {
        if (pkg == null) return false
        mediaApps?.let { return pkg in it }
        return pkg == DEFAULT_YOUTUBE
    }

    @Suppress("DEPRECATION")
    private fun isGame(pkg: String): Boolean = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_YOUTUBE = "com.google.android.youtube"
    }
}
