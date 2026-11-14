package com.idlemate.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** 兩個功能的開關邏輯，主畫面與邊緣選單共用。回傳要顯示給使用者的訊息。 */
object Features {
    private fun admin(ctx: Context) = ComponentName(ctx, IdleDeviceAdmin::class.java)

    fun isAdminActive(ctx: Context): Boolean =
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin(ctx))

    /** 開啟系統的「啟用裝置管理員」確認頁。 */
    fun requestAdmin(ctx: Context) {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "僅用來在點擊功能後關閉螢幕（鎖定螢幕），不會存取其他資料。")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** 等服務就緒後再鎖屏，確保息屏事件被接收到。 */
    private fun lockScreenSoon(ctx: Context): String {
        if (!isAdminActive(ctx)) {
            requestAdmin(ctx)
            return "請先啟用「裝置管理員」，才能自動關閉螢幕"
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
            }
        }, 700)
        return ""
    }

    fun toggleKeepAlive(ctx: Context): String {
        val p = Prefs(ctx)
        if (p.keepAliveOn) {
            p.keepAliveOn = false
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            return "掛機已關閉"
        }
        p.keepAliveOn = true
        ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        return lockScreenSoon(ctx).ifEmpty { "掛機已開啟，螢幕即將關閉" }
    }

    fun toggleMedia(ctx: Context): String {
        val p = Prefs(ctx)
        if (p.mediaResumeOn) {
            p.mediaResumeOn = false
            ctx.stopService(Intent(ctx, MediaResumeService::class.java))
            return "待機聽劇已關閉"
        }
        p.mediaResumeOn = true
        ContextCompat.startForegroundService(ctx, Intent(ctx, MediaResumeService::class.java))
        return lockScreenSoon(ctx).ifEmpty { "待機聽劇已開啟，螢幕即將關閉" }
    }
}
