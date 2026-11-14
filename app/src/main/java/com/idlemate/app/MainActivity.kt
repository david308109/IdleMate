package com.idlemate.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private data class AppEntry(val label: String, val pkg: String)

class MainActivity : ComponentActivity() {
    // 回到畫面時重新讀取授權與開關狀態
    private var refreshTick by mutableStateOf(0)

    // 一鍵設定：依序處理缺少的授權，每一步只嘗試一次，避免使用者拒絕時無限循環
    private var setupRunning = false
    private val attempted = mutableSetOf<String>()
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { continueSetup() }

    private fun startSetup() {
        attempted.clear(); setupRunning = true; continueSetup()
    }

    private fun continueSetup() {
        if (!setupRunning) return
        fun once(key: String) = attempted.add(key)
        when {
            !OemHelper.canOverlay(this) && once("overlay") -> OemHelper.openOverlaySettings(this)
            !OemHelper.isIgnoringBatteryOptimizations(this) && once("battery") ->
                OemHelper.requestIgnoreBatteryOptimizations(this)
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && once("notif") ->
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> {
                setupRunning = false
                Prefs(this).setupDone = true
                if (OemHelper.canOverlay(this)) {
                    startEdge(this)
                    Toast.makeText(this, "設定完成，從螢幕邊緣滑出選單即可使用", Toast.LENGTH_LONG).show()
                }
            }
        }
        refreshTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Home(refreshTick, { refreshTick++ }, ::startSetup) }
            }
        }
        // 第一次打開自動進入一鍵設定
        if (!Prefs(this).setupDone) startSetup()
    }

    override fun onResume() {
        super.onResume()
        if (setupRunning) continueSetup()
        // 打開 App 時，若已授權且啟用，就讓邊緣把手出現
        else if (Prefs(this).edgeOn && OemHelper.canOverlay(this)) startEdge(this)
        refreshTick++
    }
}

private fun startEdge(ctx: Context) =
    ContextCompat.startForegroundService(ctx, Intent(ctx, EdgeHandleService::class.java))

private fun Context.launchableApps(): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(intent, 0)
        .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
        .filter { it.pkg != packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

@Composable
private fun Home(refreshTick: Int, refresh: () -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    // 以 refreshTick 為 key，邊緣選單在別處改了開關，回到這裡會同步
    var keepAlive by remember(refreshTick) { mutableStateOf(prefs.keepAliveOn) }
    var mediaResume by remember(refreshTick) { mutableStateOf(prefs.mediaResumeOn) }
    var edgeOn by remember(refreshTick) { mutableStateOf(prefs.edgeOn) }
    var edgeLeft by remember(refreshTick) { mutableStateOf(prefs.edgeLeft) }
    var target by remember(refreshTick) { mutableStateOf(prefs.targetPackage) }
    var showPicker by remember { mutableStateOf(false) }

    val overlayOk = OemHelper.canOverlay(ctx)
    val batteryOk = OemHelper.isIgnoringBatteryOptimizations(ctx)
    val allReady = overlayOk && batteryOk

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("IdleMate", style = MaterialTheme.typography.headlineMedium)

        // ── 一鍵設定（只需做一次） ──
        if (!allReady) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("首次設定（只需一次）", style = MaterialTheme.typography.titleMedium)
                Text("${if (overlayOk) "✅" else "⬜"} 顯示邊緣把手（懸浮視窗）")
                Text("${if (batteryOk) "✅" else "⬜"} 允許背景運行")
                Button(onClick = onSetup) { Text("一鍵完成設定") }
                Text("系統規定這些授權必須由你親自按同意，App 無法代按；做完之後點功能就直接生效。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 邊緣把手 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("邊緣把手", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = edgeOn, onCheckedChange = {
                        edgeOn = it; prefs.edgeOn = it
                        if (!it) ctx.stopService(Intent(ctx, EdgeHandleService::class.java))
                        else if (overlayOk) startEdge(ctx) else OemHelper.openOverlaySettings(ctx)
                    })
                }
                Text(
                    "螢幕邊緣會出現一條細線，往內滑（或點一下）即可展開選單，直接開關兩個功能；上下拖曳可調整位置。",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!overlayOk) {
                    Text("⚠️ 需要「顯示在其他應用程式上層」權限")
                    Button(onClick = { OemHelper.openOverlaySettings(ctx) }) { Text("前往授權") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("放在左邊", modifier = Modifier.weight(1f))
                    Switch(checked = edgeLeft, onCheckedChange = {
                        edgeLeft = it; prefs.edgeLeft = it
                        if (edgeOn && overlayOk) {
                            ctx.stopService(Intent(ctx, EdgeHandleService::class.java)); startEdge(ctx)
                        }
                    })
                }
            }
        }

        // ── 功能一：螢幕關閉掛機 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("螢幕關閉掛機", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = keepAlive, onCheckedChange = { toast(Features.toggleKeepAlive(ctx)); refresh() })
                }
                Text("讓指定 App 在螢幕關閉後繼續運行（前景服務 + CPU/Wi-Fi 鎖）。", style = MaterialTheme.typography.bodySmall)
                Text("目標 App：${target?.let { ctx.appLabel(it) } ?: "尚未選擇"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showPicker = true }) { Text("選擇 App") }
                    target?.let { pkg ->
                        OutlinedButton(onClick = {
                            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
                        }) { Text("開啟") }
                        if (OemHelper.isXiaomi) OutlinedButton(onClick = {
                            OemHelper.openXiaomiBatteryPolicy(ctx, pkg, ctx.appLabel(pkg))
                        }) { Text("其省電策略") }
                    }
                }
            }
        }

        // ── 功能二：待機聽劇 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("待機聽劇", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = mediaResume, onCheckedChange = { toast(Features.toggleMedia(ctx)); refresh() })
                }
                Text("影音 App 在螢幕關閉而暫停時，自動送出「播放」，讓聲音繼續。", style = MaterialTheme.typography.bodySmall)
                Text("用法：先在影音 App 開始播放，再按電源鍵息屏。", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 通用保活設定 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("進階：${Build.MANUFACTURER} 保活設定（掛機被殺掉時才需要）", style = MaterialTheme.typography.titleMedium)
                if (OemHelper.hasVendorPage) OutlinedButton(onClick = { OemHelper.openVendorSettings(ctx) }) {
                    Text("開啟廠商自啟動 / 背景管理")
                }
                if (OemHelper.isXiaomi) OutlinedButton(onClick = {
                    OemHelper.openXiaomiBatteryPolicy(ctx, ctx.packageName, ctx.appLabel(ctx.packageName))
                }) { Text("本 App 省電策略 → 無限制") }
                Text(OemHelper.vendorHint(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showPicker) {
        val apps = remember { ctx.launchableApps() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
            title = { Text("選擇要掛機的 App") },
            text = {
                LazyColumn {
                    items(apps) { app ->
                        Text(
                            app.label,
                            Modifier.fillMaxWidth().clickable {
                                target = app.pkg; prefs.targetPackage = app.pkg; showPicker = false
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }
}
