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
/** Maximum wait time for the Shizuku binder before giving up (ms). */
private const val SHIZUKU_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

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
        handler.removeCallbacks(timeoutRunnable)
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                applySettings()
            } else {
                Log.w(AUTO_APPLY_TAG, "Shizuku binder available but permission not granted")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Error after Shizuku binder received", e)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Hidden API bypass is needed for telephony calls
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        createNotificationChannel()
        startForegroundCompat()

        try {
            val binder = Shizuku.getBinder()
            if (binder != null && binder.isBinderAlive &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(AUTO_APPLY_TAG, "Shizuku already available, applying immediately")
                applySettings()
            } else {
                Log.d(AUTO_APPLY_TAG, "Waiting for Shizuku binder (timeout ${SHIZUKU_TIMEOUT_MS / 1000}s)")
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
                    applySettings()
                }
            }
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Failed to initialize Shizuku connection", e)
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
        try {
            val repo = SettingsRepository(this)
            val carrierModer = CarrierModer(this)
            val subscriptions = carrierModer.subscriptions

            if (subscriptions.isEmpty()) {
                Log.w(AUTO_APPLY_TAG, "No active subscriptions found")
                return
            }

            var applied = 0
            for (subscription in subscriptions) {
                val slotIndex = subscription.simSlotIndex
                val settings = repo.loadSlotSettings(slotIndex) ?: run {
                    Log.d(AUTO_APPLY_TAG, "No saved settings for slot $slotIndex, skipping")
                    continue
                }
                Log.d(AUTO_APPLY_TAG, "Applying settings for slot $slotIndex (subId=${subscription.subscriptionId})")
                val moder = SubscriptionModer(this, subscription.subscriptionId)
                moder.applyAllSettings(settings)
                applied++
            }
            Log.i(AUTO_APPLY_TAG, "Applied settings for $applied subscription(s)")
        } catch (e: Exception) {
            Log.e(AUTO_APPLY_TAG, "Failed to apply settings after reboot", e)
        } finally {
            stopSelf()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
