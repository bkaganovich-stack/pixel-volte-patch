# Pixel IMS Auto-Apply fix

A fork of [tdrkDev/pixel-volte-patch](https://github.com/tdrkDev/pixel-volte-patch) QPR2 Beta 3 fix, adding three features on top of the original:

1. **Settings persistence** — every toggle change is saved to SharedPreferences automatically.
2. **Auto-apply on reboot** — after each reboot, the app waits for Shizuku to become available (up to 5 minutes) and re-applies all saved settings without any user interaction.
3. **Export / Import** — save all settings to a JSON file and restore them on another device.

No root required. Works with Shizuku over Wireless ADB — the standard setup for Pixel phones.

---

## What changed from the original

| Area | v1.3.0 QPR2 Beta 3 fix | v1.3.3 Auto-Apply fix |
|---|---|---|
| **Version** | 1.3.0 | 1.3.3 (versionCode 15) |
| **Settings persistence** | None — settings reset on every reboot | Saved automatically on every toggle change |
| **Auto-apply on reboot** | Manual — user must open app and re-apply | Automatic — BootReceiver starts a foreground service that waits for Shizuku, then applies |
| **Export / Import** | Not available | Export to JSON; import and apply on any device |
| **New files** | — | `SettingsRepository.kt`, `BootReceiver.kt`, `AutoApplyService.kt` |
| **Modified files** | — | `Moder.kt`, `Config.kt`, `Home.kt`, `strings.xml`, `AndroidManifest.xml`, `build.gradle` |

All original functionality (VoLTE, VoNR, Cross-SIM, VoWiFi, VT, cosmetic toggles, Expert Mode, Dump Config, QS tiles) is unchanged.

---

## How auto-apply works

```
Boot completed
   └─► BootReceiver.onReceive()
         └─► starts AutoApplyService (foreground, dataSync type)
               └─► waits up to 5 min for Shizuku binder
                     └─► Shizuku available + permission granted
                           └─► loads saved slot settings from SharedPreferences
                               → calls applyAllSettings() for each active SIM
                               → stops itself
```

**Requirement:** Shizuku must be configured to auto-start after reboot via Wireless ADB.

### One-time setup

1. On your Pixel: **Developer Options → Wireless Debugging** — enable it (persists across reboots on Pixel with Android 11+).
2. Open the **Shizuku** app → **"Start via Wireless Debugging"** → grant permission once.
3. In Pixel IMS → **Home** tab → enable **"Auto-apply settings on reboot"**.

After that, every reboot automatically restores your carrier config with no user interaction.

---

## Export / Import

In the **SIM Config** screen, scroll to **Miscellaneous**:

- **Export settings to file** — saves a JSON file to a location you choose.
- **Import settings from file** — loads the JSON, applies settings to the current SIM immediately, and saves them for future reboots.

JSON format:
```json
{
  "version": "1.3.3",
  "app": "pixel-ims",
  "slots": [
    {
      "slotIndex": 0,
      "voLTEEnabled": true,
      "voWiFiEnabled": true
    }
  ]
}
```

Settings are keyed by SIM slot index (0 or 1), so they transfer correctly to any device regardless of subscription ID changes between reboots.

---

## Build from source

Requires a **patched `android.jar`** that exposes hidden telephony APIs:

```bash
# Replace the standard SDK stub with the patched version
curl -L https://github.com/Reginer/aosp-android-jar/raw/main/android-36/android.jar \
     -o "$ANDROID_HOME/platforms/android-36/android.jar"

# Build (JDK 17 required)
./gradlew assembleRelease
```

---

## Credits

Original Pixel IMS by [kyujin-cho](https://github.com/kyujin-cho/pixel-volte-patch).
QPR2 Beta 3 fix by [tdrkDev](https://github.com/tdrkDev/pixel-volte-patch).

This fork (persistence + auto-apply + export/import) is built on top of tdrkDev's QPR2 branch with all original code and structure preserved.

---

## License

[GPL-3.0](LICENSE) — same as the original project.
