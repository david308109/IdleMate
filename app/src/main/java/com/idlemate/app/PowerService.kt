package com.idlemate.app

import android.os.Build
import android.os.IBinder
import android.system.Os
import kotlin.system.exitProcess

/**
 * 在 Shizuku 的 shell 權限行程中執行：把實體螢幕面板關掉／打開，
 * 但不讓系統進入睡眠，所以前景 App（例如 YouTube）不會收到「螢幕關閉」而暫停。
 * 做法參考 scrcpy 的 --turn-screen-off（Device.setDisplayPower）。
 */
class PowerService : IPowerService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun setDisplayPower(on: Boolean): String =
        try { apply(on) } catch (t: Throwable) { "未預期的錯誤：${describe(t)}" }

    override fun wakeUp(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "224")) // KEYCODE_WAKEUP
        "喚醒按鍵已送出，結束碼 ${p.waitFor()}"
    } catch (t: Throwable) { "喚醒失敗：${describe(t)}" }

    private fun describe(t: Throwable): String {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private fun apply(on: Boolean): String {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val sb = StringBuilder("SDK=${Build.VERSION.SDK_INT} ")

        val sc = Class.forName("android.view.SurfaceControl")
        val setMode = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)

        val tokens = ArrayList<IBinder>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val hasIds = runCatching { sc.getMethod("getPhysicalDisplayIds"); true }.getOrDefault(false)
                // Android 14 起，這些內部方法搬到 DisplayControl
                val owner: Class<*> = if (Build.VERSION.SDK_INT >= 34 && !hasIds) loadDisplayControl() else sc
                val ids = owner.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                val getToken = owner.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                for (id in ids) (getToken.invoke(null, id) as? IBinder)?.let { tokens.add(it) }
            } else {
                tokens.add(sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0) as IBinder)
            }
        } catch (t: Throwable) {
            sb.append("取得螢幕代碼失敗(").append(describe(t)).append(") ")
        }

        var ok = tokens.isNotEmpty()
        for (token in tokens) {
            try { setMode.invoke(null, token, mode) } catch (t: Throwable) {
                ok = false; sb.append("設定電源模式失敗(").append(describe(t)).append(") ")
            }
        }
        if (ok) return sb.append("成功：SurfaceControl，${tokens.size} 個螢幕").toString()

        // 備援：Android 15 的 DisplayManagerGlobal.requestDisplayPower
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val inst = dmg.getDeclaredMethod("getInstance").invoke(null)
                val r = inst!!.javaClass.getMethod(
                    "requestDisplayPower", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                ).invoke(inst, 0, on)
                return sb.append("備援 requestDisplayPower 回傳 $r").toString()
            } catch (t: Throwable) {
                sb.append("備援也失敗(").append(describe(t)).append(") ")
            }
        }
        return sb.append("失敗").toString()
    }

    // 系統函式庫在同一個行程只能載入一次，所以載入後要留著重複使用
    private var displayControlCache: Class<*>? = null

    private fun loadDisplayControl(): Class<*> {
        displayControlCache?.let { return it }
        val cls = doLoadDisplayControl()
        displayControlCache = cls
        return cls
    }

    private fun doLoadDisplayControl(): Class<*> {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java
        )
        val classpath = Os.getenv("SYSTEMSERVERCLASSPATH")
            ?: throw IllegalStateException("找不到 SYSTEMSERVERCLASSPATH")
        val loader = create.invoke(null, classpath, null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val cls = loader.loadClass("com.android.server.display.DisplayControl")
        val load = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        load.isAccessible = true
        load.invoke(Runtime.getRuntime(), cls, "android_servers")
        return cls
    }

    private companion object {
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
    }
}
