package dev.bluehouse.enablevolte.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.telephony.SubscriptionInfo
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import dev.bluehouse.enablevolte.BootLog
import dev.bluehouse.enablevolte.BuildConfig
import dev.bluehouse.enablevolte.CarrierModer
import dev.bluehouse.enablevolte.R
import dev.bluehouse.enablevolte.SettingsRepository
import dev.bluehouse.enablevolte.ShizukuStatus
import dev.bluehouse.enablevolte.SubscriptionModer
import dev.bluehouse.enablevolte.SubscriptionSettings
import dev.bluehouse.enablevolte.checkShizukuPermission
import dev.bluehouse.enablevolte.components.BooleanPropertyView
import dev.bluehouse.enablevolte.components.ClickablePropertyView
import dev.bluehouse.enablevolte.components.HeaderText
import dev.bluehouse.enablevolte.components.StringPropertyView
import dev.bluehouse.enablevolte.getLatestAppVersion
import dev.bluehouse.enablevolte.uniqueName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.swiftzer.semver.SemVer
import org.json.JSONException
import rikka.shizuku.Shizuku

const val TAG = "HomeActivity:Home"

@Suppress("ktlint:standard:function-naming")
@Composable
fun Home(navController: NavController) {
    val carrierModer = CarrierModer(LocalContext.current)
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val repo = SettingsRepository(context)
    val scope = rememberCoroutineScope()

    var shizukuEnabled by rememberSaveable { mutableStateOf(false) }
    var shizukuGranted by rememberSaveable { mutableStateOf(false) }
    var subscriptions by rememberSaveable { mutableStateOf(listOf<SubscriptionInfo>()) }
    var deviceIMSEnabled by rememberSaveable { mutableStateOf(false) }
    var autoApplyEnabled by rememberSaveable { mutableStateOf(repo.autoApplyEnabled) }
    var bootLogText by rememberSaveable { mutableStateOf(BootLog.read(context)) }

    var isIMSRegistered by rememberSaveable { mutableStateOf(listOf<Boolean>()) }
    var newerVersion by rememberSaveable { mutableStateOf("") }

    fun loadFlags() {
        shizukuGranted = true
        subscriptions = carrierModer.subscriptions
        deviceIMSEnabled = carrierModer.deviceSupportsIMS

        if (subscriptions.isNotEmpty() && deviceIMSEnabled) {
            isIMSRegistered = subscriptions.map { SubscriptionModer(context, it.subscriptionId).isIMSRegistered }
        }
    }

    // ── Reads current carrier config for every subscription via Shizuku
    //    and persists it to SharedPreferences so exportToJson() is always up-to-date.
    //    Must be called on a background thread (Shizuku IPC).
    fun saveAllSubscriptionSettings() {
        for (subscription in subscriptions) {
            try {
                val moder = SubscriptionModer(context, subscription.subscriptionId)
                val slot = try { moder.simSlotIndex } catch (e: Exception) { -1 }
                if (slot < 0) continue
                repo.saveSlotSettings(
                    slot,
                    SubscriptionSettings(
                        voLTEEnabled = moder.isVoLteConfigEnabled,
                        voNREnabled = VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE && moder.isVoNrConfigEnabled,
                        crossSIMEnabled = moder.isCrossSIMConfigEnabled,
                        voWiFiEnabled = moder.isVoWifiConfigEnabled,
                        voWiFiEnabledWhileRoaming = moder.isVoWifiWhileRoamingEnabled,
                        showIMSinSIMInfo = VERSION.SDK_INT >= VERSION_CODES.R && moder.showIMSinSIMInfo,
                        allowAddingAPNs = moder.allowAddingAPNs,
                        showVoWifiMode = VERSION.SDK_INT >= VERSION_CODES.R && moder.showVoWifiMode,
                        showVoWifiRoamingMode = VERSION.SDK_INT >= VERSION_CODES.R && moder.showVoWifiRoamingMode,
                        wfcSpnFormatIndex = moder.wfcSpnFormatIndex,
                        showVoWifiIcon = moder.showVoWifiIcon,
                        alwaysDataRATIcon = VERSION.SDK_INT >= VERSION_CODES.R && moder.alwaysDataRATIcon,
                        supportWfcWifiOnly = moder.supportWfcWifiOnly,
                        vtEnabled = moder.isVtConfigEnabled,
                        ssOverUtEnabled = moder.ssOverUtEnabled,
                        ssOverCDMAEnabled = moder.ssOverCDMAEnabled,
                        show4GForLteEnabled = VERSION.SDK_INT >= VERSION_CODES.R && moder.isShow4GForLteEnabled,
                        hideEnhancedDataIconEnabled = VERSION.SDK_INT >= VERSION_CODES.R && moder.isHideEnhancedDataIconEnabled,
                        is4GPlusEnabled = moder.is4GPlusEnabled,
                        userAgent = try { moder.userAgentConfig } catch (e: Exception) { "" } ?: "",
                    ),
                )
                Log.d(TAG, "Saved settings for slot $slot (subId=${subscription.subscriptionId})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save settings for sub ${subscription.subscriptionId}", e)
            }
        }
    }

    // ── Export: snapshot ALL SIM settings via Shizuku, then write JSON ────────
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                // Read current settings from device for all SIMs and save to prefs.
                // Done on Default dispatcher (Shizuku IPC calls).
                if (subscriptions.isNotEmpty()) {
                    withContext(Dispatchers.Default) {
                        saveAllSubscriptionSettings()
                    }
                }
                val stream = context.contentResolver.openOutputStream(uri)
                if (stream == null) {
                    Log.e(TAG, "openOutputStream returned null for $uri")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.settings_export_failed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                stream.use { it.write(repo.exportToJson().toByteArray(Charsets.UTF_8)) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.settings_exported), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.settings_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Import: parse JSON, apply settings to ALL matching SIMs ──────────────
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: return@launch
                repo.importFromJson(json)
                // Apply imported settings to every matching subscription
                withContext(Dispatchers.Default) {
                    for (subscription in subscriptions) {
                        try {
                            val moder = SubscriptionModer(context, subscription.subscriptionId)
                            val slot = try { moder.simSlotIndex } catch (e: Exception) { -1 }
                            val imported = if (slot >= 0) repo.loadSlotSettings(slot) else null
                            if (imported != null) {
                                moder.applyAllSettings(imported)
                                Log.d(TAG, "Applied imported settings for slot $slot")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to apply settings for sub ${subscription.subscriptionId}", e)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.settings_imported), Toast.LENGTH_SHORT).show()
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Import JSON parse error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.settings_import_invalid), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.settings_import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            when (checkShizukuPermission(0)) {
                ShizukuStatus.GRANTED -> {
                    shizukuEnabled = true
                    loadFlags()
                }
                ShizukuStatus.NOT_GRANTED -> {
                    shizukuEnabled = true
                    Shizuku.addRequestPermissionResultListener { _, grantResult ->
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            loadFlags()
                        }
                    }
                }
                else -> {
                    shizukuEnabled = false
                }
            }
        } catch (e: IllegalStateException) {
            shizukuEnabled = false
        }
        getLatestAppVersion {
            Log.d(TAG, "Fetched version $it")
            val latest = SemVer.parse(it)
            val current = SemVer.parse(BuildConfig.VERSION_NAME)
            if (latest > current) {
                newerVersion = it
            }
        }
    }

    Column(modifier = Modifier.padding(Dp(16f)).verticalScroll(scrollState)) {
        HeaderText(text = stringResource(R.string.version))
        if (newerVersion.isNotEmpty()) {
            ClickablePropertyView(
                label = BuildConfig.VERSION_NAME,
                value = stringResource(R.string.newer_version_available, newerVersion),
            ) {
                val url = "https://github.com/kyujin-cho/pixel-volte-patch/releases/tag/$newerVersion"
                val i = Intent(Intent.ACTION_VIEW)
                i.data = url.toUri()
                context.startActivity(i, null)
            }
        } else {
            StringPropertyView(label = BuildConfig.VERSION_NAME, value = stringResource(R.string.running_latest_version))
        }
        HeaderText(text = stringResource(R.string.permissions_capabilities))
        BooleanPropertyView(label = stringResource(R.string.shizuku_service_running), toggled = shizukuEnabled)
        BooleanPropertyView(label = stringResource(R.string.shizuku_permission_granted), toggled = shizukuGranted)
        BooleanPropertyView(label = stringResource(R.string.sim_detected), toggled = subscriptions.isNotEmpty())
        BooleanPropertyView(label = stringResource(R.string.volte_supported_by_device), toggled = deviceIMSEnabled)

        for (idx in subscriptions.indices) {
            var isRegistered = false
            if (isIMSRegistered.isNotEmpty()) {
                isRegistered = isIMSRegistered[idx]
            }
            HeaderText(text = stringResource(R.string.ims_status_for, subscriptions[idx].uniqueName))
            BooleanPropertyView(
                label = stringResource(R.string.ims_status),
                toggled = isRegistered,
                trueLabel = stringResource(R.string.registered),
                falseLabel = stringResource(R.string.unregistered),
            )
        }

        HeaderText(text = stringResource(R.string.automation))
        BooleanPropertyView(
            label = stringResource(R.string.auto_apply_on_reboot),
            toggled = autoApplyEnabled,
        ) {
            autoApplyEnabled = !autoApplyEnabled
            repo.autoApplyEnabled = autoApplyEnabled
        }
        StringPropertyView(
            label = stringResource(R.string.auto_apply_hint_label),
            value = stringResource(R.string.auto_apply_hint_value),
        )

        HeaderText(text = stringResource(R.string.settings_file_section))
        ClickablePropertyView(
            label = stringResource(R.string.export_settings),
            value = stringResource(R.string.export_settings_description),
        ) {
            exportLauncher.launch("pixel_ims_settings.json")
        }
        ClickablePropertyView(
            label = stringResource(R.string.import_settings),
            value = stringResource(R.string.import_settings_description),
        ) {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        HeaderText(text = stringResource(R.string.boot_log_section))
        // Toolbar: Refresh / Copy / Clear
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { bootLogText = BootLog.read(context) }) {
                Text(stringResource(R.string.boot_log_refresh))
            }
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("boot_log", bootLogText))
                Toast.makeText(context, context.getString(R.string.boot_log_copied), Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.boot_log_copy))
            }
            TextButton(onClick = {
                BootLog.clear(context)
                bootLogText = BootLog.read(context)
                Toast.makeText(context, context.getString(R.string.boot_log_cleared), Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.boot_log_clear))
            }
        }
        // Scrollable monospace log text
        val hScroll = rememberScrollState()
        Text(
            text = bootLogText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dp(4f))
                .horizontalScroll(hScroll),
        )
    }
}
