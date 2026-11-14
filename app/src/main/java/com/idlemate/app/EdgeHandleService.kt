package com.idlemate.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.IBinder
import kotlin.math.abs

/**
 * 螢幕邊緣把手：一條貼邊的細線，往螢幕內側滑（或點一下）展開選單，
 * 選單內可直接開關「螢幕關閉掛機」與「待機聽劇」。
 */
class EdgeHandleService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var handle: View? = null
    private var panel: View? = null
    private var keepAliveLabel: TextView? = null
    private var mediaLabel: TextView? = null

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
        // 外層是較寬的透明觸控區，內層才是看得見的細線
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
                    val dx = (if (left) e.rawX - downX else downX - e.rawX)
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

    private fun item(text: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setOnClickListener { onClick(this) }
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
        keepAliveLabel = item("") {
            Toast.makeText(this, Features.toggleKeepAlive(this), Toast.LENGTH_SHORT).show(); refreshLabels()
        }
        mediaLabel = item("") {
            Toast.makeText(this, Features.toggleMedia(this), Toast.LENGTH_SHORT).show(); refreshLabels()
        }
        box.addView(keepAliveLabel)
        box.addView(mediaLabel)
        box.addView(item("收起") { closePanel() }.apply { setTextColor(0xFF90A4AE.toInt()) })
        refreshLabels()

        // 點選單以外的地方就收起
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.y = y
        lp.x = dp(6)
        wm.addView(box, lp)
        panel = box

        // 從邊緣滑入
        box.translationX = (if (left) -1 else 1) * dp(220).toFloat()
        box.animate().translationX(0f).setDuration(180).start()
    }

    private fun refreshLabels() {
        val p = prefs
        keepAliveLabel?.apply {
            text = "🔋 螢幕關閉掛機　${if (p.keepAliveOn) "● 開" else "○ 關"}"
            setTextColor(if (p.keepAliveOn) 0xFF80E27E.toInt() else Color.WHITE)
        }
        mediaLabel?.apply {
            text = "🎧 待機聽劇　${if (p.mediaResumeOn) "● 開" else "○ 關"}"
            setTextColor(if (p.mediaResumeOn) 0xFF80E27E.toInt() else Color.WHITE)
        }
    }

    private fun closePanel() {
        val p = panel ?: return
        panel = null
        p.animate().translationX((if (left) -1 else 1) * dp(220).toFloat()).setDuration(150)
            .withEndAction { runCatching { wm.removeView(p) } }.start()
        handle?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        panel?.let { runCatching { wm.removeView(it) } }
        handle?.let { runCatching { wm.removeView(it) } }
        panel = null; handle = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
