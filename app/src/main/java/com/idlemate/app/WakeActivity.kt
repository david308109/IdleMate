package com.idlemate.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * 透明的小畫面，只用來把螢幕點亮（跟鬧鐘 App 的做法相同，不需要額外權限）。
 * 螢幕亮起後立刻關閉，使用者看到的就是鎖屏畫面。
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        DebugLog.add(this, "亮屏畫面：已啟動")
        Handler(Looper.getMainLooper()).postDelayed({ finishNow() }, 600)
    }

    private fun finishNow() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
