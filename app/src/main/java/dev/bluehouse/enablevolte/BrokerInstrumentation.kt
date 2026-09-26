package dev.bluehouse.enablevolte

import android.annotation.SuppressLint
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

const val TAG = "BrokerInstrumentation"

/** Control keys [SubscriptionModer] passes in the instrumentation arguments. */
const val ARG_SUB_ID = "moder_subId"
const val ARG_CLEAR = "moder_clear"

class BrokerInstrumentation : Instrumentation() {
    private fun activityManager(): IActivityManager =
        IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))

    /**
     * Releases the shell permission delegation. Best effort: this runs from a `finally`
     * block, so anything thrown here would mask the real failure (or, for an [Error],
     * escape the caller entirely and kill the process). The delegation is also dropped
     * by the system when the instrumentation finishes.
     */
    private fun releaseShellPermissions(
        am: IActivityManager,
        uid: Int,
    ) {
        try {
            stopDelegateShellPermissionIdentityCompat(am, uid)
        } catch (e: Throwable) {
            Log.w(TAG, "stopDelegateShellPermissionIdentity failed", e)
            BootLog.appendError(context, TAG, "stopDelegateShellPermissionIdentity failed (non-fatal)", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyConfig(
        subId: Int,
        arguments: Bundle,
    ) {
        Log.i(TAG, "applyConfig subId=$subId")
        val uid = Os.getuid()
        BootLog.append(context, TAG, "applyConfig subId=$subId uid=$uid")
        val am = activityManager()
        am.startDelegateShellPermissionIdentity(uid, null)
        try {
            val configurationManager = this.context.getSystemService(CarrierConfigManager::class.java)
            // Drop our own control keys: everything left in the bundle is written verbatim
            // into the carrier config, and moder_subId was ending up there as a stray key.
            val overrideValues =
                toPersistableBundle(
                    Bundle(arguments).apply {
                        remove(ARG_SUB_ID)
                        remove(ARG_CLEAR)
                    },
                )

            BootLog.append(context, TAG, "calling overrideConfig(subId=$subId, persistent=false), keys=${overrideValues.keySet().size}")
            configurationManager.overrideConfig(subId, overrideValues, false)

            // overrideConfig returning is not proof: the loader applies it on its own handler
            // and can drop keys it refuses on user builds. Read the values back.
            val mismatched = mismatchedKeys(configurationManager, subId, overrideValues)
            if (mismatched.isNotEmpty()) {
                throw IllegalStateException("carrier config did not take: ${mismatched.joinToString()}")
            }
            BootLog.append(context, TAG, "overrideConfig verified")
        } catch (e: Exception) {
            Log.e(TAG, "overrideConfig failed", e)
            BootLog.appendError(context, TAG, "overrideConfig FAILED", e)
            throw e
        } finally {
            Log.i(TAG, "applyConfig done")
            releaseShellPermissions(am, uid)
        }
    }

    /**
     * Keys whose live value differs from what we just wrote. The read is retried briefly
     * because the loader applies the override asynchronously on its own thread.
     */
    @SuppressLint("MissingPermission")
    private fun mismatchedKeys(
        configurationManager: CarrierConfigManager,
        subId: Int,
        expected: PersistableBundle,
    ): List<String> {
        val keys = expected.keySet().toList()
        var mismatched = keys
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val live =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    configurationManager.getConfigForSubId(subId, *keys.toTypedArray())
                } else {
                    configurationManager.getConfigForSubId(subId)
                }
            mismatched = keys.filterNot { valuesEqual(expected.get(it), live?.get(it)) }
            if (mismatched.isEmpty()) return mismatched
            if (attempt < VERIFY_ATTEMPTS - 1) Thread.sleep(VERIFY_DELAY_MS)
        }
        return mismatched
    }

    private fun valuesEqual(
        a: Any?,
        b: Any?,
    ): Boolean =
        when {
            a is IntArray && b is IntArray -> a.contentEquals(b)
            a is LongArray && b is LongArray -> a.contentEquals(b)
            a is BooleanArray && b is BooleanArray -> a.contentEquals(b)
            a is DoubleArray && b is DoubleArray -> a.contentEquals(b)
            a is Array<*> && b is Array<*> -> a.contentEquals(b)
            else -> a == b
        }

    @SuppressLint("MissingPermission")
    private fun clearConfig(subId: Int) {
        Log.i(TAG, "clearConfig subId=$subId")
        val uid = Os.getuid()
        BootLog.append(context, TAG, "clearConfig subId=$subId uid=$uid")
        val am = activityManager()
        am.startDelegateShellPermissionIdentity(uid, null)
        try {
            val configurationManager = this.context.getSystemService(CarrierConfigManager::class.java)

            configurationManager.overrideConfig(subId, null, false)
            BootLog.append(context, TAG, "clearConfig OK")
        } catch (e: Exception) {
            Log.e(TAG, "clearConfig failed", e)
            BootLog.appendError(context, TAG, "clearConfig FAILED", e)
            throw e
        } finally {
            Log.i(TAG, "clearConfig done")
            releaseShellPermissions(am, uid)
        }
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)

        if (arguments == null) {
            BootLog.append(context, TAG, "onCreate: arguments=null, skipping")
            // Still finish: BrokerQueue is waiting on the watcher for this instrumentation.
            finish(0, Bundle().apply { putString(RESULT_ERROR, "no arguments") })
            return
        }

        val clear = arguments.getBoolean(ARG_CLEAR)
        val subId = arguments.getInt(ARG_SUB_ID)
        BootLog.append(context, TAG, "onCreate: subId=$subId clear=$clear")

        // Off the main thread: verification polls, and this process's main thread is the
        // app's UI thread. The result travels back to BrokerQueue through the watcher.
        Thread({ perform(subId, clear, arguments) }, "PixelIMS-broker-work").start()
    }

    private fun perform(
        subId: Int,
        clear: Boolean,
        arguments: Bundle,
    ) {
        val results = Bundle()
        // Catches Throwable, not Exception: this instrumentation runs inside the app's
        // own process (INSTR_FLAG_INSTRUMENT_WITHOUT_RESTART), so an escaping Error —
        // e.g. a NoSuchMethodError from a hidden API that changed shape in a platform
        // release — would take the whole app down instead of failing this one apply.
        try {
            if (clear) {
                this.clearConfig(subId)
            } else {
                this.applyConfig(subId, arguments)
            }
            results.putBoolean(RESULT_OK, true)
        } catch (e: Throwable) {
            BootLog.appendError(context, TAG, "broker FAILED", e)
            results.putBoolean(RESULT_OK, false)
            results.putString(RESULT_ERROR, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            BootLog.append(context, TAG, "finish(ok=${results.getBoolean(RESULT_OK)})")
            finish(0, results)
        }
    }

    private companion object {
        const val VERIFY_ATTEMPTS = 5
        const val VERIFY_DELAY_MS = 200L
    }
}
