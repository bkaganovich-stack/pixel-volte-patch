package dev.bluehouse.enablevolte

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.util.Log

private const val BOOT_JOB_TAG = "PixelIMS:BootJob"

/**
 * A [JobService] that bridges the BOOT_COMPLETED broadcast restriction gap on Android 15+.
 *
 * Android 15 blocks starting foreground services of type `shortService` directly from
 * BOOT_COMPLETED broadcast receivers. However, starting from a JobService callback is
 * explicitly permitted (apps with a running job are allowed to start FGS from background).
 *
 * [BootReceiver] schedules this job instead of calling startForegroundService() directly.
 * This job immediately starts [AutoApplyService] and finishes — all actual work happens
 * inside the service.
 */
class BootJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(BOOT_JOB_TAG, "Boot job started — launching AutoApplyService")
        BootLog.append(this, BOOT_JOB_TAG, "=== BootJobService.onStartJob() ===")
        try {
            val serviceIntent = Intent(this, AutoApplyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            BootLog.append(this, BOOT_JOB_TAG, "AutoApplyService start requested")
        } catch (e: Exception) {
            Log.e(BOOT_JOB_TAG, "Failed to start AutoApplyService", e)
            BootLog.appendError(this, BOOT_JOB_TAG, "Failed to start AutoApplyService", e)
        }
        // All real work happens in AutoApplyService; this job is done immediately.
        jobFinished(params, /* needsReschedule = */ false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Nothing to clean up — we only started a service.
        return false
    }
}
