package com.idlemate.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** 各家 Android 廠商的省電 / 自啟動設定捷徑（找不到時退回系統通用頁面）。 */
object OemHelper {
    private val brand: String get() = android.os.Build.MANUFACTURER.lowercase()

    val isXiaomi get() = brand in setOf("xiaomi", "redmi", "poco")

    private fun cn(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** 依廠商列出可能的「自啟動 / 背景管理」頁面，依序嘗試。 */
    private fun vendorIntents(): List<Intent> = when {
        isXiaomi -> listOf(cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        brand in setOf("huawei", "honor") -> listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        )
        brand in setOf("oppo", "realme") -> listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        brand in setOf("vivo", "iqoo") -> listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        )
        brand == "samsung" -> listOf(cn("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
        brand == "oneplus" -> listOf(cn("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
        else -> emptyList()
    }

    val hasVendorPage: Boolean get() = vendorIntents().isNotEmpty()

    fun vendorHint(): String = when {
        isXiaomi -> "小米：開啟「自啟動」、省電策略選「無限制」，並在最近任務把 App 下拉上鎖。"
        brand in setOf("huawei", "honor") -> "華為/榮耀：到「應用啟動管理」把 App 改為手動管理，三項（自啟動、關聯啟動、後台活動）全開。"
        brand in setOf("oppo", "realme") -> "OPPO/realme：允許自啟動與後台運行，並在最近任務鎖定 App。"
        brand in setOf("vivo", "iqoo") -> "vivo/iQOO：允許後台高耗電與自啟動。"
        brand == "samsung" -> "三星：電池 → 背景使用限制，把 App 從「休眠應用程式」移除。"
        else -> "請在系統設定中允許 App 背景運行、不受電池最佳化限制。"
    }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return flat?.contains(ctx.packageName) == true
    }

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** 「使用狀況存取」：用來得知目前前景是哪個 App。 */
    fun usageAccessGranted(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    )

    /**
     * 「點亮螢幕」：Android 14 起，App 要自己把螢幕點亮需要這個特殊權限
     * （設定 → 應用程式 → 特殊應用程式存取權 → 點亮螢幕）。更早的版本不需要。
     */
    fun canTurnScreenOn(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) return true
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow("android:turn_screen_on", android.os.Process.myUid(), ctx.packageName)
        }.getOrDefault(android.app.AppOpsManager.MODE_ALLOWED)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * 不是從商店安裝的 App，Android 13 起會擋住協助工具（「受限制的設定」）。
     * - MODE_ERRORED：被擋住，且使用者還沒點過（「⋮」裡還沒有「允許受限制的設定」）
     * - MODE_IGNORED：被擋住，但使用者已在協助工具點過一次，「⋮」選項已出現
     * - MODE_ALLOWED / 預設：沒被擋
     */
    private fun restrictedMode(ctx: Context): Int {
        if (android.os.Build.VERSION.SDK_INT < 33) return android.app.AppOpsManager.MODE_ALLOWED
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return runCatching {
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName)
        }.getOrDefault(android.app.AppOpsManager.MODE_ALLOWED)
    }

    fun isRestricted(ctx: Context): Boolean = restrictedMode(ctx).let {
        it == android.app.AppOpsManager.MODE_ERRORED || it == android.app.AppOpsManager.MODE_IGNORED
    }

    /** 被擋住，而且「⋮ → 允許受限制的設定」還沒出現（要先到協助工具點一次 IdleMate） */
    fun needsReveal(ctx: Context): Boolean = restrictedMode(ctx) == android.app.AppOpsManager.MODE_ERRORED

    fun openAppInfo(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    fun openTurnScreenOnSettings(ctx: Context) {
        // 注意：帶 package 的版本在 Pixel 上會讓「設定」閃退，所以只開清單頁
        val tries = listOf(
            Intent("android.settings.TURN_SCREEN_ON_SETTINGS")
                // 讓清單自動捲到 IdleMate 並標亮（不支援的手機會忽略）
                .putExtra(":settings:fragment_args_key", ctx.packageName)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", ctx.packageName)
                })
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    fun openOverlaySettings(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    )

    fun openNotificationListenerSettings(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun requestIgnoreBatteryOptimizations(ctx: Context) = start(
        ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    )

    fun openVendorSettings(ctx: Context) {
        for (i in vendorIntents()) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: Exception) { }
        }
        start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 小米專用：指定 App 的省電策略頁。 */
    fun openXiaomiBatteryPolicy(ctx: Context, pkg: String, label: String) = start(
        ctx,
        cn("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            .putExtra("package_name", pkg).putExtra("package_label", label)
    )

    private fun start(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
