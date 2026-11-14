package com.idlemate.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 透過裝置管理員的「鎖定螢幕」權限，真的把螢幕關掉。 */
object ScreenLock {
    private fun admin(ctx: Context) = ComponentName(ctx, IdleDeviceAdmin::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    fun requestAdmin(ctx: Context) {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "僅用來在點擊功能後關閉螢幕（鎖定螢幕），不會存取其他資料。")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    fun lockNow(ctx: Context) {
        runCatching { dpm(ctx).lockNow() }
    }
}
