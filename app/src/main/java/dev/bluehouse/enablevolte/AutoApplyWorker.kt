package dev.bluehouse.enablevolte

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val WORKER_TAG = "PixelIMS:AutoApplyWorker"

/**
 * Re-applies the saved carrier config once Shizuku is reachable.
 *
 * WorkManager rather than a foreground service started from BOOT_COMPLETED: its queue is
 * persisted, so a run that cannot finish now survives reboots and Doze and is retried with
 * backoff. That matters because Shizuku is not up at boot on a non-rooted device — on the
 * test device it appeared 1h43m after boot, far outside any wait a boot-time service could
 * sit through.
 *
 * The short wait below is only for the case where Shizuku is seconds away. The real trigger
 * for a late start is [PixelImsApplication]: ShizukuProvider starts our process as soon as
 * the daemon comes up, and the sticky listener there enqueues this worker.
 */
class AutoApplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = SettingsRepository(applicationContext)

        if (!repo.autoApplyEnabled) {
            BootLog.append(applicationContext, WORKER_TAG, "auto-apply disabled, dropping work")
            return Result.success()
        }

        if (!awaitShizuku()) {
            BootLog.append(applicationContext, WORKER_TAG, "Shizuku unavailable, will retry with backoff")
            return Result.retry()
        }

        return try {
            val applied = applySettings(repo)
            repo.markApplied()
            BootLog.append(applicationContext, WORKER_TAG, "done, applied to $applied subscription(s)")
            Result.success()
        } catch (e: Throwable) {
            BootLog.appendError(applicationContext, WORKER_TAG, "apply failed, will retry", e)
            Result.retry()
        }
    }

    /** True once the Shizuku binder is alive and our permission is granted. */
    private suspend fun awaitShizuku(): Boolean {
        if (isShizukuReady()) return true

        val arrived =
            withTimeoutOrNull(SHIZUKU_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener =
                        object : Shizuku.OnBinderReceivedListener {
                            override fun onBinderReceived() {
                                Shizuku.removeBinderReceivedListener(this)
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                    // Sticky: fires immediately if the binder arrived between the check above
                    // and this registration, closing that race.
                    Shizuku.addBinderReceivedListenerSticky(listener)
                    cont.invokeOnCancellation { Shizuku.removeBinderReceivedListener(listener) }
                }
            }

        return arrived == true && isShizukuReady()
    }

    private fun isShizukuReady(): Boolean =
        try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

    /**
     * Applies saved settings to every active subscription, skipping ones already correct.
     * Throws if any write failed, so the run is retried and not marked as applied.
     */
    private suspend fun applySettings(repo: SettingsRepository): Int {
        val subscriptions = CarrierModer(applicationContext).subscriptions
        if (subscriptions.isEmpty()) {
            BootLog.append(applicationContext, WORKER_TAG, "no active subscriptions")
            return 0
        }

        var applied = 0
        val failures = mutableListOf<String>()
        for (subscription in subscriptions) {
            val moder = SubscriptionModer(applicationContext, subscription.subscriptionId)
            val slotIndex = subscription.simSlotIndex
            val settings = repo.loadSlotSettings(slotIndex)
            if (settings == null) {
                BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: nothing saved, skipping")
                continue
            }

            ensurePersistentVoLTE(moder, settings, repo, slotIndex)?.let { failures += it }

            if (moder.matchesSettings(settings)) {
                BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: already matches, no IMS reset needed")
                continue
            }

            // Recorded before the call: ACTION_CARRIER_CONFIG_CHANGED can land while
            // overrideConfig is still running, and the echo guard must already see it.
            repo.lastAppliedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { moder.applyAllSettings(settings).get() }
            if (!result.ok) {
                BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: apply FAILED: ${result.error}")
                failures += "slot $slotIndex: ${result.error}"
                continue
            }
            BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: applied and verified")
            applied++
            if (settings.voLTEEnabled || settings.voWiFiEnabled) logImsRegistration(moder, slotIndex)
        }
        if (failures.isNotEmpty()) throw IllegalStateException(failures.joinToString("; "))
        return applied
    }

    /**
     * Re-sets the VoIMS opt-in if the user wants persistent VoLTE and it is off (a new SIM,
     * or another tool cleared it). Only ever turns it on: switching it off is an explicit
     * user action, never something a background re-apply should do.
     * Returns a failure description, or null.
     */
    private suspend fun ensurePersistentVoLTE(
        moder: SubscriptionModer,
        settings: SubscriptionSettings,
        repo: SettingsRepository,
        slotIndex: Int,
    ): String? {
        if (!settings.persistentVoLTE || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val alreadyOn =
            try {
                moder.isVoImsOptInEnabled
            } catch (e: Throwable) {
                false
            }
        if (alreadyOn) return null
        val result = withContext(Dispatchers.IO) { moder.setPersistentVoLTE(true, repo).get() }
        BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: persistent VoLTE re-enabled, ok=${result.ok} ${result.error ?: ""}")
        return if (result.ok) null else "slot $slotIndex persistent VoLTE: ${result.error}"
    }

    /**
     * Diagnostic only: records whether IMS came back after the reset. resetIms is
     * asynchronous — right after it returns IMS still reads as registered on the old
     * config — so first wait for the drop, then for the re-registration. A config that is
     * in place but not registering points at the network, not at us, so this never fails
     * the run.
     */
    private suspend fun logImsRegistration(
        moder: SubscriptionModer,
        slotIndex: Int,
    ) {
        fun registered() =
            try {
                moder.isIMSRegistered
            } catch (e: Throwable) {
                false
            }

        val start = System.currentTimeMillis()
        val dropped =
            withTimeoutOrNull(IMS_DROP_WAIT_MS) {
                while (registered()) delay(IMS_POLL_MS)
                true
            } == true
        if (!dropped) {
            BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: IMS stayed registered, no drop seen within ${IMS_DROP_WAIT_MS / 1000}s")
            return
        }
        val back =
            withTimeoutOrNull(IMS_WAIT_MS) {
                while (!registered()) delay(IMS_POLL_MS)
                true
            } == true
        val seconds = (System.currentTimeMillis() - start) / 1000
        if (back) {
            BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: IMS re-registered ${seconds}s after reset")
        } else {
            BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: IMS not registered ${seconds}s after reset (config is applied)")
        }
    }

    companion object {
        private const val WORK_NAME = "pixel-ims-auto-apply"
        private const val SHIZUKU_WAIT_MS = 60_000L
        private const val IMS_DROP_WAIT_MS = 10_000L
        private const val IMS_WAIT_MS = 30_000L
        private const val IMS_POLL_MS = 1_000L

        /**
         * Queues a re-apply. KEEP rather than REPLACE: repeated triggers (boot, package
         * replace, a burst of carrier-config broadcasts) must not cancel a run that is
         * already waiting on Shizuku and restart its backoff from zero.
         */
        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<AutoApplyWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
