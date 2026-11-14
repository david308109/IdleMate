package com.idlemate.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** 與 Shizuku 的橋接：檢查狀態、要求授權、呼叫 shell 權限的 PowerService。 */
object ShizukuHelper {
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 1001

    // 所有 Shizuku 呼叫排成單一佇列，依序執行（避免「關面板」與「恢復」同時搶資源或順序錯亂）
    private val executor = Executors.newSingleThreadExecutor()

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
            .daemon(false).processNameSuffix("power3").version(12)
        runCatching { Shizuku.bindUserService(args, conn) }.onFailure { binding = false }
    }

    /** 讓手機進入鎖定狀態（顯示鎖屏），但系統不睡眠。 */
    fun lockKeyguard(ctx: Context, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.lockKeyguard() }.getOrElse { "呼叫失敗：$it" }) }
        }
    }

    /** 送出「喚醒」按鍵（以 shell 身分），讓睡眠中的手機亮屏。 */
    fun wakeUp(ctx: Context, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.wakeUp() }.getOrElse { "呼叫失敗：$it" }) }
        }
    }

    fun startTouchWatch(ctx: Context, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.startTouchWatch() }.getOrElse { "呼叫失敗：$it" }) }
        }
    }

    fun stopTouchWatch(done: ((String) -> Unit)? = null) {
        val s = service ?: return
        executor.execute {
            val info = runCatching { s.touchInfo() }.getOrDefault("")
            runCatching { s.stopTouchWatch() }
            done?.invoke(info)
        }
    }

    /** 最近一次手指按下的時間（毫秒）；失敗回 0 */
    fun touchStamp(done: (Long) -> Unit) {
        val s = service ?: return
        executor.execute { done(runCatching { s.touchStamp() }.getOrDefault(0L)) }
    }

    fun startLogCapture(ctx: Context, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.startLogCapture() }.getOrElse { "呼叫失敗：$it" }) }
        }
    }

    fun stopLogCapture(ctx: Context, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.stopLogCapture() }.getOrElse { "呼叫失敗：$it" }) }
        }
    }

    /** 指定面板電源模式（0 關、1 低功耗顯示、2 正常）。 */
    fun setDisplayMode(ctx: Context, mode: Int, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.setDisplayMode(mode) }.getOrElse { "呼叫失敗：$it" }) }
        }
    }

    /** 開 / 關螢幕面板，結果說明透過 done 回傳（在背景執行緒）。 */
    fun setDisplayPower(ctx: Context, on: Boolean, done: (String) -> Unit) {
        withService(ctx.applicationContext) { svc ->
            executor.execute {
                done(runCatching { svc.setDisplayPower(on) }.getOrElse { "呼叫失敗：$it" })
            }
        }
    }
}
