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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
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
            !OemHelper.usageAccessGranted(this) && once("usage") -> OemHelper.openUsageAccessSettings(this)
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
    val shizukuInstalled = ShizukuHelper.installed(ctx)
    val shizukuRunning = ShizukuHelper.running()
    val shizukuGranted = ShizukuHelper.granted()
    val usageOk = OemHelper.usageAccessGranted(ctx)
    val allReady = overlayOk && batteryOk && usageOk
    var showKeepPicker by remember { mutableStateOf(false) }
    var showMediaPicker by remember { mutableStateOf(false) }
    var pickerTick by remember { mutableStateOf(0) }

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
                Text("${if (usageOk) "✅" else "⬜"} 使用狀況存取（用來知道你正在用哪個 App，只在指定 App 顯示把手）")
                Button(onClick = onSetup) { Text("一鍵完成設定") }
                Text("系統規定這些授權必須由你親自按同意，App 無法代按。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── Shizuku（關閉螢幕面板所需） ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku（關閉螢幕面板所需）", style = MaterialTheme.typography.titleMedium)
                Text("${if (shizukuInstalled) "✅" else "⬜"} 已安裝 Shizuku")
                Text("${if (shizukuRunning) "✅" else "⬜"} Shizuku 已啟動（用「無線偵錯」啟動，重新開機後要再啟動一次）")
                Text("${if (shizukuGranted) "✅" else "⬜"} 已授權 IdleMate")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ShizukuHelper.openShizukuApp(ctx) }) {
                        Text(if (shizukuInstalled) "開啟 Shizuku" else "下載 Shizuku")
                    }
                    if (shizukuRunning && !shizukuGranted) Button(onClick = { ShizukuHelper.requestPermission() }) {
                        Text("授權 IdleMate")
                    }
                }
            }
        }

        // ── 使用說明 ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("怎麼用", style = MaterialTheme.typography.titleMedium)
                Text("1. 打開你指定的 App（預設：遊戲用掛機、YouTube 用待機聽劇），螢幕邊緣會出現一條細線。")
                Text("2. 從細線往內滑，展開選單，只會列出適用於這個 App 的功能。")
                Text("3. 點「🔋 螢幕關閉掛機」或「🎧 待機聽劇」，螢幕面板立刻關閉。")
                Text("4. 要回來時，按一次電源鍵，就是正常的鎖屏畫面，解鎖即可。")
                Text(
                    "兩個按鈕都是「點一下就執行」，不用開關。螢幕面板關閉但系統並沒有睡眠，所以 App 與影片照常運行；" +
                        "期間觸控會被攔下，不會誤觸。按電源鍵後影片會停，這是正常的。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ── 各功能出現在哪些 App ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔋 螢幕關閉掛機", style = MaterialTheme.typography.titleMedium)
                val keep = remember(pickerTick) { prefs.keepApps }
                Text(
                    if (keep == null) "出現在：所有遊戲（預設）" else "出現在：已選的 ${keep.size} 個 App",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { showKeepPicker = true }) { Text("選擇 App") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎧 待機聽劇", style = MaterialTheme.typography.titleMedium)
                val media = remember(pickerTick) { prefs.mediaApps }
                Text(
                    if (media == null) "出現在：YouTube（預設）" else "出現在：已選的 ${media.size} 個 App",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { showMediaPicker = true }) { Text("選擇 App") }
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

    if (showKeepPicker) {
        AppPickerDialog(
            title = "🔋 螢幕關閉掛機：在哪些 App 出現",
            isChecked = { prefs.appliesKeep(it) },
            onSave = { prefs.keepApps = it; pickerTick++; showKeepPicker = false },
            onReset = { prefs.keepApps = null; pickerTick++; showKeepPicker = false },
            onDismiss = { showKeepPicker = false }
        )
    }
    if (showMediaPicker) {
        AppPickerDialog(
            title = "🎧 待機聽劇：在哪些 App 出現",
            isChecked = { prefs.appliesMedia(it) },
            onSave = { prefs.mediaApps = it; pickerTick++; showMediaPicker = false },
            onReset = { prefs.mediaApps = null; pickerTick++; showMediaPicker = false },
            onDismiss = { showMediaPicker = false }
        )
    }
}

private data class AppEntry(val label: String, val pkg: String, val initiallyChecked: Boolean)

@Composable
private fun AppPickerDialog(
    title: String,
    isChecked: (String) -> Boolean,
    onSave: (Set<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(ctx.packageManager).toString(), it.activityInfo.packageName, isChecked(it.activityInfo.packageName)) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            // 已勾選的排前面，其餘依名稱排序
            .sortedWith(compareBy({ !it.initiallyChecked }, { it.label.lowercase() }))
    }
    val checked = remember { mutableStateListOf<String>().apply { addAll(apps.filter { it.initiallyChecked }.map { it.pkg }) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(apps) { app ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (app.pkg in checked) checked.remove(app.pkg) else checked.add(app.pkg)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = app.pkg in checked, onCheckedChange = null)
                        Text(app.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(checked.toSet()) }) { Text("儲存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("還原預設") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
