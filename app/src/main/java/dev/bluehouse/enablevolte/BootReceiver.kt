package dev.bluehouse.enablevolte

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.CarrierConfigManager
import android.util.Log

private const val BOOT_TAG = "PixelIMS:BootReceiver"

/**
 * Queues [AutoApplyWorker] when something may have discarded our carrier config overrides.
 *
 * Overrides are not persistent — Android 17 rejects overrideConfig(persistent = true) from a
 * non-system app — so they are lost on reboot, and the platform also drops them when the
 * build fingerprint or the carrier config package changes. Each trigger below marks one of
 * those moments.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val repo = SettingsRepository(context)
        if (!repo.autoApplyEnabled) {
            Log.d(BOOT_TAG, "Auto-apply disabled, ignoring ${intent.action}")
            return
        }

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                BootLog.append(context, BOOT_TAG, "=== ${intent.action} — queueing auto-apply ===")
                AutoApplyWorker.enqueue(context)
            }

            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> onCarrierConfigChanged(context, repo)
        }
    }

    /**
     * The platform broadcasts this in response to our own overrideConfig call, so reacting
     * to it unconditionally would re-apply forever. Ignore anything that lands within
     * [ECHO_WINDOW_MS] of our last write; a genuine reset (airplane mode, SIM reload, an OTA)
     * arrives well after that.
     */
    private fun onCarrierConfigChanged(
        context: Context,
        repo: SettingsRepository,
    ) {
        val sinceLastApply = System.currentTimeMillis() - repo.lastAppliedAt
        if (sinceLastApply in 0 until ECHO_WINDOW_MS) {
            Log.d(BOOT_TAG, "Carrier config changed ${sinceLastApply}ms after our own write — echo, ignoring")
            return
        }
        BootLog.append(context, BOOT_TAG, "carrier config changed externally — queueing auto-apply")
        // Force a re-check: the config we hold may have just been replaced.
        repo.lastAppliedBootId = 0
        AutoApplyWorker.enqueue(context)
    }

    private companion object {
        const val ECHO_WINDOW_MS = 10_000L
    }
}
