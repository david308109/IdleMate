**English** | [繁體中文](TESTING.zh-TW.md)

# IdleMate test checklist

## 1. "Restricted setting" guide (installed from a file)

Since Android 13, apps **installed from a file** (an APK sent over LINE, cloud storage, or downloaded in a browser) have their accessibility toggle locked and greyed out. IdleMate checks its install source and shows the steps to unlock it.

> Installs from the Play Store or via adb are not restricted, so this cannot be reproduced on your own phone with adb.

### Real test (most reliable)

1. Download `IdleMate-x.y.z.apk` from [Releases](https://github.com/david308109/IdleMate/releases/latest) (or the `IdleMate-release-apk` artifact from GitHub Actions).
2. Copy the APK to **another phone** (or uninstall IdleMate on this one first) and install it by tapping it in a file manager.
3. Open IdleMate. These should appear in order:
   - **Step 1 / 4: unlock the restricted setting first**
     - **Go** opens Accessibility settings, where IdleMate is greyed out and labelled as controlled by a restricted setting.
     - Tap it once, tap **OK** on the prompt, then return to IdleMate.
   - **Step 2 / 4: unlock the restricted setting**
     - **Go** opens App info.
     - Tap **⋮** in the top-right corner → **Allow restricted settings**, verify your identity, then return to IdleMate.
   - **Step 3 / 4: allow IdleMate to use accessibility?** Turn IdleMate on.
   - **Step 4 / 4: allow IdleMate to turn the screen on?** Turn IdleMate on.
4. Back in IdleMate, a "setup complete" message should show and the setup card should disappear.

Check that:

- In step 2, the **⋮** menu contains **Allow restricted settings**.
- After unlocking, the accessibility toggle can be turned on.
- For someone who already unlocked it and reinstalls: after turning the toggle on directly in step 1, step 2 does not appear.

### Simulating with adb (screens only; cannot actually unlock)

```sh
# Launch as if installed from a file (-S force-stops first so the extra is delivered)
adb shell am start -S -n com.idlemate.app/.MainActivity --ez simulate_sideload true
```

Turn IdleMate's accessibility service off before simulating, or the guide will not show. Turn it back on afterwards.

The system's restriction state can be checked with `adb shell appops get com.idlemate.app ACCESS_RESTRICTED_SETTINGS`:

| State | Meaning |
|---|---|
| `deny` | Locked; not yet tapped in Accessibility (the **⋮** option is not shown yet) |
| `ignore` | Locked; tapped once (the **⋮** option is now shown) |
| `allow` | Unlocked |

Note: the app itself cannot read this state (the system throws a SecurityException), so the app decides based on the install source, not this value.

## 2. Basic checks after every change

- [ ] Open YouTube: the handle appears at the screen edge. Go to the home screen: the handle disappears.
- [ ] Swipe inward from the handle: the menu opens and the system back gesture is **not** triggered.
- [ ] A single tap on the handle does not open the menu.
- [ ] Long-press the handle for about 0.5 s: the line gets thicker (no vibration) and can then be dragged up or down.
- [ ] In landscape, the handle defaults to the top-right.
- [ ] 🌙 Screen-off mode in YouTube: the screen goes black, audio keeps playing, taps are blocked.
- [ ] 🌙 Screen-off mode in a game: the screen goes black and the game keeps running.
- [ ] The menu shows one item, **🌙 熄屏運行**, plus **收起**.
- [ ] Rotate the phone while black (portrait ↔ landscape): the cover still fills the whole screen.
- [ ] While black, double-tap the screen or press the power button: you go straight to the lock screen.

## 3. adb notes

- After updating with `adb install -r`, the accessibility service does not reconnect by itself. Turn IdleMate off and on in settings, or rewrite `enabled_accessibility_services` with adb.
- If a test changes a phone setting (for example the screen timeout), **note the original value first** and restore it afterwards.
