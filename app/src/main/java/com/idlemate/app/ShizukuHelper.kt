package com.idlemate.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import rikka.shizuku.Shizuku

/** 與 Shizuku 的橋接：檢查狀態、要求授權、呼叫 shell 權限的 PowerService。 */
object ShizukuHelper {
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 1001

    private var service: IPowerService? = null
    private var binding = false
    private val pending = mutableListOf<(IPowerService) -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IPowerService.Stub.asInterface(binder)
            service = s
            binding = false
            val todo = pending.toList(); pending.clear()
            todo.forEach { it(s) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null; binding = false
        }
    }

    fun installed(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)

    /** Shizuku 服務是否已啟動（用無線偵錯啟動後才會是 true） */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = running() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun openShizukuApp(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun withService(ctx: Context, block: (IPowerService) -> Unit) {
        service?.let { block(it); return }
        pending.add(block)
        if (binding) return
        binding = true
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, PowerService::class.java.name))
            .daemon(false).processNameSuffix("power").version(2)
        runCatching { Shizuku.bindUserService(args, conn) }.onFailure { binding = false }
    }

    /** 送出「喚醒」按鍵（以 shell 身分），讓睡眠中的手機亮屏。 */
    fun wakeUp(ctx: Context, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            Thread { done(runCatching { svc.wakeUp() }.getOrElse { "呼叫失敗：$it" }) }.start()
        }
    }

    /** 開 / 關螢幕面板，結果說明透過 done 回傳（在背景執行緒）。 */
    fun setDisplayPower(ctx: Context, on: Boolean, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            Thread {
                done(runCatching { svc.setDisplayPower(on) }.getOrElse { "呼叫失敗：$it" })
            }.start()
        }
    }
}
