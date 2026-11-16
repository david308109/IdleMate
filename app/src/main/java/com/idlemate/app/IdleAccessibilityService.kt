package com.idlemate.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * IdleMate 的核心，只需要「協助工具」這一個權限：
 * 1. 知道目前前景是哪個 App，只在指定的 App 顯示邊緣把手。
 * 2. 畫邊緣把手與選單（協助工具的視窗不需要「顯示在其他應用程式上層」權限）。
 * 3. 全黑遮罩：蓋住整個畫面（含狀態列、導覽列），擋住點擊；系統不睡眠，App 與影片照常運行。
 * 4. 點兩下或按電源鍵：鎖定並亮屏，直接看到鎖屏畫面。
 * 不讀取任何畫面內容。
 */
class IdleAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: IdleAccessibilityService? = null
            private set

        /** 系統設定裡是否已開啟本服務。 */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, IdleAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** 開啟協助工具清單，並自動捲到「IdleMate」那一項、標亮。 */
        fun openSettings(ctx: Context) {
            val key = ComponentName(ctx, IdleAccessibilityService::class.java).flattenToString()
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .putExtra(":settings:fragment_args_key", key)
                        .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                            putString(":settings:fragment_args_key", key)
                        })
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var foregroundPkg: String? = null
    private val activityCache = HashMap<String, Boolean>()

    private var handle: View? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var cover: View? = null
    private var sessionActive = false
    private var applies = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val left get() = prefs.edgeLeft

    // 螢幕開關 / 解鎖：決定把手要不要顯示；全黑期間螢幕被關掉 → 移除遮罩並亮屏
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && sessionActive) {
                DebugLog.add(this@IdleAccessibilityService, "螢幕已關閉並上鎖 → 移除遮罩、亮屏")
                endSession()
                wakeScreen()
            }
            updateHandle()
        }
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        refreshHandle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        // 桌面（含最近使用的 App 畫面）送來的事件常常不是標準的 Activity 類別，一律當成回到桌面
        if (pkg !in homePackages() && !isActivity(pkg, cls)) return
        if (pkg != foregroundPkg) {
            foregroundPkg = pkg
            closePanel()
        }
        updateHandle()
    }

    private var homeCache: Set<String>? = null
    private var homeCacheAt = 0L

    /** 所有桌面 App 的套件名稱（每分鐘更新一次，換桌面也能跟上）。 */
    private fun homePackages(): Set<String> {
        val now = System.currentTimeMillis()
        homeCache?.let { if (now - homeCacheAt < 60_000) return it }
        val set = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        homeCache = set; homeCacheAt = now
        return set
    }

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut("$pkg/$cls") {
        runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        endSession()
        runCatching { unregisterReceiver(screenReceiver) }
        panel?.let { runCatching { wm.removeView(it) } }; panel = null
        handle?.let { runCatching { wm.removeView(it) } }; handle = null
        instance = null
    }

    // ───────────── 邊緣把手 ─────────────

    /** 重新建立把手（例如切換左右邊、開關把手後）。 */
    fun refreshHandle() {
        closePanel()
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
        if (prefs.edgeOn) showHandle()
        updateHandle()
    }

    private fun updateHandle() {
        val h = handle ?: return
        applies = prefs.applies(foregroundPkg)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val visible = prefs.edgeOn && applies && panel == null && !sessionActive &&
            pm.isInteractive && !km.isKeyguardLocked
        h.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private val landscape get() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** 橫屏：預設在右上角（從頂端往下算）；直屏：沿用原本的位置（相對螢幕中央）。 */
    private fun handleY(): Int =
        if (landscape) prefs.edgeOffsetYLand.takeIf { it >= 0 } ?: dp(24) else prefs.edgeOffsetY

    private fun saveHandleY(y: Int) {
        if (landscape) prefs.edgeOffsetYLand = y.coerceAtLeast(0) else prefs.edgeOffsetY = y
    }

    private fun params(w: Int, h: Int, watchOutside: Boolean = false, intoCutout: Boolean = true) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = (if (left) Gravity.START else Gravity.END) or
            (if (landscape) Gravity.TOP else Gravity.CENTER_VERTICAL)
        y = handleY()
        // 把手：允許延伸進前鏡頭挖孔那一條，橫屏時才能貼齊螢幕最邊緣。
        // 選單：不延伸，與邊緣（或鏡頭那一條）保持距離
        if (intoCutout && Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (intoCutout && Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 轉向時，把手換到該方向記住的位置
        if (instance != null) refreshHandle()
    }

    private fun showHandle() {
        val thin = dp(5); val thick = dp(22)  // 移動時加粗
        val bar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat(); setColor(0xB0546E7A.toInt())  // 圓角取大值 → 兩端是半圓（膠囊形）
            }
        }
        val onLeft = left
        val barLp = FrameLayout.LayoutParams(thin, dp(77),
            (if (onLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL)
        val touch = FrameLayout(this).apply { addView(bar, barLp) }
        val lp = params(dp(28), dp(120))

        fun setThick(on: Boolean) {
            barLp.width = if (on) thick else thin
            bar.layoutParams = barLp
            (bar.background as GradientDrawable).setColor(if (on) 0xFF90A4AE.toInt() else 0xB0546E7A.toInt())
        }

        // 告訴系統：把手這一塊不要觸發「返回」手勢，避免滑出選單時同時返回上一頁
        if (Build.VERSION.SDK_INT >= 29) {
            touch.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, v.width, v.height))
            }
        }

        val slop = dp(8)
        var downX = 0f; var downY = 0f; var startY = 0
        var moving = false   // 長按 0.5 秒後進入移動模式
        var moved = false    // 手指已經滑動（不算點一下）
        var opened = false
        val longPress = Runnable {
            moving = true
            setThick(true)
        }
        touch.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startY = lp.y
                    moving = false; moved = false; opened = false
                    handler.postDelayed(longPress, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (if (onLeft) e.rawX - downX else downX - e.rawX)
                    val dy = e.rawY - downY
                    if (moving) {
                        lp.y = (startY + dy.toInt()).let { if (landscape) it.coerceAtLeast(0) else it }
                        runCatching { wm.updateViewLayout(touch, lp) }
                    } else {
                        if (abs(dx) > slop || abs(dy) > slop) {
                            moved = true
                            handler.removeCallbacks(longPress)  // 手指一動就不是長按
                        }
                        if (!opened && dx > dp(24) && abs(dx) >= abs(dy)) { opened = true; openPanel(lp.y) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (moving) { saveHandleY(lp.y); setThick(false) }
                    moving = false
                }
            }
            true
        }
        touch.visibility = View.GONE  // 先隱藏，偵測到適用的 App 才顯示
        runCatching { wm.addView(touch, lp) }.onFailure { DebugLog.add(this, "邊緣把手顯示失敗：$it"); return }
        handle = touch
        handleLp = lp
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
        box.addView(item("🌙 熄屏運行") { startSession() })
        box.addView(item("收起") { closePanel() }.apply { setTextColor(0xFF90A4AE.toInt()) })
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, watchOutside = true, intoCutout = false)
        lp.y = y
        lp.x = dp(12)  // 選單與螢幕邊緣留一段距離
        runCatching { wm.addView(box, lp) }.onFailure { return }
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

    // ───────────── 全黑遮罩 ─────────────

    private fun startSession() {
        closePanel()
        if (sessionActive) return
        sessionActive = true
        updateHandle()
        // 等選單收起後再蓋上遮罩
        handler.postDelayed({
            if (!sessionActive) return@postDelayed
            if (showCover()) DebugLog.add(this, "全黑遮罩：開始")
            else endSession()
        }, 200)
    }

    private fun showCover(): Boolean {
        if (cover != null) return true
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap(); return true }
        })
        // 所有點擊都由遮罩吃掉，不會傳到底下的 App
        v.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                // 不搶焦點：底下的 App 仍是前景、有焦點，影片與遊戲照常
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) {
                // 用實體螢幕的完整大小，連狀態列與導覽列一起蓋住。
                // 用「長邊 × 長邊」的正方形：轉向後不論直橫都能完整蓋住（超出部分會被裁掉）
                val b = wm.maximumWindowMetrics.bounds
                val side = maxOf(b.width(), b.height())
                width = side; height = side
                fitInsetsTypes = 0
            }
        }
        return runCatching { wm.addView(v, lp); cover = v; true }
            .onFailure { DebugLog.add(this, "全黑遮罩失敗：$it") }
            .getOrDefault(false)
    }

    private fun hideCover() {
        val v = cover ?: return
        cover = null
        runCatching { wm.removeView(v) }
    }

    /** 點兩下：鎖定螢幕；螢幕關閉後由 screenReceiver 移除遮罩並亮屏。 */
    private fun onDoubleTap() {
        if (!sessionActive) return
        DebugLog.add(this, "點兩下 → 鎖定")
        val locked = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        if (!locked) {
            // Android 8 以前沒有鎖定功能：只移除遮罩
            DebugLog.add(this, "無法鎖定，只移除遮罩")
            endSession()
        }
    }

    private fun endSession() {
        if (!sessionActive) return
        sessionActive = false
        handler.removeCallbacksAndMessages(null)
        hideCover()
        updateHandle()
    }

    /** 亮屏：鎖定後由系統直接點亮螢幕，使用者看到的就是鎖屏畫面（需要「點亮螢幕」權限，Android 14 起）。 */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "IdleMate:wake"
            ).acquire(2000)
        }.onFailure { DebugLog.add(this, "亮屏失敗：$it") }
        if (!OemHelper.canTurnScreenOn(this)) DebugLog.add(this, "沒有「點亮螢幕」權限，螢幕可能不會自動亮起")
    }
}
