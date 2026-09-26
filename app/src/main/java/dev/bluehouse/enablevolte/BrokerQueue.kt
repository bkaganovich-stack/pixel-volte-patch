package dev.bluehouse.enablevolte

import android.app.IActivityManager
import android.app.IInstrumentationWatcher
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private const val QUEUE_TAG = "PixelIMS:BrokerQueue"

/** Result keys [BrokerInstrumentation] puts into the bundle it finishes with. */
const val RESULT_OK = "moder_ok"
const val RESULT_ERROR = "moder_error"

/** Key the framework uses in the status bundle when it cannot start an instrumentation. */
private const val FRAMEWORK_ERROR_KEY = "Error"

/** INSTR_FLAG_NO_RESTART: run inside our live process instead of killing and restarting it. */
private const val INSTR_FLAG_NO_RESTART = 8

private const val BROKER_TIMEOUT_MS = 20_000L

/** Outcome of one carrier config write, as reported back by whoever performed it. */
data class BrokerResult(
    val ok: Boolean,
    val error: String? = null,
) {
    companion object {
        val OK = BrokerResult(true)
    }
}

/**
 * Serialises every carrier config write and IMS reset onto one background thread.
 *
 * [IActivityManager.startInstrumentation] only schedules the broker: it returns before the
 * override has happened. Callers used to fire it and immediately call resetIms, which then
 * raced the write it was meant to follow (from the UI it always lost, because the broker runs
 * on the main thread the click handler was still holding). Two keys set back to back also
 * started two instrumentations at once.
 *
 * Here each job waits for the broker's own finish callback before the next one starts, so
 * "write, then reset" happens in that order and every write gets a real success or failure.
 * Waiting happens on this queue's thread, never the main thread — the broker needs the main
 * thread to run at all, so blocking it would deadlock.
 */
object BrokerQueue {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PixelIMS-broker").apply { isDaemon = true }
        }

    fun <T> submit(task: () -> T): Future<T> = executor.submit(Callable(task))

    /**
     * Runs [BrokerInstrumentation] with [args] and blocks until it reports back.
     * Only ever called from the queue thread.
     */
    fun runBroker(
        context: Context,
        args: Bundle,
    ): BrokerResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "runBroker would deadlock on the main thread" }

        val latch = CountDownLatch(1)
        var result: BrokerResult? = null

        val watcher =
            object : IInstrumentationWatcher.Stub() {
                override fun instrumentationStatus(
                    name: ComponentName?,
                    resultCode: Int,
                    results: Bundle?,
                ) {
                    // The broker never reports status; the framework does, when it refuses to
                    // start the instrumentation at all, and then no finish callback follows.
                    val error = results?.getString(FRAMEWORK_ERROR_KEY) ?: return
                    result = BrokerResult(false, error)
                    latch.countDown()
                }

                override fun instrumentationFinished(
                    name: ComponentName?,
                    resultCode: Int,
                    results: Bundle?,
                ) {
                    if (result == null) {
                        result =
                            if (results?.getBoolean(RESULT_OK) == true) {
                                BrokerResult.OK
                            } else {
                                BrokerResult(false, results?.getString(RESULT_ERROR) ?: "broker finished without a result")
                            }
                    }
                    latch.countDown()
                }
            }

        val started =
            try {
                val am =
                    IActivityManager.Stub.asInterface(
                        ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)),
                    )
                am.startInstrumentation(
                    ComponentName(context, BrokerInstrumentation::class.java),
                    null,
                    INSTR_FLAG_NO_RESTART,
                    args,
                    watcher,
                    UiAutomationConnection(),
                    0,
                    null,
                )
            } catch (e: Throwable) {
                Log.e(QUEUE_TAG, "startInstrumentation threw", e)
                return BrokerResult(false, "startInstrumentation: ${e.javaClass.simpleName}: ${e.message}")
            }

        if (!started) return BrokerResult(false, "startInstrumentation returned false")
        if (!latch.await(BROKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return BrokerResult(false, "broker did not report back within ${BROKER_TIMEOUT_MS / 1000}s")
        }
        return result ?: BrokerResult(false, "broker finished without a result")
    }
}
