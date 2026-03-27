package dev.bluehouse.enablevolte

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val BOOT_TAG = "PixelIMS:BootReceiver"

/**
 * Receives BOOT_COMPLETED and starts [AutoApplyService] to re-apply saved carrier
 * config settings once Shizuku becomes available.
 *
 * Auto-apply only runs when the user has enabled it via the Home screen toggle.
 * For non-root Shizuku users who must manually start Shizuku after each boot,
 * the service will wait up to 2 minutes for the Shizuku binder to appear.
 *
 * [AutoApplyService] uses FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE which is explicitly
 * allowed from BOOT_COMPLETED on all Android versions (unlike shortService/dataSync
 * which are restricted on Android 15+).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val repo = SettingsRepository(context)
        if (!repo.autoApplyEnabled) {
            Log.d(BOOT_TAG, "Auto-apply disabled, skipping")
            return
        }

        Log.d(BOOT_TAG, "Boot completed — starting AutoApplyService")
        BootLog.append(context, BOOT_TAG, "=== BOOT_COMPLETED received, starting AutoApplyService ===")

        val serviceIntent = Intent(context, AutoApplyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
