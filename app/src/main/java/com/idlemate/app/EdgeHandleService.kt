package com.idlemate.app

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 螢幕邊緣把手 + 關閉螢幕面板。
 * - 把手只在「使用者指定的 App」位於前景時出現（掛機預設遊戲、聽劇預設 YouTube），
 *   選單也只列出適用於該 App 的功能。
 * - 點選單按鈕：透過 Shizuku 關掉螢幕面板，系統不睡眠，App 與影片照常運行。
 */
class EdgeHandleService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())     // 工作階段用（endSession 會清空）
    private val pollHandler = Handler(Looper.getMainLooper()) // 前景偵測用（獨立，不受影響）

    private var handle: View? = null
    private var panel: View? = null

    // 目前前景 App 與適用的功能
    private var currentPkg: String? = null
    private var lastPoll = 0L
    private var showKeep = true
    private var showMedia = true

    // 螢幕面板關閉期間的工作階段
    private var sessionActive = false
    private var keepAwakeView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var awaitingWake = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // 使用者按了電源鍵 → 系統真的睡眠了：結束本次，但保留接收器等待下次亮屏
                Intent.ACTION_SCREEN_OFF -> if (sessionActive) {
                    endSession(restorePanel = false, keepReceiver = true)
                    awaitingWake = true
                    // 立刻把螢幕叫醒，使用者只按一次電源鍵就看到鎖屏畫面
                    handler.postDelayed({ wakeScreen() }, 200)
                }
                // 亮屏時，保險起見再把面板電源設回正常
                Intent.ACTION_SCREEN_ON -> if (awaitingWake) {
                    awaitingWake = false
                    runCatching { unregisterReceiver(this) }
                    ShizukuHelper.setDisplayPower(this@EdgeHandleService, on = true) {
                        DebugLog.add(this@EdgeHandleService, "亮屏後恢復面板：$it")
                    }
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollForeground()
            pollHandler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val left get() = prefs.edgeLeft

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            3, Notif.build(this, "邊緣把手", "在指定的 App 中從螢幕邊緣滑出選單"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (!OemHelper.canOverlay(this)) { stopSelf(); return START_NOT_STICKY }
        if (handle == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = Prefs(this)
            showHandle()
        }
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
        return START_STICKY
    }

    // ───────────── 前景 App 偵測 ─────────────

    private fun pollForeground() {
        if (!OemHelper.usageAccessGranted(this)) {
            // 沒有「使用狀況存取」就無法分辨 App：先在所有 App 顯示兩個功能
            showKeep = true; showMedia = true
            updateHandle()
            return
        }
        runCatching {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = if (lastPoll == 0L) now - 24 * 3600_000L else lastPoll - 1500
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> currentPkg = e.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == currentPkg) currentPkg = null
                }
            }
            lastPoll = now
        }
        showKeep = prefs.appliesKeep(currentPkg)
        showMedia = prefs.appliesMedia(currentPkg)
        updateHandle()
    }

    /** 把手只在適用的 App、且沒有選單／工作階段時才顯示。 */
    private fun updateHandle() {
        val applicable = showKeep || showMedia
        handle?.visibility =
            if (applicable && panel == null && !sessionActive) View.VISIBLE else View.GONE
    }

    // ───────────── 邊緣把手 ─────────────

    private fun params(w: Int, h: Int, watchOutside: Boolean = false) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        y = prefs.edgeOffsetY
    }

    private fun showHandle() {
        val bar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat(); setColor(0xB0546E7A.toInt())
            }
        }
        val onLeft = left
        val touch = FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(dp(5), dp(96),
                (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL))
        }
        val lp = params(dp(28), dp(120))

        var downX = 0f; var downY = 0f; var startY = 0; var mode = 0 // 0 未定 1 橫滑 2 縱拖
        touch.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startY = lp.y; mode = 0 }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (if (onLeft) e.rawX - downX else downX - e.rawX)
                    val dy = e.rawY - downY
                    if (mode == 0 && (abs(dx) > dp(8) || abs(dy) > dp(8)))
                        mode = if (abs(dx) >= abs(dy)) 1 else 2
                    if (mode == 1 && dx > dp(24)) { openPanel(lp.y); mode = -1 }
                    if (mode == 2) { lp.y = startY + dy.toInt(); wm.updateViewLayout(touch, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 0) openPanel(lp.y)           // 點一下也展開
                    if (mode == 2) prefs.edgeOffsetY = lp.y  // 記住位置
                }
            }
            true
        }
        touch.visibility = View.GONE  // 先隱藏，偵測到適用的 App 才顯示
        wm.addView(touch, lp)
        handle = touch
    }

    private fun item(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setOnClickListener { onClick() }
    }

    private fun openPanel(y: Int) {
        if (panel != null) return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF2263238.toInt())
            }
            setPadding(0, dp(6), 0, dp(6))
        }
        // 只列出適用於目前 App 的功能
        if (showKeep) box.addView(item("🔋 螢幕關閉掛機") { startSession() })
        if (showMedia) box.addView(item("🎧 待機聽劇") { startSession() })
        box.addView(item("收起") { closePanel() }.apply { setTextColor(0xFF90A4AE.toInt()) })

        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.y = y
        lp.x = dp(6)
        wm.addView(box, lp)
        panel = box
        updateHandle()

        box.translationX = (if (left) -1 else 1) * dp(220).toFloat()
        box.animate().translationX(0f).setDuration(180).start()
    }

    private fun closePanel() {
        val p = panel ?: return
        panel = null
        p.animate().translationX((if (left) -1 else 1) * dp(220).toFloat()).setDuration(150)
            .withEndAction { runCatching { wm.removeView(p) } }.start()
        updateHandle()
    }

    // ───────────── 關閉螢幕面板 ─────────────

    private fun startSession() {
        closePanel()
        if (!ShizukuHelper.granted()) {
            val msg = when {
                !ShizukuHelper.installed(this) -> "請先安裝 Shizuku"
                !ShizukuHelper.running() -> "請先啟動 Shizuku（用無線偵錯）"
                else -> "請在 IdleMate 內授權 Shizuku"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (sessionActive) return
        sessionActive = true
        updateHandle()

        addKeepAwakeWindow()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdleMate:panel-off")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IdleMate:panel-off-wifi")
            .apply { setReferenceCounted(false); acquire() }
        registerReceiver(screenOffReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })

        // 等選單收起後再關面板
        handler.postDelayed({
            if (!sessionActive) return@postDelayed
            ShizukuHelper.setDisplayPower(this, on = false) { DebugLog.add(this, "關閉面板：$it") }
            // 實驗：讓手機進入鎖定狀態（顯示鎖屏）但不睡眠，觀察 YouTube 會不會繼續播
            handler.postDelayed({
                if (sessionActive) ShizukuHelper.lockKeyguard(this) { DebugLog.add(this, "鎖屏：$it") }
            }, 600)
        }, 400)
    }

    /** 1×1、不接收觸控的透明視窗，只用來讓系統不因螢幕逾時而睡眠。 */
    private fun addKeepAwakeWindow() {
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val v = View(this)
        wm.addView(v, lp)
        keepAwakeView = v
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        // 方法一：帶「喚醒」旗標的喚醒鎖；方法二：以 shell 身分送出喚醒按鍵
        runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "IdleMate:wake"
            ).acquire(3000)
        }.onFailure { DebugLog.add(this, "喚醒鎖失敗：$it") }
        ShizukuHelper.wakeUp(this) { DebugLog.add(this, "喚醒：$it") }
    }

    private fun endSession(restorePanel: Boolean, keepReceiver: Boolean = false) {
        if (!sessionActive) return
        sessionActive = false
        handler.removeCallbacksAndMessages(null)
        keepAwakeView?.let { runCatching { wm.removeView(it) } }; keepAwakeView = null
        if (!keepReceiver) runCatching { unregisterReceiver(screenOffReceiver) }
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        if (restorePanel) {
            ShizukuHelper.setDisplayPower(this, on = true) { DebugLog.add(this, "恢復面板：$it") }
        }
        updateHandle()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacksAndMessages(null)
        endSession(restorePanel = true)
        if (awaitingWake) runCatching { unregisterReceiver(screenOffReceiver) }
        panel?.let { runCatching { wm.removeView(it) } }
        handle?.let { runCatching { wm.removeView(it) } }
        panel = null; handle = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
