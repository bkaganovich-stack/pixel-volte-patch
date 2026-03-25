package dev.bluehouse.enablevolte

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistent storage for per-SIM carrier configuration settings.
 * Settings are keyed by SIM slot index (0 or 1), which is stable across reboots
 * unlike subscription IDs that may change.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "pixel_ims_settings"
        private const val KEY_AUTO_APPLY_ENABLED = "auto_apply_on_reboot"
        private const val EXPORT_VERSION = "1.3.3"

        private fun slotKey(slotIndex: Int, key: String) = "slot_${slotIndex}_$key"
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether to automatically re-apply settings after device reboot. */
    var autoApplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPLY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_APPLY_ENABLED, value).apply()

    /** Returns true if there are saved settings for the given SIM slot. */
    fun hasSettings(slotIndex: Int): Boolean =
        prefs.getBoolean(slotKey(slotIndex, "configured"), false)

    /** Saves all carrier config settings for a SIM slot. */
    fun saveSlotSettings(slotIndex: Int, settings: SubscriptionSettings) {
        prefs.edit().apply {
            putBoolean(slotKey(slotIndex, "configured"), true)
            putBoolean(slotKey(slotIndex, "volte"), settings.voLTEEnabled)
            putBoolean(slotKey(slotIndex, "vonr"), settings.voNREnabled)
            putBoolean(slotKey(slotIndex, "crosssim"), settings.crossSIMEnabled)
            putBoolean(slotKey(slotIndex, "vowifi"), settings.voWiFiEnabled)
            putBoolean(slotKey(slotIndex, "vowifi_roaming"), settings.voWiFiEnabledWhileRoaming)
            putBoolean(slotKey(slotIndex, "show_ims_sim"), settings.showIMSinSIMInfo)
            putBoolean(slotKey(slotIndex, "allow_apns"), settings.allowAddingAPNs)
            putBoolean(slotKey(slotIndex, "show_vowifi_mode"), settings.showVoWifiMode)
            putBoolean(slotKey(slotIndex, "show_vowifi_roaming_mode"), settings.showVoWifiRoamingMode)
            putInt(slotKey(slotIndex, "wfc_spn_format"), settings.wfcSpnFormatIndex)
            putBoolean(slotKey(slotIndex, "show_vowifi_icon"), settings.showVoWifiIcon)
            putBoolean(slotKey(slotIndex, "always_data_rat"), settings.alwaysDataRATIcon)
            putBoolean(slotKey(slotIndex, "wfc_wifi_only"), settings.supportWfcWifiOnly)
            putBoolean(slotKey(slotIndex, "vt"), settings.vtEnabled)
            putBoolean(slotKey(slotIndex, "ss_over_ut"), settings.ssOverUtEnabled)
            putBoolean(slotKey(slotIndex, "ss_over_cdma"), settings.ssOverCDMAEnabled)
            putBoolean(slotKey(slotIndex, "show_4g_lte"), settings.show4GForLteEnabled)
            putBoolean(slotKey(slotIndex, "hide_enhanced"), settings.hideEnhancedDataIconEnabled)
            putBoolean(slotKey(slotIndex, "4g_plus"), settings.is4GPlusEnabled)
            putString(slotKey(slotIndex, "user_agent"), settings.userAgent)
            apply()
        }
    }

    /** Loads saved settings for a SIM slot, or null if none saved yet. */
    fun loadSlotSettings(slotIndex: Int): SubscriptionSettings? {
        if (!hasSettings(slotIndex)) return null
        return SubscriptionSettings(
            voLTEEnabled = prefs.getBoolean(slotKey(slotIndex, "volte"), false),
            voNREnabled = prefs.getBoolean(slotKey(slotIndex, "vonr"), false),
            crossSIMEnabled = prefs.getBoolean(slotKey(slotIndex, "crosssim"), false),
            voWiFiEnabled = prefs.getBoolean(slotKey(slotIndex, "vowifi"), false),
            voWiFiEnabledWhileRoaming = prefs.getBoolean(slotKey(slotIndex, "vowifi_roaming"), false),
            showIMSinSIMInfo = prefs.getBoolean(slotKey(slotIndex, "show_ims_sim"), false),
            allowAddingAPNs = prefs.getBoolean(slotKey(slotIndex, "allow_apns"), false),
            showVoWifiMode = prefs.getBoolean(slotKey(slotIndex, "show_vowifi_mode"), false),
            showVoWifiRoamingMode = prefs.getBoolean(slotKey(slotIndex, "show_vowifi_roaming_mode"), false),
            wfcSpnFormatIndex = prefs.getInt(slotKey(slotIndex, "wfc_spn_format"), 0),
            showVoWifiIcon = prefs.getBoolean(slotKey(slotIndex, "show_vowifi_icon"), false),
            alwaysDataRATIcon = prefs.getBoolean(slotKey(slotIndex, "always_data_rat"), false),
            supportWfcWifiOnly = prefs.getBoolean(slotKey(slotIndex, "wfc_wifi_only"), false),
            vtEnabled = prefs.getBoolean(slotKey(slotIndex, "vt"), false),
            ssOverUtEnabled = prefs.getBoolean(slotKey(slotIndex, "ss_over_ut"), false),
            ssOverCDMAEnabled = prefs.getBoolean(slotKey(slotIndex, "ss_over_cdma"), false),
            show4GForLteEnabled = prefs.getBoolean(slotKey(slotIndex, "show_4g_lte"), false),
            hideEnhancedDataIconEnabled = prefs.getBoolean(slotKey(slotIndex, "hide_enhanced"), false),
            is4GPlusEnabled = prefs.getBoolean(slotKey(slotIndex, "4g_plus"), false),
            userAgent = prefs.getString(slotKey(slotIndex, "user_agent"), "") ?: "",
        )
    }

    /**
     * Exports all saved settings to a JSON string.
     * Settings are stored by slot index so they can be imported on a different device.
     */
    fun exportToJson(): String {
        val root = JSONObject()
        root.put("version", EXPORT_VERSION)
        root.put("app", "pixel-ims")
        val slots = JSONArray()
        for (slotIndex in 0..1) {
            val settings = loadSlotSettings(slotIndex) ?: continue
            val obj = JSONObject()
            obj.put("slotIndex", slotIndex)
            obj.put("voLTEEnabled", settings.voLTEEnabled)
            obj.put("voNREnabled", settings.voNREnabled)
            obj.put("crossSIMEnabled", settings.crossSIMEnabled)
            obj.put("voWiFiEnabled", settings.voWiFiEnabled)
            obj.put("voWiFiEnabledWhileRoaming", settings.voWiFiEnabledWhileRoaming)
            obj.put("showIMSinSIMInfo", settings.showIMSinSIMInfo)
            obj.put("allowAddingAPNs", settings.allowAddingAPNs)
            obj.put("showVoWifiMode", settings.showVoWifiMode)
            obj.put("showVoWifiRoamingMode", settings.showVoWifiRoamingMode)
            obj.put("wfcSpnFormatIndex", settings.wfcSpnFormatIndex)
            obj.put("showVoWifiIcon", settings.showVoWifiIcon)
            obj.put("alwaysDataRATIcon", settings.alwaysDataRATIcon)
            obj.put("supportWfcWifiOnly", settings.supportWfcWifiOnly)
            obj.put("vtEnabled", settings.vtEnabled)
            obj.put("ssOverUtEnabled", settings.ssOverUtEnabled)
            obj.put("ssOverCDMAEnabled", settings.ssOverCDMAEnabled)
            obj.put("show4GForLteEnabled", settings.show4GForLteEnabled)
            obj.put("hideEnhancedDataIconEnabled", settings.hideEnhancedDataIconEnabled)
            obj.put("is4GPlusEnabled", settings.is4GPlusEnabled)
            obj.put("userAgent", settings.userAgent)
            slots.put(obj)
        }
        root.put("slots", slots)
        return root.toString(2)
    }

    /**
     * Imports settings from a JSON string previously exported by [exportToJson].
     * @throws JSONException if the JSON is malformed or missing required fields.
     */
    fun importFromJson(json: String) {
        val root = JSONObject(json)
        if (root.optString("app") != "pixel-ims") {
            throw JSONException("Not a Pixel IMS settings file")
        }
        val slots = root.getJSONArray("slots")
        for (i in 0 until slots.length()) {
            val obj = slots.getJSONObject(i)
            val slotIndex = obj.getInt("slotIndex")
            val settings = SubscriptionSettings(
                voLTEEnabled = obj.getBoolean("voLTEEnabled"),
                voNREnabled = obj.getBoolean("voNREnabled"),
                crossSIMEnabled = obj.getBoolean("crossSIMEnabled"),
                voWiFiEnabled = obj.getBoolean("voWiFiEnabled"),
                voWiFiEnabledWhileRoaming = obj.getBoolean("voWiFiEnabledWhileRoaming"),
                showIMSinSIMInfo = obj.getBoolean("showIMSinSIMInfo"),
                allowAddingAPNs = obj.getBoolean("allowAddingAPNs"),
                showVoWifiMode = obj.getBoolean("showVoWifiMode"),
                showVoWifiRoamingMode = obj.getBoolean("showVoWifiRoamingMode"),
                wfcSpnFormatIndex = obj.getInt("wfcSpnFormatIndex"),
                showVoWifiIcon = obj.getBoolean("showVoWifiIcon"),
                alwaysDataRATIcon = obj.getBoolean("alwaysDataRATIcon"),
                supportWfcWifiOnly = obj.getBoolean("supportWfcWifiOnly"),
                vtEnabled = obj.getBoolean("vtEnabled"),
                ssOverUtEnabled = obj.getBoolean("ssOverUtEnabled"),
                ssOverCDMAEnabled = obj.getBoolean("ssOverCDMAEnabled"),
                show4GForLteEnabled = obj.getBoolean("show4GForLteEnabled"),
                hideEnhancedDataIconEnabled = obj.getBoolean("hideEnhancedDataIconEnabled"),
                is4GPlusEnabled = obj.getBoolean("is4GPlusEnabled"),
                userAgent = obj.optString("userAgent", ""),
            )
            saveSlotSettings(slotIndex, settings)
        }
    }
}

/** Snapshot of all configurable carrier settings for one SIM subscription. */
data class SubscriptionSettings(
    val voLTEEnabled: Boolean,
    val voNREnabled: Boolean,
    val crossSIMEnabled: Boolean,
    val voWiFiEnabled: Boolean,
    val voWiFiEnabledWhileRoaming: Boolean,
    val showIMSinSIMInfo: Boolean,
    val allowAddingAPNs: Boolean,
    val showVoWifiMode: Boolean,
    val showVoWifiRoamingMode: Boolean,
    val wfcSpnFormatIndex: Int,
    val showVoWifiIcon: Boolean,
    val alwaysDataRATIcon: Boolean,
    val supportWfcWifiOnly: Boolean,
    val vtEnabled: Boolean,
    val ssOverUtEnabled: Boolean,
    val ssOverCDMAEnabled: Boolean,
    val show4GForLteEnabled: Boolean,
    val hideEnhancedDataIconEnabled: Boolean,
    val is4GPlusEnabled: Boolean,
    val userAgent: String,
)
