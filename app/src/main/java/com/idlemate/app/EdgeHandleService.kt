package com.idlemate.app

import android.app.Service
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 螢幕邊緣把手 + 關閉螢幕面板。
 * - 把手：貼邊的一條細線，往內滑（或點一下）展開選單。
 * - 選單按鈕「點一下就執行」：透過 Shizuku 把實體螢幕面板關掉，但系統並未進入睡眠，
 *   所以 YouTube 等 App 不會收到「螢幕關閉」而暫停。雙擊螢幕可恢復。
 */
class EdgeHandleService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var handle: View? = null
    private var panel: View? = null

    // 螢幕面板關閉期間的工作階段
    private var sessionActive = false
    private var cover: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        // 使用者在面板關閉期間按了電源鍵 → 系統真的睡眠了，結束本次，面板電源交給系統處理
        override fun onReceive(context: Context, intent: Intent) = endSession(restorePanel = false)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val left get() = prefs.edgeLeft

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            3, Notif.build(this, "邊緣把手", "從螢幕邊緣滑出選單"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (!OemHelper.canOverlay(this)) { stopSelf(); return START_NOT_STICKY }
        if (handle == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = Prefs(this)
            showHandle()
        }
        return START_STICKY
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
        handle?.visibility = View.GONE

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0xF2263238.toInt())
            }
            setPadding(0, dp(6), 0, dp(6))
        }
        box.addView(item("🔋 螢幕關閉掛機") { startSession() })
        box.addView(item("🎧 待機聽劇") { startSession() })
        box.addView(item("收起") { closePanel() }.apply { setTextColor(0xFF90A4AE.toInt()) })

        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.y = y
        lp.x = dp(6)
        wm.addView(box, lp)
        panel = box

        box.translationX = (if (left) -1 else 1) * dp(220).toFloat()
        box.animate().translationX(0f).setDuration(180).start()
    }

    private fun closePanel() {
        val p = panel ?: return
        panel = null
        p.animate().translationX((if (left) -1 else 1) * dp(220).toFloat()).setDuration(150)
            .withEndAction { runCatching { wm.removeView(p) } }.start()
        if (!sessionActive) handle?.visibility = View.VISIBLE
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
        handle?.visibility = View.GONE

        addCover()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdleMate:panel-off")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IdleMate:panel-off-wifi")
            .apply { setReferenceCounted(false); acquire() }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        // 等選單收起後再關面板
        handler.postDelayed({
            if (!sessionActive) return@postDelayed
            ShizukuHelper.setDisplayPower(this, on = false) { DebugLog.add(this, "關閉面板：$it") }
        }, 400)
    }

    /** 全螢幕透明視窗：吃掉所有觸控（避免口袋誤觸）、保持系統清醒，雙擊才恢復。 */
    private fun addCover() {
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        val v = FrameLayout(this)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { endSession(restorePanel = true); return true }
        })
        v.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }
        wm.addView(v, lp)
        cover = v
    }

    private fun endSession(restorePanel: Boolean) {
        if (!sessionActive) return
        sessionActive = false
        handler.removeCallbacksAndMessages(null)
        cover?.let { runCatching { wm.removeView(it) } }; cover = null
        runCatching { unregisterReceiver(screenOffReceiver) }
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        if (restorePanel) {
            ShizukuHelper.setDisplayPower(this, on = true) { DebugLog.add(this, "恢復面板：$it") }
        }
        handle?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        endSession(restorePanel = true)
        panel?.let { runCatching { wm.removeView(it) } }
        handle?.let { runCatching { wm.removeView(it) } }
        panel = null; handle = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
