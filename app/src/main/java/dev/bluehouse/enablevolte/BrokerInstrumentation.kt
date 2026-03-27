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
    @SuppressLint("MissingPermission")
    private fun applyConfig(
        subId: Int,
        arguments: Bundle,
    ) {
        Log.i(TAG, "applyConfig subId=$subId")
        BootLog.append(context, TAG, "applyConfig subId=$subId uid=${Os.getuid()}")
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))
        am.startDelegateShellPermissionIdentity(Os.getuid(), null)
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
            am.stopDelegateShellPermissionIdentity()
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearConfig(subId: Int) {
        Log.i(TAG, "clearConfig subId=$subId")
        BootLog.append(context, TAG, "clearConfig subId=$subId")
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))
        am.startDelegateShellPermissionIdentity(Os.getuid(), null)
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
            am.stopDelegateShellPermissionIdentity()
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

        try {
            if (clear) {
                this.clearConfig(subId)
            } else {
                this.applyConfig(subId, arguments)
            }
        } catch (e: Exception) {
            BootLog.appendError(context, TAG, "onCreate FAILED", e)
        } finally {
            BootLog.append(context, TAG, "finish()")
            finish(0, Bundle())
        }
    }
}
