package com.idlemate.app

import android.content.Context
import androidx.core.content.ContextCompat

/** 兩個功能的開關邏輯，主畫面與邊緣選單共用。回傳要顯示給使用者的訊息。 */
object Features {
    fun toggleKeepAlive(ctx: Context): String {
        val p = Prefs(ctx)
        if (p.keepAliveOn) {
            p.keepAliveOn = false
            ctx.stopService(android.content.Intent(ctx, KeepAliveService::class.java))
            return "掛機已關閉"
        }
        val target = p.targetPackage ?: return "請先在 IdleMate 內選擇要掛機的 App"
        p.keepAliveOn = true
        ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, KeepAliveService::class.java))
        ctx.packageManager.getLaunchIntentForPackage(target)?.let {
            runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return "掛機已開啟，可按電源鍵息屏"
    }

    fun toggleMedia(ctx: Context): String {
        val p = Prefs(ctx)
        if (p.mediaResumeOn) {
            p.mediaResumeOn = false
            ctx.stopService(android.content.Intent(ctx, MediaResumeService::class.java))
            return "待機聽劇已關閉"
        }
        p.mediaResumeOn = true
        ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, MediaResumeService::class.java))
        return "待機聽劇已開啟，先播放影片再息屏"
    }
}
