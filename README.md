**English** | [繁體中文](README.zh-TW.md)

# IdleMate

An Android utility that makes the screen *look* off while your app or video keeps running.

**🌙 Screen-off mode**: one tap turns the screen black while the app keeps running, so a game keeps idling (AFK farming) and YouTube keeps playing audio.

No root, no Shizuku.

## Download

Get the latest `IdleMate-x.y.z.apk` from [Releases](https://github.com/david308109/IdleMate/releases/latest) and open it on your phone to install.

### Prompts you will see when installing

1. **Play Protect "Scan app"**: IdleMate is not installed from the Play Store, so Android shows this prompt. You can scan it or install directly.
2. **Accessibility toggle is greyed out ("Restricted setting")**: Android does this for every app installed from a file. Open IdleMate and follow the on-screen guide:
   1. In Accessibility, tap the greyed-out IdleMate once and tap **OK** on the prompt.
   2. In App info, tap **⋮** in the top-right corner → **Allow restricted settings**.
   3. Go back to Accessibility and turn IdleMate on.

## How to use

1. Open one of your chosen apps (default: all games and YouTube). A thin line (the handle) appears at the edge of the screen.
2. **Swipe inward** from the handle to open the menu.
3. Tap **🌙 Screen-off mode**. The screen turns black right away, and touches are blocked so nothing gets tapped by accident.
4. To come back, **double-tap the screen** or **press the power button**. You go straight to the lock screen; just unlock.

### The handle

- A short pill-shaped line. Portrait: right edge, vertically centred. Landscape: top-right by default.
- **Long-press for about 0.5 s** until the line gets thicker, then drag it up or down. Pull it toward the middle and the line follows your finger; pull far enough and it flies to the other edge. Pull back the other way to send it back, as many times as you like in one drag. Portrait and landscape positions are remembered separately.
- The menu keeps 15dp from the screen edge. In landscape, on the side with the front camera, it sits right next to the camera hole instead. The position is read from each phone's own camera cutout (Android 12+; older versions use the system's cutout safe area).
- A single tap does nothing, to avoid accidental opens. The handle area does not trigger the system back gesture.
- It only appears in your chosen apps, and hides automatically when you leave the app, lock the phone, or the screen is black.

## Permissions

| Permission | Used for |
|---|---|
| Accessibility | Showing the handle and the black cover (above the status and navigation bars), knowing the current app, locking the screen |
| Turn screen on (Android 14+) | Turning the screen on after locking with the power button, so you see the lock screen |

IdleMate **does not read any screen content** (`canRetrieveWindowContent=false`) and **has no internet permission**.

On first launch it shows short explanation dialogs and takes you to each settings page.

If it was installed from a file, IdleMate detects the install source and adds the steps to lift the restricted setting (see [Prompts you will see when installing](#prompts-you-will-see-when-installing)). Installs from the Play Store or adb are not affected.

## How it works

- **Black screen**: a full-screen black `TYPE_ACCESSIBILITY_OVERLAY` window with `FLAG_KEEP_SCREEN_ON`. The system never goes to sleep, so the foreground app and video keep running. Brightness is not changed. The cover is a square of the screen's long edge, so it still covers everything after rotation.
- **Double-tap**: detected on the cover with `GestureDetector`, which triggers `GLOBAL_ACTION_LOCK_SCREEN`.
- **Power button**: on `SCREEN_OFF` the cover is removed, then an `ACQUIRE_CAUSES_WAKEUP` wake lock turns the screen on to show the lock screen.
- **Foreground app detection**: listens to `TYPE_WINDOW_STATE_CHANGED` and only accepts real activities plus events from the home app.

### Known limitations

- Video stops once the phone is locked; this is expected. Free YouTube stops playback by itself when its screen is paused.
- While black, the panel is actually on (just showing black), so it saves less power than a truly off screen. On OLED screens, black pixels use almost no power.

### Why one mode instead of two

Xiaomi offers "screen-off idle" for games and "background listening" for videos as two features, but logs show both use the same system mechanism (`Go to sleep and hold on - <package>`): the system really sleeps, but the chosen app is kept from being paused. IdleMate likewise uses one mechanism for both cases, so it offers a single mode. Xiaomi's approach needs system privileges, which is why IdleMate uses a black overlay instead.

## Project structure

```
app/src/main/java/com/idlemate/app/
├── IdleAccessibilityService.kt  # Core: handle, menu, black cover, lock, wake, foreground detection
├── MainActivity.kt              # Settings screen and first-run setup guide (Compose)
├── Prefs.kt                     # Preferences (handle position, chosen apps)
├── OemHelper.kt                 # Permission checks, vendor settings pages, install source
└── DebugLog.kt                  # In-app diagnostic log
```

- Kotlin + Jetpack Compose
- minSdk 26, targetSdk 35

## Building

Every push to `main` makes GitHub Actions ([.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)) build a **release-signed** APK, uploaded as the `IdleMate-release-apk` artifact.

The release signing key lives only in GitHub Secrets, **never in the repo**:

| Secret | Contents |
|---|---|
| `IDLEMATE_KEYSTORE_B64` | Base64 of the PKCS12 keystore |
| `IDLEMATE_KEYSTORE_PASSWORD` | Keystore password |
| `IDLEMATE_KEY_ALIAS` | Key alias |

> If the key is lost, no further update can be installed over existing installs. Keep a separate backup. If the signing key ever changes, users must uninstall the old version first.

Local debug builds use the fixed `idlemate-debug.keystore` in the repo:

```sh
gradle assembleDebug
```

### Publishing a new version

Push a tag starting with `v`. The workflow builds, signs and publishes it to [Releases](https://github.com/david308109/IdleMate/releases):

```sh
git tag v0.2.0
git push origin v0.2.0
```

- The version name comes from the tag (`v0.2.0` → `0.2.0`) and the version code from the build number, so every release installs over the previous one.
- Release notes list the commits since the previous tag.
- Ordinary pushes to `main` only build; they do not publish.

### Installing on a phone

```sh
gh run download <run-id> -n IdleMate-release-apk -D IdleMate-apk
adb install -r IdleMate-apk/app-release.apk
```

After updating via adb, the accessibility service does not reconnect by itself. Turn IdleMate off and on again in Accessibility settings.

## Testing

See [TESTING.md](TESTING.md).
