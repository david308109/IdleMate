package com.idlemate.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import rikka.shizuku.Shizuku
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 與 Shizuku 的橋接：檢查狀態、要求授權、呼叫 shell 權限的 PowerService。 */
object ShizukuHelper {
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 1001

    // 所有 Shizuku 呼叫排成單一佇列，依序執行（避免「關面板」與「恢復」同時搶資源或順序錯亂）
    private val executor = Executors.newSingleThreadExecutor()
    // 診斷用的呼叫走另一組執行緒，不會卡住關面板／恢復，也不會互相排隊卡死
    private val diagExecutor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

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
            .daemon(false).processNameSuffix("power3").version(18)
        runCatching { Shizuku.bindUserService(args, conn) }.onFailure { binding = false }
        // 8 秒內沒連上就放棄這次，下次呼叫會重新連線（避免永遠卡在「連線中」）
        main.postDelayed({ if (service == null && binding) { binding = false; pending.clear() } }, 8000)
    }

    /** 診斷呼叫：有逾時、不排隊；逾時會說明是「連不上服務」還是「服務沒回應」。 */
    private fun diagCall(ctx: Context, timeoutMs: Long, done: (String) -> Unit, op: (IPowerService) -> String) {
        val finished = AtomicBoolean(false)
        main.postDelayed({
            if (!finished.getAndSet(true)) {
                val sec = timeoutMs / 1000
                done(
                    if (service == null) "連不上 Shizuku 服務（等了 $sec 秒）。小米請確認：開發人員選項的「USB 偵錯（安全性設定）」已開啟，然後在 Shizuku 裡停止再重新啟動服務，再試一次。"
                    else "已連上服務，但這個動作 $sec 秒內沒有回應。"
                )
            }
        }, timeoutMs)
        withService(ctx.applicationContext) { svc ->
            diagExecutor.execute {
                val r = runCatching { op(svc) }.getOrElse { "呼叫失敗：$it" }
                if (!finished.getAndSet(true)) done(r)
            }
        }
    }

    fun grantSecureSettings(ctx: Context, done: (String) -> Unit) {
        val pkg = ctx.packageName
        withService(ctx.applicationContext) { svc ->
            executor.execute { done(runCatching { svc.grantSecureSettings(pkg) }.getOrElse { "呼叫失敗：$it" }) }
        }
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

    fun startLogCapture(ctx: Context, done: (String) -> Unit) =
        diagCall(ctx, 15000L, done) { it.startLogCapture() }

    fun startFullCapture(ctx: Context, done: (String) -> Unit) =
        diagCall(ctx, 15000L, done) { it.startFullCapture() }

    fun stopFullCapture(ctx: Context, done: (String) -> Unit) =
        diagCall(ctx, 15000L, done) { it.stopFullCapture() }

    fun probeTouch(ctx: Context, done: (String) -> Unit) =
        diagCall(ctx, 60000L, done) { it.probeTouch() }

    fun stopLogCapture(ctx: Context, done: (String) -> Unit) =
        diagCall(ctx, 15000L, done) { it.stopLogCapture() }

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
