[English](README.md) | **繁體中文**

# IdleMate

讓螢幕「看起來關掉」，App 和影片照常在跑的 Android 小工具。

**🌙 熄屏運行**：一鍵讓畫面全黑，App 照常運行。遊戲可以繼續掛機，YouTube 可以繼續播聲音。

不需要 root，也不需要 Shizuku。

## 下載

到 [Releases](https://github.com/david308109/IdleMate/releases/latest) 下載最新的 `IdleMate-x.y.z.apk`，在手機上點開安裝。

### 安裝時會看到的提示

1. **Play Protect「建議掃描應用程式」**：IdleMate 不是從 Play 商店安裝的，所以會出現這個提示。可以按掃描，或直接安裝。
2. **協助工具開關是灰色（受限制的設定）**：Android 對所有從檔案安裝的 App 都會這樣。打開 IdleMate 後照著畫面引導做就好：
   1. 到協助工具，點一下灰色的 IdleMate，看到提示後按「確定」。
   2. 到應用程式資訊，點右上角「⋮」→「允許受限制的設定」。
   3. 回到協助工具，開啟 IdleMate。

## 怎麼用

1. 打開指定的 App（預設：所有遊戲與 YouTube），螢幕邊緣會出現一條細線（把手）。
2. 從把手**往內滑**，展開選單。
3. 點「🌙 熄屏運行」，畫面立刻全黑，觸控會被擋住，不怕誤觸。
4. 要回來時，**點兩下螢幕**或**按電源鍵**，會直接看到鎖屏畫面，解鎖即可。

### 把手

- 一條短短的膠囊形細線。直屏時在右側中間，橫屏時預設在右上角。
- **長按約 0.5 秒**，線會變粗，之後可以上下拖動調整位置；拖過螢幕中間可以換到另一邊。直屏、橫屏各自記住位置。
- 橫屏時，滑出的選單會緊貼前鏡頭孔，左右兩側距離相同。距離依每支手機的鏡頭位置自動計算（Android 12 以上；更舊的版本使用系統的鏡頭安全範圍）。
- 點一下不會展開，避免誤觸。把手的區域不會觸發系統的返回手勢。
- 只在指定的 App 出現。離開 App、鎖屏或全黑時會自動隱藏。

## 權限

| 權限 | 用途 |
|---|---|
| 協助工具 | 顯示把手和全黑畫面（蓋在狀態列與導覽列之上）、偵測目前的 App、鎖定螢幕 |
| 點亮螢幕（Android 14+） | 按電源鍵鎖定後，自動亮屏顯示鎖屏畫面 |

IdleMate **不讀取任何畫面內容**（`canRetrieveWindowContent=false`），也**沒有網路權限**。

第一次打開時會跳出說明視窗，並帶你到對應的設定頁。

從檔案安裝的話，IdleMate 會偵測安裝來源，自動加入解除「受限制的設定」的步驟（見[安裝時會看到的提示](#安裝時會看到的提示)）。從 Play 商店或 adb 安裝的不受影響。

## 運作原理

- **全黑**：用協助工具的 `TYPE_ACCESSIBILITY_OVERLAY` 蓋一層全螢幕黑色畫面，加上 `FLAG_KEEP_SCREEN_ON`。系統其實沒有睡眠，所以前景 App 和影片都照常運行。亮度不會被調整。黑幕是以螢幕長邊為邊長的正方形，所以旋轉後也能蓋滿。
- **點兩下**：遮罩用 `GestureDetector` 偵測，觸發 `GLOBAL_ACTION_LOCK_SCREEN`。
- **電源鍵**：收到 `SCREEN_OFF` 後移除遮罩，再用 `ACQUIRE_CAUSES_WAKEUP` 的 wake lock 點亮螢幕，顯示鎖屏。
- **偵測前景 App**：監聽 `TYPE_WINDOW_STATE_CHANGED`，只採用真正的 Activity，以及桌面 App 的事件。

### 已知限制

- 鎖屏後影片會停，這是正常的。免費版 YouTube 在畫面暫停後會自己停止播放。
- 全黑期間螢幕面板其實是亮著的（只是黑色），省電效果比真正關螢幕差。OLED 螢幕的黑色像素幾乎不耗電。

### 為什麼只有一個模式

小米把遊戲的「熄屏掛機」和影片的「待機聽劇」做成兩個功能，但從日誌看，兩者用的是同一套系統機制（`Go to sleep and hold on - <App>`）：系統真的進入睡眠，但不讓指定的 App 被暫停。IdleMate 對這兩種情況也是同一套做法，所以只提供一個模式。小米的做法需要系統權限，所以 IdleMate 改用全黑遮罩。

## 專案結構

```
app/src/main/java/com/idlemate/app/
├── IdleAccessibilityService.kt  # 核心：把手、選單、全黑遮罩、鎖屏、亮屏、前景偵測
├── MainActivity.kt              # 設定畫面與首次設定引導（Compose）
├── Prefs.kt                     # 偏好設定（把手位置、適用的 App）
├── OemHelper.kt                 # 權限檢查、各廠牌設定頁、安裝來源判斷
└── DebugLog.kt                  # App 內的診斷紀錄
```

- Kotlin + Jetpack Compose
- minSdk 26，targetSdk 35

## 建置

推到 `main` 後，GitHub Actions（[.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)）會自動建置**正式簽章**的 APK，產物名稱是 `IdleMate-release-apk`。

正式簽章的金鑰只存在 GitHub Secrets，**不在 repo 裡**：

| Secret | 內容 |
|---|---|
| `IDLEMATE_KEYSTORE_B64` | PKCS12 金鑰檔的 base64 |
| `IDLEMATE_KEYSTORE_PASSWORD` | 金鑰密碼 |
| `IDLEMATE_KEY_ALIAS` | 金鑰別名 |

> 金鑰遺失就無法再發布可以覆蓋安裝的更新，請務必另外備份。換簽章時，使用者必須先解除安裝舊版。

本機建置 debug 版用的是 repo 裡固定的 `idlemate-debug.keystore`：

```sh
gradle assembleDebug
```

### 發布新版本

推一個 `v` 開頭的標籤，就會自動建置、簽章並發布到 [Releases](https://github.com/david308109/IdleMate/releases)：

```sh
git tag v0.2.0
git push origin v0.2.0
```

- 版本名稱取自標籤（`v0.2.0` → `0.2.0`），版本號取自建置次數，所以每一版都能覆蓋安裝。
- 更新說明會自動列出與上一個標籤之間的 commit。
- 一般推到 `main` 只會建置，不會發布。

### 安裝到手機

```sh
gh run download <run-id> -n IdleMate-release-apk -D IdleMate-apk
adb install -r IdleMate-apk/app-release.apk
```

用 adb 更新後，協助工具不會自動重新連上，要在設定裡把 IdleMate 關掉再打開一次。

## 測試

見 [TESTING.zh-TW.md](TESTING.zh-TW.md)。
