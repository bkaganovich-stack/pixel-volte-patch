package dev.bluehouse.enablevolte

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
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

    /** Applies saved settings to every active subscription, skipping ones already correct. */
    private fun applySettings(repo: SettingsRepository): Int {
        val subscriptions = CarrierModer(applicationContext).subscriptions
        if (subscriptions.isEmpty()) {
            BootLog.append(applicationContext, WORKER_TAG, "no active subscriptions")
            return 0
        }

        var applied = 0
        for (subscription in subscriptions) {
            val moder = SubscriptionModer(applicationContext, subscription.subscriptionId)
            val slotIndex = subscription.simSlotIndex
            val settings = repo.loadSlotSettings(slotIndex)
            if (settings == null) {
                BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: nothing saved, skipping")
                continue
            }

            if (moder.matchesSettings(settings)) {
                BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: already matches, no IMS reset needed")
                continue
            }

            // Recorded before the call: ACTION_CARRIER_CONFIG_CHANGED can land while
            // overrideConfig is still running, and the echo guard must already see it.
            repo.lastAppliedAt = System.currentTimeMillis()
            moder.applyAllSettings(settings)
            BootLog.append(applicationContext, WORKER_TAG, "slot $slotIndex: applied")
            applied++
        }
        return applied
    }

    companion object {
        private const val WORK_NAME = "pixel-ims-auto-apply"
        private const val SHIZUKU_WAIT_MS = 60_000L

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
