<p align="right"><a href="README.md">Русский</a> | <b>English</b></p>

[![Android](https://img.shields.io/badge/Android-14%E2%80%9317-3DDC84?logo=android&logoColor=white)](#) [![Devices](https://img.shields.io/badge/Google%20Pixel-6%E2%80%9311%20Series-4285F4?logo=google&logoColor=white)](#) [![Shizuku](https://img.shields.io/badge/Powered%20by-Shizuku--Next-FF6F00)](#) [![Release](https://img.shields.io/github/v/release/bkaganovich-stack/pixel-volte-patch?color=blue)](https://github.com/bkaganovich-stack/pixel-volte-patch/releases)

# Enabling VoLTE and VoWiFi on Google Pixel phones without root or an unlocked bootloader

---

**Pixel IMS** enables voice calls over LTE (VoLTE) and over Wi-Fi (VoWiFi) on Google Pixel phones on carriers for which Google has restricted these features in software.

The app works through the standard system API `CarrierConfigManager`, using the privileges of **Shizuku-Next** (an adapted fork of Shizuku with full support for Android 16–17):
* **Safe for the system:** the bootloader stays locked and the warranty stays intact.
* **Full compatibility:** Google Wallet, banking apps and government services keep working normally.
* **Automatic recovery:** a background service restores the settings after the phone restarts.

---

## Contents
1. [Step 1. Download and install the apps](#-step-1-download-and-install-the-apps)
2. [Step 2. Enable Developer options](#️-step-2-enable-developer-options)
3. [Step 3. Pair and start Shizuku-Next](#-step-3-pair-and-start-shizuku-next)
4. [Step 4. Turn on VoLTE and VoWiFi](#-step-4-turn-on-volte-and-vowifi)
5. [Step 5. Check that it works](#-step-5-check-that-it-works)
6. [Keeping the settings after a restart](#-keeping-the-settings-after-a-restart)
7. [Troubleshooting (FAQ)](#-troubleshooting-faq)
8. [What this fork adds and building from source](#-what-this-fork-adds-and-building-from-source)
9. [License and credits](#-license-and-credits)

> [!NOTE]
> The screenshots below were taken on a phone with the interface in Russian. The menu names in the text are given as they appear in English.

---

## 📥 Step 1. Download and install the apps

1. Go to the [**Shizuku-Next**](https://github.com/rushiranpise/Shizuku-Next/releases) releases page, expand **Assets** under the latest version, download the `.apk` file and install it.
2. Go to the [**Pixel IMS**](https://github.com/bkaganovich-stack/pixel-volte-patch/releases) releases page, open **Assets** the same way, download the `.apk` and install the app on your phone.

<p align="center">
  <img src="docs/screenshots/01_github_assets.png" width="260" alt="Downloading the APK from Assets" />
  <img src="docs/screenshots/02_apps_installed.png" width="260" alt="Installed app icons" />
</p>

---

## ⚙️ Step 2. Enable Developer options

To pass configuration values to the modem, you need to turn on Android's built-in debugging interface.

1. Open the phone's **Settings** → scroll to the bottom and select **About phone**.
2. Scroll to the very bottom of the screen, to **Build number**.
3. Tap **Build number** 7 times in quick succession.
4. Confirm with your usual screen lock PIN or pattern — the message *"You are now a developer!"* appears at the bottom.

<p align="center">
  <img src="docs/screenshots/03_about_phone.png" width="260" alt="About phone" />
  <img src="docs/screenshots/04_build_number.png" width="260" alt="Tapping Build number" />
</p>

---

## 🔗 Step 3. Pair and start Shizuku-Next

> [!IMPORTANT]
> During the initial pairing the phone must be connected to a Wi-Fi network (a home router or a hotspot from another phone). Once setup is complete, you can turn Wi-Fi off.

1. Open the **Shizuku** app (Shizuku-Next).
2. In the **Start via Wireless debugging** section, tap **Pairing**. Allow the app to send notifications when the system asks.
3. Tap **Developer options** — the system settings menu opens.
4. Scroll down to the "Debugging" section and turn on three options:
   * Turn on **USB debugging**.
   * Turn on **Wireless debugging** (check *"Always allow on this network"* and tap **Allow**).
   * A little further down, turn on **Disable adb authorization timeout** (keeps the authorization from being revoked while the device is idle).
5. Tap **the text of the "Wireless debugging" row itself** (to the left of the switch) to open the wireless debugging settings.
6. Select **Pair device with pairing code** — a six-digit code appears on the screen.
7. Without closing the code window, pull down the notification shade. In the Shizuku notification *"Pairing service found"*, tap **Enter pairing code**, type the 6 digits from the screen and tap the send button.
8. Go back to the **Shizuku-Next** main screen and tap **Start**. After 3–5 seconds the status changes to **"Shizuku is running"**.

<p align="center">
  <img src="docs/screenshots/05_shizuku_pairing.jpg" width="260" alt="Pairing button in Shizuku-Next" />
  <img src="docs/screenshots/06_wireless_debugging.png" width="260" alt="Turning on Wireless debugging" />
  <img src="docs/screenshots/07_pairing_code_input.jpg" width="260" alt="Entering the 6-digit code in the notification shade" />
  <br><sub>First and third screenshots (classic Shizuku): <a href="https://nekolands.com/games/shizuku-gajd-po-nastrojke-i-polucheniju-dostupa-k-papke-data-obb/">nekolands.com — Shizuku setup guide</a></sub>
</p>

---

## 🚀 Step 4. Turn on VoLTE and VoWiFi

1. Open **Pixel IMS**.
2. In the Shizuku dialog that appears, select **Allow all the time**.
3. Select the SIM card you need (if you use a physical SIM together with an eSIM, apply the patch to each card in turn).
4. Turn on the **VoLTE** and **VoWiFi** switches, then tap the button that applies the patch.
5. The phone's modem restarts its network registration (the signal indicator in the status bar disappears and comes back after 2–3 seconds).

> [!TIP]
> After applying the patch, go to the system **Settings → Network & internet → SIMs → [your carrier]** and make sure the standard **4G calling (VoLTE)** and **Wi-Fi calling** switches that now appear are turned on.

<p align="center">
  <img src="docs/screenshots/08_shizuku_permission.png" width="260" alt="Granting the Shizuku permission" />
  <img src="docs/screenshots/09_patch_applied.png" width="260" alt="Pixel IMS interface" />
  <br><sub>First screenshot: <a href="https://github.com/kyujin-cho/pixel-volte-patch">kyujin-cho/pixel-volte-patch</a> (README, GPL-3.0)</sub>
</p>

---

## ✅ Step 5. Check that it works

### Method 1. A test call
1. Turn off Wi-Fi so that the **LTE** or **4G** mobile data icon is shown in the top right corner of the screen.
2. Make an outgoing call to any number.
3. If the **LTE / 4G** indicator stays in the status bar while the call rings and during the conversation (without switching to `H+`, `3G` or `E`), and the call connects within 1–2 seconds, voice over LTE is working.

### Method 2. Checking the IMS status in the Pixel service menu
1. Open the standard **Phone** app and enter this code on the keypad:
   ```text
   *#*#4636#*#*
   ```
2. In the menu that opens, select **Phone information** (`Phone 0` is the first SIM card, `Phone 1` is the second).
3. Tap the **three-dot icon (⋮)** in the top right corner and open **IMS Service Status**.
4. Check the current values:
   * **IMS Registration:** `Registered`
   * **Voice over LTE:** `Available`
   * **Voice over WiFi:** `Available` (when Wi-Fi is connected and the carrier supports it)

<p align="center">
  <img src="docs/screenshots/10_testing_menu.png" width="260" alt="Service menu *#*#4636#*#*" />
  <img src="docs/screenshots/11_ims_status.png" width="260" alt="IMS Service Status window" />
</p>

---

## 🔄 Keeping the settings after a restart

Android's security architecture resets `CarrierConfig` overrides when the phone is fully restarted or a monthly OTA update is installed. To have VoLTE restored automatically in the background, do two things:

1. **Turn off battery optimization for both apps:**
   * Open **Settings → Apps → See all apps**.
   * Select **Shizuku**, go to **App battery usage**, tap the text of the *"Allow background usage"* row itself and select **Unrestricted**.
   * Repeat the same steps for **Pixel IMS**.
1. **Turn on autostart in Shizuku-Next:**
   * In the Shizuku-Next settings, turn on **Start on boot** and background monitoring (**Watchdog**). After the phone restarts and connects to a network, the service starts by itself, and **Pixel IMS** applies the modem configuration automatically.

---

## 🛠 Troubleshooting (FAQ)

Below are solutions to the typical problems that come up during the initial setup on Android 16–17. Click a problem to expand the instructions.

<details>
<summary><b>1. The field for entering the Shizuku pairing code does not appear in the notification shade</b></summary>
<br>

On phones with Android 16–17 the system may block the input pop-up if Shizuku notifications have low priority or Do Not Disturb is on.
* Go to **Settings → Apps → Shizuku → Notifications** and make sure all notification categories are turned on.
* In the system **Wireless debugging** menu, remove old entries from the "Paired devices" list, turn the **Wireless debugging** switch off and on again, then tap **Pairing** in Shizuku-Next again.
* If the notification is collapsed, drag it down with two fingers or tap the small arrow in the right corner of the notification.
</details>

<details>
<summary><b>2. Entering the pairing code hangs at "Searching for pairing service..."</b></summary>
<br>

This happens when the local network blocks network discovery (mDNS) or the phone is connected to a guest Wi-Fi network with client isolation.
* Restart Wi-Fi on the phone, or temporarily share a hotspot from another phone and connect to it.
* If a VPN client or a personal DNS filter is running on the phone (for example, AdGuard in local VPN mode), turn it off for 2 minutes while you enter the 6-digit code.
* Make sure that in **Developer options** not only "Wireless debugging" but also the basic **USB debugging** switch is turned on.
</details>

<details>
<summary><b>3. The patch was applied, but the IMS status in *#*#4636#*#* is "Not registered"</b></summary>
<br>

The phone has unlocked the feature in software, but the carrier's network has not yet authorized your SIM card.
* Turn on **Airplane mode** for 10–15 seconds and then turn it off to make the modem register again.
* Check in your carrier's official app whether the free **"VoLTE"** / **"4G calling"** service is enabled for your number. Some carriers disable this option in billing by default if the SIM card has never been used in a phone they certified.
* If the SIM card is more than 5–7 years old, visit a carrier store to replace it with a new USIM for free, or reissue your eSIM profile.
</details>

<details>
<summary><b>4. The "Wireless debugging" switch in Developer options is grayed out</b></summary>
<br>

Wireless debugging in Android only works while the phone is connected to a Wi-Fi network.
* Connect to any Wi-Fi access point — the switch becomes available as soon as the phone gets an IP address.
* Check **Settings → Security & privacy → Advanced Protection** — if **Advanced Protection** is turned on there, turn it off temporarily, because it blocks the ADB interface.
</details>

<details>
<summary><b>5. VoLTE settings are lost after the phone restarts</b></summary>
<br>

This is normal Android behavior after a cold start. To have the settings restored without manual steps:
* Check that **Shizuku-Next** and **Pixel IMS** both have battery usage set to **Unrestricted** (see [Keeping the settings after a restart](#-keeping-the-settings-after-a-restart)).
* Make sure **Disable adb authorization timeout** is turned on in **Developer options** — without it, the system revokes the debugging keys every 7 days.
</details>

---

## 🧩 What this fork adds and building from source

This fork is based on the Android 16 QPR2 Beta 3 fix branch by [tdrkDev](https://github.com/tdrkDev/pixel-volte-patch) and adds the following to the original Pixel IMS:

* **Settings persistence** — every switch change is saved automatically.
* **Automatic re-apply** — the saved configuration is applied again whenever the carrier settings may have been reset: after a restart, after an app update, and when the system reloads the carrier configuration. The job is placed in a persistent background queue (WorkManager) and runs as soon as Shizuku becomes reachable. If the live configuration already matches the saved one, IMS is left alone. Turn it on with the **Auto-apply settings on reboot** switch on the **Home** tab.
* **Export and import** — on the **Home** tab you can save the settings of all SIM cards to a JSON file (**Export settings to file**) and restore them on another device (**Import settings from file**).

### Building from source

Building requires a **patched `android.jar`** that exposes the hidden telephony APIs:

```bash
# Replace the standard SDK stub with the patched version
curl -L https://github.com/Reginer/aosp-android-jar/raw/main/android-36/android.jar \
     -o "$ANDROID_HOME/platforms/android-36/android.jar"

# Build (JDK 17 required)
./gradlew assembleRelease
```

---

## 📄 License and credits

* Fork development and maintenance: [@bkaganovich-stack](https://github.com/bkaganovich-stack)
* Original Pixel IMS: [kyujin-cho/pixel-volte-patch](https://github.com/kyujin-cho/pixel-volte-patch/tree/main)
* Android 16 QPR2 Beta 3 fix: [tdrkDev/pixel-volte-patch](https://github.com/tdrkDev/pixel-volte-patch)
* Original Shizuku: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
* System backend: [Shizuku-Next (@rushiranpise)](https://github.com/rushiranpise/Shizuku-Next)
* License: [GPL-3.0](LICENSE), the same as the original project.
