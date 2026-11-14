package com.idlemate.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 螢幕邊緣把手 + 一鍵鎖屏。
 * - 把手：貼邊的一條細線，往內滑（或點一下）展開選單。
 * - 選單按鈕「點一下就執行」：真的把螢幕鎖掉，並在鎖屏期間
 *   ① 持有 CPU / Wi-Fi 鎖讓 App 繼續運作；
 *   ② 若鎖屏前正在播放聲音，鎖屏後 App 把播放暫停時，直接對它的播放器下「播放」。
 */
class EdgeHandleService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var handle: View? = null
    private var panel: View? = null

    // 鎖屏工作階段
    private var sessionActive = false
    private var wasPlaying = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> endSession()   // 使用者回來了，結束這次掛機
            }
        }
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
        box.addView(item("🔋 螢幕關閉掛機") { lockAndKeep(resumeAudio = false) })
        box.addView(item("🎧 待機聽劇") { lockAndKeep(resumeAudio = true) })
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
        handle?.visibility = View.VISIBLE
    }

    // ───────────── 一鍵鎖屏 ─────────────

    private fun lockAndKeep(resumeAudio: Boolean) {
        closePanel()
        if (!ScreenLock.isAdminActive(this)) {
            ScreenLock.requestAdmin(this)
            Toast.makeText(this, "請先啟用「裝置管理員」，才能自動關閉螢幕", Toast.LENGTH_LONG).show()
            return
        }
        endSession()
        sessionActive = true
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        wasPlaying = resumeAudio && am.isMusicActive
        DebugLog.add(this, "── 點擊 ${if (resumeAudio) "待機聽劇" else "掛機"}：鎖屏前正在播放=${am.isMusicActive}，" +
            "通知存取權=${OemHelper.isNotificationListenerEnabled(this)}")

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdleMate:session")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IdleMate:session-wifi")
            .apply { setReferenceCounted(false); acquire() }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })

        // 等選單收起、接收器就緒後再鎖屏
        handler.postDelayed({ ScreenLock.lockNow(this) }, 400)
    }

    /** 螢幕關掉後，若原本在播放，就反覆確認聲音有沒有被 App 暫停，被暫停就叫它繼續。 */
    private fun onScreenOff() {
        DebugLog.add(this, "螢幕已關閉（工作階段=$sessionActive，需恢復播放=$wasPlaying）")
        if (!sessionActive || !wasPlaying) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val delays = longArrayOf(300, 800, 1500, 3000, 5000, 8000, 12000)
        delays.forEachIndexed { i, delay ->
            handler.postDelayed({
                if (!sessionActive) return@postDelayed
                val active = am.isMusicActive
                if (active) {
                    DebugLog.add(this, "+${delay}ms 聲音正在播放 ✓")
                } else {
                    resumeNow(am, delay, tryKey = i >= 2)
                }
            }, delay)
        }
    }

    /** 先對播放器直接下「播放」（需通知存取權）；稍後仍沒聲音，再加送系統媒體鍵。 */
    private fun resumeNow(am: AudioManager, delay: Long, tryKey: Boolean) {
        val summary = StringBuilder("+${delay}ms 沒有聲音 → ")
        val direct = runCatching {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
            summary.append("播放器=").append(
                sessions.joinToString { "${it.packageName}:${it.playbackState?.state}" }.ifEmpty { "無" }
            )
            val target = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
                ?: sessions.firstOrNull()
            target?.transportControls?.play() ?: error("沒有可控制的播放器")
            summary.append(" 已送出play(${target.packageName})")
        }.onFailure { summary.append(" 直接控制失敗：${it.javaClass.simpleName} ${it.message ?: ""}") }
            .isSuccess
        if (!direct || tryKey) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            summary.append(" 已送出媒體鍵")
        }
        DebugLog.add(this, summary.toString())
    }

    private fun endSession() {
        if (!sessionActive) return
        sessionActive = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }

    override fun onDestroy() {
        endSession()
        panel?.let { runCatching { wm.removeView(it) } }
        handle?.let { runCatching { wm.removeView(it) } }
        panel = null; handle = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
