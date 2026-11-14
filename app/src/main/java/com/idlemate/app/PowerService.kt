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

    override fun setDisplayPower(on: Boolean): String = synchronized(LOCK) {
        try { apply(on) } catch (t: Throwable) { "未預期的錯誤：${describe(t)}" }
    }

    /** 優先直接呼叫電源服務 wakeUp（很快）；失敗才退回啟動 input 指令（較慢）。 */
    override fun wakeUp(): String {
        val t0 = System.currentTimeMillis()
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "power") as IBinder
            val stub = Class.forName("android.os.IPowerManager\$Stub")
            val pm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val m = pm.javaClass.methods.first {
                it.name == "wakeUp" && it.parameterTypes.contentEquals(
                    arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java)
                )
            }
            // reason 1 = 電源鍵；呼叫端是 shell，所以套件名用 com.android.shell
            m.invoke(pm, android.os.SystemClock.uptimeMillis(), 1, "IdleMate", "com.android.shell")
            return "喚醒：電源服務 wakeUp（${System.currentTimeMillis() - t0}ms）"
        } catch (t: Throwable) {
            val why = describe(t)
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "224")) // KEYCODE_WAKEUP
                "喚醒：電源服務失敗($why)，改用 input，結束碼 ${p.waitFor()}（${System.currentTimeMillis() - t0}ms）"
            } catch (t2: Throwable) { "喚醒失敗：$why / ${describe(t2)}" }
        }
    }

    /** 呼叫 IWindowManager.lockNow：顯示鎖屏，但不像電源鍵那樣讓系統睡眠。 */
    override fun lockKeyguard(): String = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val wm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        wm!!.javaClass.getMethod("lockNow", android.os.Bundle::class.java).invoke(wm, null as android.os.Bundle?)
        "已呼叫 lockNow（鎖屏，不睡眠）"
    } catch (t: Throwable) { "lockNow 失敗：${describe(t)}" }

    @Volatile private var touchGen = 0
    @Volatile private var lastTouch = 0L
    @Volatile private var evCount = 0

    // 直接讀 /dev/input/event*（避免 getevent 輸出被管線緩衝而延遲）
    override fun startTouchWatch(): String = synchronized(this) {
        val gen = ++touchGen
        lastTouch = 0L; evCount = 0
        val files = java.io.File("/dev/input").listFiles { f -> f.name.startsWith("event") }
            ?: return "無法列出 /dev/input"
        var opened = 0
        val errs = StringBuilder()
        for (f in files) {
            try {
                val ins = java.io.FileInputStream(f)
                opened++
                Thread {
                    val buf = ByteArray(24 * 16)
                    try {
                        while (gen == touchGen) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            val bb = java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.nativeOrder())
                            var o = 0
                            while (o + 24 <= n) {
                                val type = bb.getShort(o + 16).toInt() and 0xffff
                                val code = bb.getShort(o + 18).toInt() and 0xffff
                                val value = bb.getInt(o + 20)
                                evCount++
                                if ((type == 1 && code == 0x14a && value == 1) ||
                                    (type == 3 && code == 0x39 && value != -1)) {
                                    if (gen == touchGen) lastTouch = System.currentTimeMillis()
                                }
                                o += 24
                            }
                        }
                    } catch (_: Throwable) {}
                    runCatching { ins.close() }
                }.apply { isDaemon = true; start() }
            } catch (t: Throwable) { errs.append(f.name).append(':').append(t.javaClass.simpleName).append(' ') }
        }
        "已監聽 $opened/${files.size} 個輸入裝置 $errs"
    }

    override fun touchInfo(): String = "收到輸入事件 $evCount 筆"

    override fun stopTouchWatch() { touchGen++ }

    override fun touchStamp(): Long = lastTouch

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

    // 系統函式庫在同一個行程只能載入一次，所以載入後要留著重複使用（跨 PowerService 實例共用）
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
        val LOCK = Any()
        @Volatile var displayControlCache: Class<*>? = null
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
    }
}
