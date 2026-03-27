package dev.bluehouse.enablevolte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler

import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

private const val AUTO_APPLY_TAG = "PixelIMS:AutoApply"
private const val NOTIFICATION_CHANNEL_ID = "pixel_ims_autostart"
private const val NOTIFICATION_ID = 9001
/**
 * Maximum wait time for the Shizuku binder before giving up (ms).
 * Must stay under 3 minutes on Android 14+ because SHORT_SERVICE foreground services
 * are automatically stopped by the system after 3 minutes.
 */
private const val SHIZUKU_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes

/**
 * Foreground service that waits for Shizuku to become available after a device
 * reboot and then re-applies all saved carrier configuration settings.
 * No root is required — Shizuku auto-starts via Wireless ADB on Pixel devices
 * when "Wireless Debugging" is kept enabled in Developer Options (Android 11+).
 *
 * The service stops itself after successfully applying settings or after the
 * [SHIZUKU_TIMEOUT_MS] timeout expires.
 */
@RequiresApi(Build.VERSION_CODES.O)
class AutoApplyService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val timeoutRunnable: Runnable = Runnable {
        Log.w(AUTO_APPLY_TAG, "Shizuku binder not received within timeout, giving up")
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        stopSelf()
    }

    private val binderReceivedListener: Shizuku.OnBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(AUTO_APPLY_TAG, "Shizuku binder received")
        BootLog.append(this, AUTO_APPLY_TAG, "Shizuku binder received")
        handler.removeCallbacks(timeoutRunnable)
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                applySettings()
            } else {
                val msg = "Shizuku binder available but permission not granted"
                Log.w(AUTO_APPLY_TAG, msg)
                BootLog.append(this, AUTO_APPLY_TAG, "WARN: $msg")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Error after Shizuku binder received", e)
            BootLog.appendError(this, AUTO_APPLY_TAG, "Error after Shizuku binder received", e)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        BootLog.append(this, AUTO_APPLY_TAG, "=== AutoApplyService started ===")

        // Hidden API bypass is needed for telephony calls
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        // startForeground() MUST be called before any early return or exception can escape
        // onCreate(). If it isn't, Android throws ForegroundServiceDidNotStartInTimeException
        // and kills the process — which also crashes the app on next launch.
        try {
            createNotificationChannel()
            startForegroundCompat()
            BootLog.append(this, AUTO_APPLY_TAG, "Foreground started (SDK=${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Failed to start foreground service, aborting", e)
            BootLog.appendError(this, AUTO_APPLY_TAG, "Failed to start foreground", e)
            stopSelf()
            return
        }

        try {
            val binder = Shizuku.getBinder()
            if (binder != null && binder.isBinderAlive &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(AUTO_APPLY_TAG, "Shizuku already available, applying immediately")
                BootLog.append(this, AUTO_APPLY_TAG, "Shizuku already available")
                applySettings()
            } else {
                Log.d(AUTO_APPLY_TAG, "Waiting for Shizuku binder (timeout ${SHIZUKU_TIMEOUT_MS / 1000}s)")
                BootLog.append(this, AUTO_APPLY_TAG, "Waiting for Shizuku (timeout ${SHIZUKU_TIMEOUT_MS / 1000}s), binder=${binder != null}")
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                handler.postDelayed(timeoutRunnable, SHIZUKU_TIMEOUT_MS)
                // Double-check in case Shizuku came up between getBinder() check and addListener
                val binder2 = Shizuku.getBinder()
                if (binder2 != null && binder2.isBinderAlive &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ) {
                    handler.removeCallbacks(timeoutRunnable)
                    Shizuku.removeBinderReceivedListener(binderReceivedListener)
                    Log.d(AUTO_APPLY_TAG, "Shizuku appeared between checks, applying immediately")
                    BootLog.append(this, AUTO_APPLY_TAG, "Shizuku appeared between checks")
                    applySettings()
                }
            }
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Failed to initialize Shizuku connection", e)
            BootLog.appendError(this, AUTO_APPLY_TAG, "Failed to init Shizuku", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeoutRunnable)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applySettings() {
        var applied = 0
        try {
            BootLog.append(this, AUTO_APPLY_TAG, "applySettings() called")
            val repo = SettingsRepository(this)
            val carrierModer = CarrierModer(this)
            val subscriptions = carrierModer.subscriptions

            BootLog.append(this, AUTO_APPLY_TAG, "subscriptions found: ${subscriptions.size}")
            if (subscriptions.isEmpty()) {
                Log.w(AUTO_APPLY_TAG, "No active subscriptions found")
                BootLog.append(this, AUTO_APPLY_TAG, "WARN: No active subscriptions — cannot apply")
                return
            }

            for (subscription in subscriptions) {
                val slotIndex = subscription.simSlotIndex
                val settings = repo.loadSlotSettings(slotIndex) ?: run {
                    Log.d(AUTO_APPLY_TAG, "No saved settings for slot $slotIndex, skipping")
                    BootLog.append(this, AUTO_APPLY_TAG, "slot $slotIndex: no saved settings, skipping")
                    continue
                }
                BootLog.append(this, AUTO_APPLY_TAG, "slot $slotIndex (subId=${subscription.subscriptionId}): launching instrumentation…")
                Log.d(AUTO_APPLY_TAG, "Applying settings for slot $slotIndex (subId=${subscription.subscriptionId})")
                try {
                    val moder = SubscriptionModer(this, subscription.subscriptionId)
                    moder.applyAllSettings(settings)
                    // applyAllSettings() → overrideConfigUsingBroker() → startInstrumentation()
                    // is ASYNC: BrokerInstrumentation runs in a separate SDK-sandbox process.
                    // We must keep this service process alive so the sandbox can interact with
                    // ShizukuProvider (hosted in our process) to complete the override.
                    BootLog.append(this, AUTO_APPLY_TAG, "slot $slotIndex: instrumentation launched (async)")
                    applied++
                } catch (e: Exception) {
                    Log.e(AUTO_APPLY_TAG, "Failed to apply slot $slotIndex", e)
                    BootLog.appendError(this, AUTO_APPLY_TAG, "slot $slotIndex: FAILED", e)
                }
            }
            Log.i(AUTO_APPLY_TAG, "Launched settings apply for $applied subscription(s)")
            BootLog.append(this, AUTO_APPLY_TAG, "All instrumentations launched: $applied / ${subscriptions.size}")
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Failed to apply settings after reboot", e)
            BootLog.appendError(this, AUTO_APPLY_TAG, "applySettings() FAILED", e)
        } finally {
            if (applied > 0) {
                // Give BrokerInstrumentation time to complete in the SDK sandbox process.
                // Each instrumentation takes a few seconds to start, delegate shell
                // permissions, call overrideConfig(), and finish. We keep the service
                // (and thus our process) alive so ShizukuProvider stays reachable.
                val delayMs = 12_000L
                BootLog.append(this, AUTO_APPLY_TAG, "Keeping service alive ${delayMs / 1000}s for instrumentations to finish…")
                handler.postDelayed({
                    BootLog.append(this, AUTO_APPLY_TAG, "stopSelf() after delay")
                    stopSelf()
                }, delayMs)
            } else {
                BootLog.append(this, AUTO_APPLY_TAG, "stopSelf() (nothing applied)")
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_applying_settings))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        when {
            // Android 10+ (API 29+): use CONNECTED_DEVICE — explicitly allowed to start from
            // BOOT_COMPLETED on all Android versions (including Android 15/16).
            // DATA_SYNC and SHORT_SERVICE are restricted from BOOT_COMPLETED on Android 15+.
            // CONNECTED_DEVICE is semantically correct: we are configuring SIM/modem connectivity.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            // Android 8-9 (API 26-28): no service type required.
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }
}
