package dev.bluehouse.enablevolte

import android.annotation.SuppressLint
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.system.Os
import android.telephony.CarrierConfigManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

const val TAG = "BrokerInstrumentation"

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
            val overrideValues = toPersistableBundle(arguments)

            BootLog.append(context, TAG, "calling overrideConfig(subId=$subId, persistent=false), keys=${overrideValues.keySet().size}")
            configurationManager.overrideConfig(subId, overrideValues, false)
            BootLog.append(context, TAG, "overrideConfig returned OK")
        } catch (e: Exception) {
            Log.e(TAG, "overrideConfig failed", e)
            BootLog.appendError(context, TAG, "overrideConfig FAILED", e)
            throw e
        } finally {
            Log.i(TAG, "applyConfig done")
            releaseShellPermissions(am, uid)
        }
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
            return
        }

        val clear = arguments.getBoolean("moder_clear")
        val subId = arguments.getInt("moder_subId")
        BootLog.append(context, TAG, "onCreate: subId=$subId clear=$clear")

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
        } catch (e: Throwable) {
            BootLog.appendError(context, TAG, "onCreate FAILED", e)
        } finally {
            BootLog.append(context, TAG, "finish()")
            finish(0, Bundle())
        }
    }
}
