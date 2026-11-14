package com.idlemate.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 協助工具服務，只做三件事：
 * 1. 記錄目前前景是哪個 App（決定邊緣把手要不要出現）。
 * 2. 畫全黑遮罩：層級在狀態列與導覽列之上，整個畫面全黑，並擋住所有點擊。
 * 3. 鎖定螢幕。
 * 不讀取任何畫面內容。
 */
class IdleAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: IdleAccessibilityService? = null
            private set

        /** 系統設定裡是否已開啟本服務（服務還沒連上時也能判斷）。 */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, IdleAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun openSettings(ctx: Context) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** 最近一次出現在前景的 App（只算真正的 Activity，忽略系統介面與鍵盤等）。 */
    @Volatile var foregroundPkg: String? = null
        private set

    private var cover: View? = null
    private val activityCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (isActivity(pkg, cls)) foregroundPkg = pkg
    }

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut("$pkg/$cls") {
        runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true }.getOrDefault(false)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hideCover(); instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideCover(); instance = null
        super.onDestroy()
    }

    val coverShowing: Boolean get() = cover != null

    /** 顯示全黑遮罩；點兩下時呼叫 onDoubleTap。回傳是否成功。 */
    fun showCover(onDoubleTap: () -> Unit): Boolean {
        if (cover != null) return true
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
                // 用實體螢幕的完整大小，連狀態列與導覽列一起蓋住
                val b = wm.maximumWindowMetrics.bounds
                width = b.width(); height = b.height()
                fitInsetsTypes = 0
            }
        }
        return runCatching { wm.addView(v, lp); cover = v; true }
            .onFailure { DebugLog.add(this, "全黑遮罩失敗：$it") }
            .getOrDefault(false)
    }

    fun hideCover() {
        val v = cover ?: return
        cover = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    /** 鎖定螢幕（跟按電源鍵一樣：螢幕會關閉並上鎖）。 */
    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
}
