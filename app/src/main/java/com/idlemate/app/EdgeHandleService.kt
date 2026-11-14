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
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 螢幕邊緣把手 + 黑屏模式。
 * - 把手：貼邊的一條細線，往內滑（或點一下）展開選單。
 * - 選單按鈕「點一下就執行」：整個螢幕蓋上全黑、亮度最低的畫面（看起來像關螢幕），
 *   但系統仍認為螢幕開著，所以 App 與影音不會被暫停。雙擊螢幕即可恢復。
 */
class EdgeHandleService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var handle: View? = null
    private var panel: View? = null

    private var blackout: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioCb: AudioManager.AudioPlaybackCallback? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        // 使用者在黑屏時按電源鍵 → 變成真的鎖屏，黑色畫面就不該再蓋著
        override fun onReceive(context: Context, intent: Intent) = endBlackout()
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
        box.addView(item("🔋 螢幕關閉掛機") { startBlackout(mediaMode = false) })
        box.addView(item("🎧 待機聽劇") { startBlackout(mediaMode = true) })
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

    // ───────────── 黑屏模式 ─────────────

    @Suppress("DEPRECATION")
    private fun startBlackout(mediaMode: Boolean) {
        if (blackout != null) return
        closePanel()
        handle?.visibility = View.GONE

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = 0f   // 亮度壓到最低
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val hint = TextView(this).apply {
            text = "雙擊螢幕喚醒"
            setTextColor(0xFF555555.toInt())
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(hint, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        // 全黑畫面吃掉所有觸控，避免誤觸底下的 App；只有雙擊才會喚醒
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { endBlackout(); return true }
        })
        root.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        wm.addView(root, lp)
        blackout = root
        handler.postDelayed({ hint.visibility = View.GONE }, 3000)

        // 保持 CPU 與 Wi-Fi 運作
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdleMate:blackout")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IdleMate:blackout-wifi")
            .apply { setReferenceCounted(false); acquire() }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        if (mediaMode) watchAudio()
    }

    /** 待機聽劇：剛進入黑屏的 10 秒內，若聲音意外停了，就補送一次「播放」。 */
    private fun watchAudio() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val startedAt = System.currentTimeMillis()
        var wasPlaying = am.isMusicActive
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                val playing = am.isMusicActive
                if (wasPlaying && !playing && System.currentTimeMillis() - startedAt < 10_000) {
                    handler.postDelayed({
                        if (blackout != null && !am.isMusicActive) {
                            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                        }
                    }, 800)
                }
                wasPlaying = playing
            }
        }
        am.registerAudioPlaybackCallback(cb, handler)
        audioCb = cb
    }

    private fun endBlackout() {
        val v = blackout ?: return
        blackout = null
        runCatching { wm.removeView(v) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        audioCb?.let { cb ->
            runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioPlaybackCallback(cb) }
        }
        audioCb = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        handle?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        endBlackout()
        handler.removeCallbacksAndMessages(null)
        panel?.let { runCatching { wm.removeView(it) } }
        handle?.let { runCatching { wm.removeView(it) } }
        panel = null; handle = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
