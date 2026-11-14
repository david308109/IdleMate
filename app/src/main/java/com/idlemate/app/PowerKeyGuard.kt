package com.idlemate.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * 黑屏期間暫時把「電源鍵短按」改成不做任何事：
 * 按電源鍵時系統不會睡眠、不會上鎖，IdleMate 讀到按鍵後直接恢復面板，聲音不中斷。
 * 結束時（或 App 重啟、手機重開機後）一定還原成原本的設定。
 */
object PowerKeyGuard {
    private const val KEY = "power_button_short_press"
    private const val SHORT_PRESS_NOTHING = 0

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 關掉電源鍵的睡眠功能；回傳是否成功。 */
    fun block(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        val cr = ctx.contentResolver
        val p = Prefs(ctx)
        if (!p.pbspSaved) {
            p.pbspOrig = Settings.Global.getString(cr, KEY)
            p.pbspSaved = true
        }
        return runCatching { Settings.Global.putInt(cr, KEY, SHORT_PRESS_NOTHING) }.getOrDefault(false)
    }

    /** 還原電源鍵設定（沒改過就什麼都不做）。 */
    fun restore(ctx: Context): String? {
        val p = Prefs(ctx)
        if (!p.pbspSaved) return null
        if (!canWrite(ctx)) return "無法還原電源鍵設定：沒有權限"
        val cr = ctx.contentResolver
        val orig = p.pbspOrig
        val ok = runCatching {
            if (orig == null) {
                // 原本沒有這個設定 → 刪掉，讓系統回到預設行為
                val deleted = cr.delete(Uri.withAppendedPath(Settings.Global.CONTENT_URI, KEY), null, null)
                if (deleted <= 0) Settings.Global.putString(cr, KEY, null)
            } else {
                Settings.Global.putString(cr, KEY, orig)
            }
            true
        }.getOrDefault(false)
        if (ok) { p.pbspSaved = false; p.pbspOrig = null }
        return if (ok) "已還原電源鍵設定" else "還原電源鍵設定失敗"
    }
}

/** 手機重開機後，若上次黑屏時電源鍵設定沒還原，就在這裡還原。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PowerKeyGuard.restore(context)?.let { DebugLog.add(context, "開機：$it") }
        }
    }
}
