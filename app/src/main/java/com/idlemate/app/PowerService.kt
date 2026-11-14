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
    private val evLog = java.util.Collections.synchronizedList(ArrayList<String>())

    // 直接讀 /dev/input/event*（避免 getevent 輸出被管線緩衝而延遲）
    override fun startTouchWatch(): String = synchronized(this) {
        val gen = ++touchGen
        lastTouch = 0L; evCount = 0; evLog.clear()
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
                                if (type != 0 && evLog.size < 40) evLog.add("${f.name}:t$type/c$code/v$value")
                                if ((type == 1 && code == 0x14a && value == 1) ||
                                    (type == 3 && code == 0x39 && value != -1) ||
                                    (type == 1 && (code == 114 || code == 115) && value == 1)) {
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

    override fun touchInfo(): String = "收到輸入事件 $evCount 筆 [" + synchronized(evLog) { evLog.joinToString(" ") } + "]"

    override fun stopTouchWatch() { touchGen++ }

    override fun touchStamp(): Long = lastTouch

    @Volatile private var logProc: Process? = null
    @Volatile private var snapRun = false
    private val logLines = java.util.Collections.synchronizedList(ArrayList<String>())

    @Volatile private var ytState = -1
    @Volatile private var sending = false
    private val stRe = Regex("state=[A-Z_]+[(]([0-9]+)[)]")

    private fun trySendPlay() {
        logLines.add("[" + hhmmss() + "] 偵測到 播放→停止，開始送播放指令")
        val r1 = runCmd("cmd", "media_session", "dispatch", "play")
        logLines.add("[" + hhmmss() + "] cmd media_session dispatch play → " + r1.take(120))
        try { Thread.sleep(1500) } catch (_: Throwable) {}
        logLines.add("[" + hhmmss() + "] 送出後狀態 state=" + ytState)
        if (ytState != 3) {
            val r2 = runCmd("input", "keyevent", "126")
            logLines.add("[" + hhmmss() + "] input keyevent 126(MEDIA_PLAY) → " + r2.take(120))
            try { Thread.sleep(1500) } catch (_: Throwable) {}
            logLines.add("[" + hhmmss() + "] 再送後狀態 state=" + ytState)
        }
    }

    private fun runCmd(vararg args: String): String = try {
        val q = Runtime.getRuntime().exec(arrayOf(*args))
        val out = q.inputStream.bufferedReader().readText() + q.errorStream.bufferedReader().readText()
        q.waitFor(); out.trim()
    } catch (t: Throwable) { "失敗:" + t.javaClass.simpleName }

    private fun hhmmss() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    override fun startLogCapture(): String = synchronized(this) {
        runCatching { logProc?.destroy() }
        snapRun = false
        logLines.clear(); ytState = -1; sending = false
        try {
            // 三個緩衝區都要清，否則會撈到之前的歷史
            runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-b", "main,system,events", "-c")).waitFor() }
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "main,system,events", "-v", "time"))
            logProc = p
            val keys = listOf("mediasession", "ytpl.", "audiomanager", "audiofocus", "screen_off", "goingtosleep", "wm_set_keyguard_shown", "wakefulness", "sleep")
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { l ->
                        val low = l.lowercase()
                        if (keys.any { low.contains(it) } && logLines.size < 500) logLines.add(l.take(260))
                        if (low.contains("onsessionplaybackstatechanged") && low.contains("youtube")) {
                            val m = stRe.find(l)
                            val st = m?.groupValues?.get(1)?.toIntOrNull() ?: -1
                            if (st >= 0) {
                                if (ytState == 3 && (st == 1 || st == 2) && !sending) {
                                    sending = true
                                    Thread { trySendPlay() ; sending = false }.apply { isDaemon = true; start() }
                                }
                                ytState = st
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            // 每秒記錄媒體工作階段狀態；若播放中 → 停止/暫停，就自動送出「播放」指令試試
            snapRun = true
            Thread {
                var prev = ""
                var prevState = -1
                val re = Regex("state=PlaybackState [{]state=([0-9]+)")
                while (snapRun) {
                    try {
                        val txt = runCmd("dumpsys", "media_session")
                        val st = re.find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                        val cond = txt.lines().map { it.trim() }
                            .filter { it.contains("youtube", true) || it.startsWith("state=PlaybackState") || it.contains("active=") }
                            .take(12).joinToString(" | ").take(300)
                        if (cond != prev) { prev = cond; if (logLines.size < 500) logLines.add("[" + hhmmss() + "] 媒體工作階段 state=" + st + ": " + cond) }
                        prevState = st
                    } catch (_: Throwable) {}
                    try { Thread.sleep(1000) } catch (_: Throwable) {}
                }
            }.apply { isDaemon = true; start() }
            "已開始擷取日誌（" + hhmmss() + "）"
        } catch (t: Throwable) { "擷取日誌失敗：${describe(t)}" }
    }

    @Volatile private var fullProc: Process? = null
    @Volatile private var fullRun = false
    @Volatile private var fullLog: java.io.File? = null
    @Volatile private var fullState: java.io.File? = null

    override fun startFullCapture(): String {
        stopFullInternal()
        return try {
            val dir = java.io.File("/storage/emulated/0/Download")
            dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val logFile = java.io.File(dir, "IdleMate_log_$stamp.txt")
            val stateFile = java.io.File(dir, "IdleMate_state_$stamp.txt")
            fullLog = logFile; fullState = stateFile
            // 清除舊日誌最多等 3 秒（有些手機會卡住）
            runCatching {
                val c = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "main,system,events,crash", "-c"))
                if (!c.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) c.destroy()
            }
            fullProc = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-b", "main,system,events,crash", "-v", "threadtime", "-f", logFile.absolutePath
            ))
            fullRun = true
            Thread {
                val w = try { java.io.FileWriter(stateFile, true) } catch (t: Throwable) { null }
                val nl = System.lineSeparator()
                w?.write("裝置：" + Build.MANUFACTURER + " " + Build.MODEL + "  SDK=" + Build.VERSION.SDK_INT + nl)
                while (fullRun) {
                    try {
                        val sb = StringBuilder("===== " + hhmmss() + " =====" + nl)
                        sb.append("[activity]").append(nl).append(runCmd("sh", "-c",
                            "dumpsys activity activities | grep -i -E 'ResumedActivity|sleep|keyguard|occlud' | head -25")).append(nl)
                        sb.append("[window]").append(nl).append(runCmd("sh", "-c",
                            "dumpsys window | grep -i -E 'mCurrentFocus|mFocusedApp|SleepToken|mKeyguard|mAwake|mScreenOn|mDreaming' | head -25")).append(nl)
                        sb.append("[power]").append(nl).append(runCmd("sh", "-c",
                            "dumpsys power | grep -i -E 'mWakefulness=|mInteractive|Display Power|mHoldingDisplaySuspendBlocker|mScreenBrightness' | head -15")).append(nl)
                        sb.append("[display]").append(nl).append(runCmd("sh", "-c",
                            "dumpsys display | grep -i -E 'mState=|mScreenState|mPendingScreenState|Display Power' | head -10")).append(nl)
                        sb.append("[media]").append(nl).append(runCmd("sh", "-c",
                            "dumpsys media_session | grep -i -E 'package=|state=|active=' | head -20")).append(nl)
                        w?.write(sb.toString()); w?.flush()
                    } catch (_: Throwable) {}
                    try { Thread.sleep(2000) } catch (_: Throwable) {}
                }
                runCatching { w?.close() }
            }.apply { isDaemon = true; start() }
            "已開始完整存檔（" + hhmmss() + "）\n日誌：" + logFile.absolutePath + "\n狀態：" + stateFile.absolutePath
        } catch (t: Throwable) { "完整存檔失敗：${describe(t)}" }
    }

    private fun stopFullInternal() {
        fullRun = false
        runCatching { fullProc?.destroy() }
        fullProc = null
    }

    override fun stopFullCapture(): String {
        stopFullInternal()
        try { Thread.sleep(500) } catch (_: Throwable) {}
        val l = fullLog; val s = fullState
        fun info(f: java.io.File?) = if (f == null) "（無）" else f.absolutePath + "（" + (if (f.exists()) "${f.length() / 1024} KB" else "不存在") + "）"
        return "已結束完整存檔（" + hhmmss() + "）\n日誌：" + info(l) + "\n狀態：" + info(s) +
            "\n請把這兩個檔案從手機的「下載」資料夾傳到電腦。"
    }

    override fun probeTouch(): String {
        val nl = System.lineSeparator()
        val sb = StringBuilder("探測 " + hhmmss() + nl)
        fun section(title: String, body: String) {
            sb.append("== ").append(title).append(" ==").append(nl)
            sb.append(if (body.isBlank()) "（無）" else body.take(2500)).append(nl)
        }
        section("身分", runCmd("id"))
        section("/dev 觸控相關裝置", runCmd("sh", "-c", "ls -laZ /dev 2>&1 | grep -i -E 'touch|goog|offload|tbn|fts|gti|input'"))
        val found = runCmd("sh", "-c",
            "timeout 25 find /sys/devices/platform /sys/class/input /sys/kernel -maxdepth 9 " +
                "\\( -iname '*force_active*' -o -iname '*gesture*' -o -iname '*tap*' -o -iname '*wake*' " +
                "-o -iname '*screen_state*' -o -iname '*lpm*' -o -iname '*goog*' -o -iname '*touch*' -o -iname '*panel_state*' \\) " +
                "2>/dev/null | head -200")
        val files = StringBuilder()
        for (path in found.lines().map { it.trim() }.filter { it.startsWith("/") }) {
            val f = java.io.File(path)
            val kind = if (f.isDirectory) "目錄" else "檔案"
            val r = if (f.canRead()) "可讀" else "不可讀"
            val w = if (f.canWrite()) "可寫" else "不可寫"
            var content = ""
            if (f.isFile && f.canRead()) {
                content = try { f.readText().take(60).replace(nl, " ").replace("\n", " ") } catch (t: Throwable) { "讀取失敗:" + t.javaClass.simpleName }
            }
            files.append(path).append("  [").append(kind).append(" ").append(r).append(" ").append(w).append("] ").append(content).append(nl)
        }
        section("sysfs 觸控/喚醒相關（最多 200 項）", files.toString())
        section("系統服務：電源/觸控/輸入", runCmd("sh", "-c", "service list 2>&1 | grep -i -E 'power|touch|input|twoshay|gti' | head -30"))
        section("屬性 getprop", runCmd("sh", "-c", "getprop | grep -i -E 'touch|tap|doze|wake|gesture' | head -40"))
        section("Secure 設定", runCmd("sh", "-c", "settings list secure | grep -i -E 'tap|doze|wake|gesture' | head -40"))
        section("dumpsys power 摘錄", runCmd("sh", "-c", "dumpsys power | grep -i -E 'tap|wake|gesture|mode' | head -40"))
        section("cmd power help", runCmd("cmd", "power", "help").take(2500))
        return sb.toString().take(20000)
    }

    override fun stopLogCapture(): String = synchronized(this) {
        snapRun = false
        runCatching { logProc?.destroy() }
        logProc = null
        val yt = synchronized(logLines) { logLines.toList() }
        "結束 " + hhmmss() + System.lineSeparator() +
            (if (yt.isEmpty()) "（沒有擷取到相關日誌）" else yt.joinToString(System.lineSeparator()))
    }

    private fun describe(t: Throwable): String {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    override fun setDisplayMode(mode: Int): String = synchronized(LOCK) {
        try { applyMode(mode) } catch (t: Throwable) { "未預期的錯誤：${describe(t)}" }
    }

    private fun apply(on: Boolean): String = applyMode(if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)

    private fun applyMode(mode: Int): String {
        val on = mode == POWER_MODE_NORMAL
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
