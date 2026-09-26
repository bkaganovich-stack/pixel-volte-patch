package dev.bluehouse.enablevolte.pages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bluehouse.enablevolte.BrokerQueue
import dev.bluehouse.enablevolte.CheckStatus
import dev.bluehouse.enablevolte.DiagnosticCheck
import dev.bluehouse.enablevolte.DiagnosticFix
import dev.bluehouse.enablevolte.FiveGDiagnostics
import dev.bluehouse.enablevolte.R
import dev.bluehouse.enablevolte.SettingsRepository
import dev.bluehouse.enablevolte.SubscriptionModer
import dev.bluehouse.enablevolte.components.HeaderText
import dev.bluehouse.enablevolte.components.InfiniteLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("ktlint:standard:function-naming")
@Composable
fun Diagnostics(subId: Int) {
    val context = LocalContext.current
    val moder = remember { SubscriptionModer(context, subId) }
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<DiagnosticCheck>?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        if (VERSION.SDK_INT < VERSION_CODES.S) {
            checks = emptyList()
            return
        }
        checks = withContext(Dispatchers.IO) { FiveGDiagnostics.run(context, moder) }
    }

    fun apply(fix: DiagnosticFix) {
        when (fix) {
            DiagnosticFix.ENABLE_SA ->
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) {
                        moder.updateNRAvailabilities(true)
                        // Keep the saved settings in step, or auto-apply would undo this.
                        val slot = moder.simSlotIndex
                        repo.loadSlotSettings(slot)?.let { repo.saveSlotSettings(slot, it.copy(nrSAEnabled = true)) }
                        // updateNRAvailabilities is queued; wait for the queue to drain so the
                        // re-read below sees the new value.
                        BrokerQueue.submit {}.get()
                    }
                    refresh()
                    busy = false
                }
            DiagnosticFix.RESTART_IMS ->
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) { moder.restartIMSRegistration().get() }
                    refresh()
                    busy = false
                }
            DiagnosticFix.OPEN_NETWORK_SETTINGS ->
                openSettings(context, Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).putExtra(Settings.EXTRA_SUB_ID, subId))
            DiagnosticFix.OPEN_SIM_SETTINGS ->
                openSettings(context, Intent(ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS))
        }
    }

    LaunchedEffect(subId) { refresh() }

    if (checks == null || busy) {
        InfiniteLoadingDialog()
    }
    val current = checks ?: return

    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        if (current.isEmpty()) {
            Text(stringResource(R.string.diag_unsupported_android))
            return@Column
        }

        HeaderText(text = stringResource(R.string.diag_verdict_header))
        val blocker = FiveGDiagnostics.blocker(current)
        Text(
            text =
                if (blocker != null) {
                    stringResource(R.string.diag_verdict_blocked, blocker.title)
                } else {
                    stringResource(R.string.diag_verdict_clear)
                },
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        HeaderText(text = stringResource(R.string.diag_checks_header))
        for (check in current) {
            CheckRow(check) { apply(it) }
        }

        OutlinedButton(
            onClick = { scope.launch { refresh() } },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.diag_refresh))
        }
    }
}

@Composable
private fun CheckRow(
    check: DiagnosticCheck,
    onFix: (DiagnosticFix) -> Unit,
) {
    val (symbol, color) =
        when (check.status) {
            CheckStatus.PASS -> "✓" to Color(0xFF2E7D32)
            CheckStatus.WARN -> "!" to Color(0xFFB26A00)
            CheckStatus.FAIL -> "✕" to MaterialTheme.colorScheme.error
            CheckStatus.UNKNOWN -> "?" to MaterialTheme.colorScheme.outline
        }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = symbol,
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = check.title, fontSize = 17.sp)
            Text(text = check.detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
            check.fix?.takeIf { check.status == CheckStatus.FAIL || check.status == CheckStatus.WARN }?.let { fix ->
                TextButton(onClick = { onFix(fix) }, modifier = Modifier.padding(top = 2.dp)) {
                    Text(stringResource(fixLabel(fix)))
                }
            }
        }
    }
}

private fun fixLabel(fix: DiagnosticFix): Int =
    when (fix) {
        DiagnosticFix.ENABLE_SA -> R.string.diag_fix_enable_sa
        DiagnosticFix.OPEN_NETWORK_SETTINGS -> R.string.diag_fix_network_settings
        DiagnosticFix.OPEN_SIM_SETTINGS -> R.string.diag_fix_sim_settings
        DiagnosticFix.RESTART_IMS -> R.string.restart_ims_registration
    }

/** Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS, spelled out so it resolves on every SDK. */
private const val ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS = "android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"

/** Opens a Settings screen, falling back to the top-level network page if it is missing. */
private fun openSettings(
    context: Context,
    intent: Intent,
) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
