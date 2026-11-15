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

    /** 「熄屏運行」要出現的 App；null 表示使用預設（所有遊戲 + YouTube） */
    var apps: Set<String>?
        get() = sp.getStringSet("apps", null)?.toSet()
        set(v) {
            putSet("apps", v)
            // 舊版的兩份清單（掛機 / 聽劇）已合併，存新清單時一併清掉
            sp.edit().remove("keepApps").remove("mediaApps").apply()
        }

    private fun putSet(key: String, v: Set<String>?) {
        sp.edit().apply { if (v == null) remove(key) else putStringSet(key, v) }.apply()
    }

    /** 還沒存過新清單，但有舊版（掛機 / 聽劇）的自訂清單 */
    val usingLegacyApps: Boolean
        get() = apps == null && (sp.contains("keepApps") || sp.contains("mediaApps"))

    fun applies(pkg: String?): Boolean {
        if (pkg == null) return false
        apps?.let { return pkg in it }
        // 舊版設定：還沒存過新清單時，沿用「掛機」與「聽劇」兩份清單的聯集
        val legacyKeep = sp.getStringSet("keepApps", null)
        val legacyMedia = sp.getStringSet("mediaApps", null)
        val keep = legacyKeep?.let { pkg in it } ?: isGame(pkg)
        val media = legacyMedia?.let { pkg in it } ?: (pkg == DEFAULT_YOUTUBE)
        return keep || media
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
