package dev.bluehouse.enablevolte

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.os.IInterface
import android.os.Looper
import android.telephony.CarrierConfigManager
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyFrameworkInitializer
import android.telephony.ims.ProvisioningManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.internal.telephony.IPhoneSubInfo
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Future

object InterfaceCache {
    val cache = HashMap<String, IInterface>()
}

/** Package name Shizuku calls are attributed to: they run as the shell uid. */
private const val SHELL_PACKAGE = "com.android.shell"

/** Subscription database columns (SubscriptionManager.VOIMS_OPT_IN_STATUS / ENHANCED_4G_MODE_ENABLED). */
private const val COLUMN_VOIMS_OPT_IN = "voims_opt_in_status"
private const val COLUMN_ENHANCED_4G_MODE = "volte_vt_enabled"

/** Values of [CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY]. */
const val CARRIER_NR_AVAILABILITY_NSA = 1
const val CARRIER_NR_AVAILABILITY_SA = 2

open class Moder {
    @Suppress("ktlint:standard:property-naming")
    val KEY_IMS_USER_AGENT = "ims.ims_user_agent_string"

    protected inline fun <reified T : IInterface> loadCachedInterface(interfaceLoader: () -> T): T {
        InterfaceCache.cache[T::class.java.name]?.let {
            return it as T
        } ?: run {
            val i = interfaceLoader()
            InterfaceCache.cache[T::class.java.name] = i
            return i
        }
    }

    protected val carrierConfigLoader: ICarrierConfigLoader
        get() =
            ICarrierConfigLoader.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .carrierConfigServiceRegisterer
                        .get()!!,
                ),
            )

    protected val telephony: ITelephony
        get() =
            ITelephony.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .telephonyServiceRegisterer
                        .get()!!,
                ),
            )

    protected val phoneSubInfo: IPhoneSubInfo
        get() =
            IPhoneSubInfo.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .phoneSubServiceRegisterer
                        .get()!!,
                ),
            )

    protected val sub: ISub
        get() =
            ISub.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .subscriptionServiceRegisterer
                        .get()!!,
                ),
            )
}

class CarrierModer(
    private val context: Context,
) : Moder() {
    fun getActiveSubscriptionInfoForSimSlotIndex(index: Int): SubscriptionInfo? {
        val sub = this.loadCachedInterface { sub }
        return sub.getActiveSubscriptionInfoForSimSlotIndex(index, null, null)
    }

    val subscriptions: List<SubscriptionInfo>
        get() {
            val sub = this.loadCachedInterface { sub }
            return try {
                sub.getActiveSubscriptionInfoList(null, null, true)
            } catch (e: NoSuchMethodError) {
                // FIXME: lift up reflect as soon as official source code releases
                val getActiveSubscriptionInfoListMethod =
                    sub.javaClass.getMethod(
                        "getActiveSubscriptionInfoList",
                        String::class.java,
                        String::class.java,
                        Boolean::class.java,
                    )
                (getActiveSubscriptionInfoListMethod.invoke(sub, null, null, false) as List<SubscriptionInfo>)
            }
        }

    val defaultSubId: Int
        get() {
            val sub = this.loadCachedInterface { sub }
            return sub.defaultSubId
        }

    val deviceSupportsIMS: Boolean
        get() {
            val res = Resources.getSystem()
            val volteConfigId = res.getIdentifier("config_device_volte_available", "bool", "android")
            return res.getBoolean(volteConfigId)
        }
}

private fun nrAvailabilities(saEnabled: Boolean): IntArray =
    if (saEnabled) {
        intArrayOf(CARRIER_NR_AVAILABILITY_NSA, CARRIER_NR_AVAILABILITY_SA)
    } else {
        intArrayOf(CARRIER_NR_AVAILABILITY_NSA)
    }

