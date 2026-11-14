package com.idlemate.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("idlemate", Context.MODE_PRIVATE)

    var keepAliveOn: Boolean
        get() = sp.getBoolean("keepAliveOn", false)
        set(v) = sp.edit().putBoolean("keepAliveOn", v).apply()

    var targetPackage: String?
        get() = sp.getString("targetPackage", null)
        set(v) = sp.edit().putString("targetPackage", v).apply()

    var mediaResumeOn: Boolean
        get() = sp.getBoolean("mediaResumeOn", false)
        set(v) = sp.edit().putBoolean("mediaResumeOn", v).apply()

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

    /** 把手垂直偏移（px，相對螢幕中央） */
    var edgeOffsetY: Int
        get() = sp.getInt("edgeOffsetY", 0)
        set(v) = sp.edit().putInt("edgeOffsetY", v).apply()
}
