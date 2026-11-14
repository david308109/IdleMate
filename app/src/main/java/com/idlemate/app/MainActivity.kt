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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
            !ScreenLock.isAdminActive(this) && once("admin") -> ScreenLock.requestAdmin(this)
            !OemHelper.isNotificationListenerEnabled(this) && once("listener") ->
                OemHelper.openNotificationListenerSettings(this)
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
                Surface(Modifier.fillMaxSize()) { Home(refreshTick, ::startSetup) }
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

private fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

@Composable
private fun Home(refreshTick: Int, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var logTick by remember { mutableStateOf(0) }
    var edgeOn by remember(refreshTick) { mutableStateOf(prefs.edgeOn) }
    var edgeLeft by remember(refreshTick) { mutableStateOf(prefs.edgeLeft) }

    val overlayOk = OemHelper.canOverlay(ctx)
    val batteryOk = OemHelper.isIgnoringBatteryOptimizations(ctx)
    val adminOk = ScreenLock.isAdminActive(ctx)
    val listenerOk = OemHelper.isNotificationListenerEnabled(ctx)
    val allReady = overlayOk && batteryOk && adminOk && listenerOk

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
                Text("${if (adminOk) "✅" else "⬜"} 啟用裝置管理員（只用來關閉螢幕）")
                Text("${if (listenerOk) "✅" else "⬜"} 通知存取權（用來讓影片繼續播放）")
                Button(onClick = onSetup) { Text("一鍵完成設定") }
                Text("系統規定這些授權必須由你親自按同意，App 無法代按；做完之後點功能就直接生效。",
                    style = MaterialTheme.typography.bodySmall)
                if (!listenerOk) Text(
                    "若「通知存取權」的開關是灰色、點不動：設定 → 應用程式 → IdleMate → 右上角 ⋮ → 「允許受限制的設定」，再回來開啟。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ── 使用說明 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("怎麼用", style = MaterialTheme.typography.titleMedium)
                Text("1. 打開你要用的 App（例如 YouTube、遊戲）。")
                Text("2. 從螢幕邊緣的細線往內滑，展開選單。")
                Text("3. 點「🔋 螢幕關閉掛機」或「🎧 待機聽劇」，螢幕立刻關閉。")
                Text("4. 鎖屏期間 App 繼續運作；若鎖屏前正在播放，聲音被暫停時會自動叫它繼續。")
                Text(
                    "兩個按鈕都是「點一下就執行」，不用開關。再次開啟螢幕時這次掛機就結束，需要時再點一次。" +
                        "「待機聽劇」才會在鎖屏後恢復播放；「掛機」只負責讓 App 繼續運作。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ── 診斷紀錄 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("診斷紀錄", style = MaterialTheme.typography.titleMedium)
                val log = remember(refreshTick, logTick) { DebugLog.read(ctx) }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(log.ifEmpty { "（還沒有紀錄。測試後回到這裡查看）" }, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { DebugLog.clear(ctx); logTick++ }) { Text("清除紀錄") }
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
                Text("上下拖曳把手可調整位置。", style = MaterialTheme.typography.bodySmall)
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

        // ── 通用保活設定 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("進階：${Build.MANUFACTURER} 保活設定（把手被系統殺掉時才需要）", style = MaterialTheme.typography.titleMedium)
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
}