class SubscriptionModer(
    private val context: Context,
    val subscriptionId: Int,
) : Moder() {
    @Suppress("ktlint:standard:property-naming")
    private val TAG = "CarrierModer"

    /**
     * Before the 2025-10 security patch the loader accepted overrideConfig from the shell
     * uid directly; from then on it has to come from our own uid holding the shell's
     * permissions, which is what [BrokerInstrumentation] provides.
     */
    private val useBroker: Boolean
        get() {
            val securityPatchDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH) ?: return false
            val cal = Calendar.getInstance().apply { time = securityPatchDate }
            return cal.get(Calendar.YEAR) > 2025 || (cal.get(Calendar.YEAR) == 2025 && cal.get(Calendar.MONTH) >= 9)
        }

    /** Writes [bundle] (or clears our overrides when null). Runs on the [BrokerQueue] thread. */
    private fun writeConfigNow(bundle: Bundle?): BrokerResult {
        if (useBroker) {
            val args = if (bundle != null) Bundle(bundle) else Bundle().apply { putBoolean(ARG_CLEAR, true) }
            args.putInt(ARG_SUB_ID, subscriptionId)
            return BrokerQueue.runBroker(context, args)
        }
        return try {
            val iCclInstance = this.loadCachedInterface { carrierConfigLoader }
            iCclInstance.overrideConfig(subscriptionId, bundle?.let { toPersistableBundle(it) }, false)
            BrokerResult.OK
        } catch (e: Throwable) {
            Log.e(TAG, "overrideConfig failed", e)
            BrokerResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun resetImsNow() {
        val telephony = this.loadCachedInterface { telephony }
        val sub = this.loadCachedInterface { sub }
        telephony.resetIms(sub.getSlotIndex(this.subscriptionId))
    }

    /** Shows a failed write to the user; the toggle they just flipped did not take effect. */
    private fun toastIfFailed(result: BrokerResult) {
        if (result.ok) return
        Log.w(TAG, "carrier config write failed: ${result.error}")
        Handler(Looper.getMainLooper()).post {
            Toast
                .makeText(
                    context.applicationContext,
                    context.getString(R.string.apply_failed, result.error ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    private fun overrideConfig(bundle: Bundle?): Future<BrokerResult> =
        BrokerQueue.submit { writeConfigNow(bundle).also { toastIfFailed(it) } }

    private fun publishBundle(fn: (Bundle) -> Unit) {
        val overrideBundle = Bundle()
        fn(overrideBundle)
        this.overrideConfig(overrideBundle)
    }

    fun updateCarrierConfig(
        key: String,
        value: Boolean,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putBoolean(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: String,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putString(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: Int,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putInt(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: Long,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putLong(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: IntArray,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putIntArray(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: BooleanArray,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putBooleanArray(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: Array<String>,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putStringArray(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: LongArray,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putLongArray(key, value) }
    }

    fun clearCarrierConfig(): Future<BrokerResult> = this.overrideConfig(null)

    /**
     * Sets [CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY]. Turning SA off
     * leaves NSA in place rather than emptying the array, which is what stock Pixel
     * carrier configs ship and avoids disabling 5G outright.
     */
    fun updateNRAvailabilities(saEnabled: Boolean) {
        Log.d(TAG, "Setting NR availabilities, SA=$saEnabled")
        this.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, nrAvailabilities(saEnabled))
    }

    /**
     * Queued behind any pending carrier config write, so a reset requested right after a
     * toggle re-registers IMS with the new config rather than the old one.
     */
    fun restartIMSRegistration(): Future<Unit> =
        BrokerQueue.submit {
            try {
                resetImsNow()
            } catch (e: Throwable) {
                Log.e(TAG, "resetIms failed", e)
            }
        }

    /**
     * Applies all settings from [settings] in a single carrier config override call and,
     * once that write is confirmed, restarts IMS registration. The returned future completes
     * with the write's real outcome; [AutoApplyWorker] retries on failure.
     */
    fun applyAllSettings(settings: SubscriptionSettings): Future<BrokerResult> {
        Log.d(TAG, "applyAllSettings for subId=$subscriptionId")
        val bundle = Bundle()

        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, settings.voLTEEnabled)
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, settings.voWiFiEnabled)
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, settings.voWiFiEnabledWhileRoaming)
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, settings.vtEnabled)
        bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, settings.supportWfcWifiOnly)
        bundle.putBoolean(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, settings.showVoWifiIcon)
        bundle.putBoolean(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL, settings.allowAddingAPNs)
        bundle.putBoolean(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL, settings.ssOverCDMAEnabled)
        bundle.putInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, settings.wfcSpnFormatIndex)

        if (settings.userAgent.isNotBlank()) {
            bundle.putString(KEY_IMS_USER_AGENT, settings.userAgent)
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
            bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, settings.voNREnabled)
            bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, settings.voNREnabled)
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            bundle.putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, nrAvailabilities(settings.nrSAEnabled))
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, settings.crossSIMEnabled)
            bundle.putBoolean(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL, settings.crossSIMEnabled)
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
            bundle.putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, settings.showIMSinSIMInfo)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, settings.showVoWifiMode)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, settings.showVoWifiRoamingMode)
            bundle.putBoolean(CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL, settings.alwaysDataRATIcon)
            bundle.putBoolean(CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL, settings.show4GForLteEnabled)
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, settings.hideEnhancedDataIconEnabled)
        }

        if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, settings.ssOverUtEnabled)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, settings.is4GPlusEnabled)
            bundle.putBoolean(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL, settings.is4GPlusEnabled)
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, !settings.is4GPlusEnabled)
        }

        return BrokerQueue.submit {
            val result = writeConfigNow(bundle)
            if (result.ok) {
                try {
                    resetImsNow()
                } catch (e: Throwable) {
                    // The config is in place; a failed reset only delays re-registration.
                    Log.e(TAG, "resetIms after apply failed", e)
                }
            }
            result
        }
    }

    /**
     * Whether the live carrier config already matches [settings].
     *
     * Used to skip a redundant apply on boot. That matters beyond saving an IPC:
     * [applyAllSettings] ends with [restartIMSRegistration], so re-applying an identical
     * config would drop and re-register IMS on every single boot for no reason.
     *
     * Returns false if anything cannot be read — better a redundant apply than a silent skip.
     */
    fun matchesSettings(settings: SubscriptionSettings): Boolean =
        try {
            val vonrMatches =
                Build.VERSION.SDK_INT < VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    isVoNrConfigEnabled == settings.voNREnabled
            val nrMatches =
                Build.VERSION.SDK_INT < VERSION_CODES.S ||
                    isNRConfigEnabled == settings.nrSAEnabled
            val crossSimMatches =
                Build.VERSION.SDK_INT < VERSION_CODES.TIRAMISU ||
                    isCrossSIMConfigEnabled == settings.crossSIMEnabled

            isVoLteConfigEnabled == settings.voLTEEnabled &&
                vonrMatches &&
                nrMatches &&
                crossSimMatches &&
                isVoWifiConfigEnabled == settings.voWiFiEnabled &&
                isVoWifiWhileRoamingEnabled == settings.voWiFiEnabledWhileRoaming &&
                isVtConfigEnabled == settings.vtEnabled &&
                supportWfcWifiOnly == settings.supportWfcWifiOnly &&
                ssOverUtEnabled == settings.ssOverUtEnabled &&
                ssOverCDMAEnabled == settings.ssOverCDMAEnabled &&
                is4GPlusEnabled == settings.is4GPlusEnabled &&
                wfcSpnFormatIndex == settings.wfcSpnFormatIndex
        } catch (e: Throwable) {
            Log.d(TAG, "matchesSettings: could not read config, assuming mismatch", e)
            false
        }

    // ── Radio state, read for the 5G diagnostics ─────────────────────────────
    // Each returns null when the platform refuses or lacks the call, so one missing
    // piece of information degrades a single check rather than the whole screen.

    /** Network types allowed for [reason] (TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_*). */
    fun allowedNetworkTypes(reason: Int): Long? =
        try {
            this.loadCachedInterface { telephony }.getAllowedNetworkTypesForReason(subscriptionId, reason)
        } catch (e: Throwable) {
            Log.w(TAG, "getAllowedNetworkTypesForReason($reason) failed", e)
            null
        }

    /** Radio access family the modem reports for this SIM's slot (network-type bitmask). */
    val radioAccessFamily: Int?
        get() =
            try {
                this.loadCachedInterface { telephony }.getRadioAccessFamily(simSlotIndex, SHELL_PACKAGE)
            } catch (e: Throwable) {
                Log.w(TAG, "getRadioAccessFamily failed", e)
                null
            }

    /**
     * Current service state. Asked for with location included, because the serving cell's
     * band lives in the cell identity; falls back to the location-free state if refused.
     */
    val serviceState: ServiceState?
        get() {
            val telephony = this.loadCachedInterface { telephony }
            val slot = simSlotIndex
            return try {
                telephony.getServiceStateForSlot(slot, false, false, SHELL_PACKAGE, null)
            } catch (e: Throwable) {
                try {
                    telephony.getServiceStateForSlot(slot, true, true, SHELL_PACKAGE, null)
                } catch (e2: Throwable) {
                    Log.w(TAG, "getServiceStateForSlot failed", e2)
                    null
                }
            }
        }

    val isDefaultDataSubscription: Boolean?
        get() =
            try {
                this.loadCachedInterface { sub }.defaultDataSubId == subscriptionId
            } catch (e: Throwable) {
                null
            }

    /** ImsRegistrationImplBase.REGISTRATION_TECH_*, or null when unknown. */
    val imsRegistrationTech: Int?
        get() =
            try {
                this.loadCachedInterface { telephony }.getImsRegTechnologyForMmTel(subscriptionId)
            } catch (e: Throwable) {
                null
            }

    // ── Persistent VoLTE (VoIMS opt-in) ──────────────────────────────────────
    //
    // Carrier config overrides are held in memory and vanish on reboot. The VoIMS opt-in
    // flag is not: it is a column of the subscription database, and
    // ImsManager.isVolteEnabledByPlatform() returns true for it before it even looks at
    // carrier_volte_available_bool. So with it set, VoLTE is back as soon as the phone
    // boots, without waiting for Shizuku. It covers VoLTE only; VoNR, VoWiFi and 5G SA
    // still come from the carrier config overrides.

    private fun rawSubscriptionProperty(column: String): Int? =
        this
            .loadCachedInterface { sub }
            .getSubscriptionProperty(subscriptionId, column, SHELL_PACKAGE, null)
            ?.toIntOrNull()

    private fun writeSubscriptionProperty(
        column: String,
        value: Int,
    ) {
        this.loadCachedInterface { sub }.setSubscriptionProperty(subscriptionId, column, value.toString())
    }

    private fun writeVoImsOptIn(enabled: Boolean) {
        val value = if (enabled) ProvisioningManager.PROVISIONING_VALUE_ENABLED else ProvisioningManager.PROVISIONING_VALUE_DISABLED
        val status =
            this
                .loadCachedInterface { telephony }
                .setImsProvisioningInt(subscriptionId, ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS, value)
        // ImsConfigImplBase.CONFIG_RESULT_SUCCESS; returning without an exception is not enough.
        check(status == 0) { "setImsProvisioningInt returned $status" }
    }

    /** Whether VoLTE is kept on across reboots by the VoIMS opt-in flag. */
    val isVoImsOptInEnabled: Boolean
        @RequiresApi(VERSION_CODES.S)
        get() =
            this
                .loadCachedInterface { telephony }
                .getImsProvisioningInt(subscriptionId, ProvisioningManager.KEY_VOIMS_OPT_IN_STATUS) ==
                ProvisioningManager.PROVISIONING_VALUE_ENABLED

    /** SHA-256 of the ICCID, so a saved backup is never restored onto a different SIM. */
    private fun simIdentity(): String {
        val info =
            this.loadCachedInterface { sub }.getActiveSubscriptionInfo(subscriptionId, SHELL_PACKAGE, null)
                ?: error("subscription $subscriptionId is not active")
        val iccId = info.iccId?.takeIf { it.isNotBlank() } ?: error("ICCID unavailable")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(iccId.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Turns persistent VoLTE on or off for this SIM.
     *
     * On: remembers the SIM's original opt-in and "4G calling" values (once, per ICCID),
     * sets the opt-in and switches the user-facing VoLTE setting on — the opt-in alone
     * leaves VoLTE off if that switch is off.
     * Off: clears the opt-in (back to "follow the carrier" if that is how it started) and
     * restores the original "4G calling" switch.
     */
    fun setPersistentVoLTE(
        enabled: Boolean,
        repo: SettingsRepository,
    ): Future<BrokerResult> =
        BrokerQueue.submit {
            try {
                val identity = simIdentity()
                if (enabled) {
                    if (repo.loadOptInBackup(identity) == null) {
                        repo.saveOptInBackup(
                            identity,
                            OptInBackup(
                                optIn = rawSubscriptionProperty(COLUMN_VOIMS_OPT_IN) ?: -1,
                                enhanced4gMode = rawSubscriptionProperty(COLUMN_ENHANCED_4G_MODE) ?: -1,
                            ),
                        )
                    }
                    writeVoImsOptIn(true)
                    this.loadCachedInterface { telephony }.setAdvancedCallingSettingEnabled(subscriptionId, true)
                } else {
                    val backup = repo.loadOptInBackup(identity)
                    writeVoImsOptIn(false)
                    // The provisioning call only takes 0/1; "-1, follow the carrier" has to
                    // be written straight to the subscription database afterwards.
                    if (backup?.optIn == -1) writeSubscriptionProperty(COLUMN_VOIMS_OPT_IN, -1)
                    backup?.let { writeSubscriptionProperty(COLUMN_ENHANCED_4G_MODE, it.enhanced4gMode) }
                    repo.clearOptInBackup(identity)
                }
                check(isVoImsOptInEnabled == enabled) { "VoIMS opt-in did not change" }
                BrokerResult.OK
            } catch (e: Throwable) {
                Log.e(TAG, "setPersistentVoLTE($enabled) failed", e)
                BrokerResult(false, "${e.javaClass.simpleName}: ${e.message}")
            }.also { toastIfFailed(it) }
        }

    /**
     * Puts the opt-in and "4G calling" values back exactly as they were before this app
     * first touched them. Does nothing for a SIM we never changed, so it never undoes an
     * opt-in another tool (or the carrier) set.
     */
    fun restorePersistentVoLTE(repo: SettingsRepository): Future<BrokerResult> =
        BrokerQueue.submit {
            try {
                val identity = simIdentity()
                val backup = repo.loadOptInBackup(identity) ?: return@submit BrokerResult.OK
                writeVoImsOptIn(backup.optIn == ProvisioningManager.PROVISIONING_VALUE_ENABLED)
                if (backup.optIn == -1) writeSubscriptionProperty(COLUMN_VOIMS_OPT_IN, -1)
                writeSubscriptionProperty(COLUMN_ENHANCED_4G_MODE, backup.enhanced4gMode)
                repo.clearOptInBackup(identity)
                BrokerResult.OK
            } catch (e: Throwable) {
                Log.e(TAG, "restorePersistentVoLTE failed", e)
                BrokerResult(false, "${e.javaClass.simpleName}: ${e.message}")
            }.also { toastIfFailed(it) }
        }

    fun getStringValue(key: String): String? {
        Log.d(TAG, "Resolving string value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return ""
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getString(key)
    }

    fun getBooleanValue(key: String): Boolean {
        Log.d(TAG, "Resolving boolean value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return false
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getBoolean(key)
    }

    fun getIntValue(key: String): Int {
        Log.d(TAG, "Resolving integer value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return -1
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getInt(key)
    }

    fun getLongValue(key: String): Long {
        Log.d(TAG, "Resolving long value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return -1
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getLong(key)
    }

    fun getBooleanArrayValue(key: String): BooleanArray {
        Log.d(TAG, "Resolving boolean array value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return booleanArrayOf()
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getBooleanArray(key) ?: BooleanArray(0)
    }

    fun getIntArrayValue(key: String): IntArray {
        Log.d(TAG, "Resolving integer value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return intArrayOf()
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getIntArray(key) ?: IntArray(0)
    }

    fun getStringArrayValue(key: String): Array<String> {
        Log.d(TAG, "Resolving string array value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return arrayOf()
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.getStringArray(key) ?: emptyArray()
    }

    fun getValue(key: String): Any? {
        Log.d(TAG, "Resolving value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return null
        }
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }

        val config = iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        return config.get(key)
    }

    val simSlotIndex: Int
        get() = this.loadCachedInterface { sub }.getSlotIndex(subscriptionId)

    val isVoLteConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)

    val isVoNrConfigEnabled: Boolean
        @RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() =
            this.getBooleanValue(CarrierConfigManager.KEY_VONR_ENABLED_BOOL) &&
                this.getBooleanValue(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL)

    val isCrossSIMConfigEnabled: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL)
            } else {
                false
            }
        }

    val isVoWifiConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)

    val isVoWifiWhileRoamingEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL)

    val showIMSinSIMInfo: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL)

    val allowAddingAPNs: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL)

    val showVoWifiMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL)

    val showVoWifiRoamingMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL)

    val wfcSpnFormatIndex: Int
        get() = this.getIntValue(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT)

    val carrierName: String
        get() = this.loadCachedInterface { telephony }.getSubscriptionCarrierName(this.subscriptionId)

    val showVoWifiIcon: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL)

    val alwaysDataRATIcon: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL)

    val supportWfcWifiOnly: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL)

    val isVtConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL)

    val ssOverUtEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL)
            } else {
                false
            }

    val ssOverCDMAEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL)

    val isShow4GForLteEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL)

    val isHideEnhancedDataIconEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL)

    val is4GPlusEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
                this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL) &&
                    !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
            } else {
                this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
                    !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
            }

    /**
     * Whether 5G SA (standalone) is unlocked for this SIM.
     *
     * Pixel carrier configs frequently ship `[NSA]` only, which keeps the modem off any
     * standalone network no matter what the user picks in Settings. Russian operators
     * run their n79 (4.6-5.0 GHz) layer as SA, so SA has to be in the list for it to
     * attach at all.
     */
    val isNRConfigEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                this
                    .getIntArrayValue(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
                    .contains(CARRIER_NR_AVAILABILITY_SA)
            } else {
                false
            }

    val userAgentConfig: String
        get() = this.getStringValue(KEY_IMS_USER_AGENT) ?: ""

    val isIMSRegistered: Boolean
        get() {
            val telephony = this.loadCachedInterface { telephony }
            return telephony.isImsRegistered(this.subscriptionId)
        }
}
