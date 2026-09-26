package dev.bluehouse.enablevolte

import android.content.Context
import android.os.Build
import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.TelephonyManager
import android.telephony.ims.stub.ImsRegistrationImplBase
import androidx.annotation.RequiresApi

enum class CheckStatus { PASS, WARN, FAIL, UNKNOWN }

/** What the diagnostics screen can do about a failing check. */
enum class DiagnosticFix { ENABLE_SA, OPEN_NETWORK_SETTINGS, OPEN_SIM_SETTINGS, RESTART_IMS }

data class DiagnosticCheck(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val fix: DiagnosticFix? = null,
)

/**
 * Answers "why is there no 5G on this SIM?" by walking the conditions in the order they
 * gate each other: the modem has to support NR, the carrier config has to allow the mode,
 * Android's network-type policy has to allow NR, and only then does the actual registration
 * say anything about coverage. The first failing check is the one to fix.
 *
 * Russian networks run 5G as SA on n79, so SA in the carrier config is the check that
 * matters most there; NSA-only configs are what Pixel ships for most carriers.
 */
@RequiresApi(Build.VERSION_CODES.S)
object FiveGDiagnostics {
    private const val NR = TelephonyManager.NETWORK_TYPE_BITMASK_NR

    fun run(
        context: Context,
        moder: SubscriptionModer,
    ): List<DiagnosticCheck> =
        listOf(
            deviceSupport(context, moder),
            carrierConfig(context, moder),
            userNetworkMode(context, moder),
            otherRestrictions(context, moder),
            dataSim(context, moder),
            registration(context, moder),
            ims(context, moder),
        )

    /** The first check that blocks 5G, or null when nothing does. */
    fun blocker(checks: List<DiagnosticCheck>): DiagnosticCheck? = checks.firstOrNull { it.status == CheckStatus.FAIL }

    private fun deviceSupport(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_device_title)
        val raf = moder.radioAccessFamily ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        return if (raf.toLong() and NR != 0L) {
            DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_device_ok))
        } else {
            DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_device_fail))
        }
    }

    private fun carrierConfig(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_carrier_title)
        val modes =
            try {
                moder.getIntArrayValue(android.telephony.CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
            } catch (e: Throwable) {
                return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
            }
        val names =
            modes.joinToString(" + ") {
                when (it) {
                    CARRIER_NR_AVAILABILITY_NSA -> "NSA"
                    CARRIER_NR_AVAILABILITY_SA -> "SA"
                    else -> it.toString()
                }
            }.ifEmpty { context.getString(R.string.diag_none) }
        return when {
            CARRIER_NR_AVAILABILITY_SA in modes ->
                DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_carrier_ok, names))
            CARRIER_NR_AVAILABILITY_NSA in modes ->
                DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_carrier_nsa_only, names), DiagnosticFix.ENABLE_SA)
            else ->
                DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_carrier_none), DiagnosticFix.ENABLE_SA)
        }
    }

    private fun userNetworkMode(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_user_mode_title)
        val allowed =
            moder.allowedNetworkTypes(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
                ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        return if (allowed and NR != 0L) {
            DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_user_mode_ok))
        } else {
            DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_user_mode_fail), DiagnosticFix.OPEN_NETWORK_SETTINGS)
        }
    }

    private fun otherRestrictions(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_restrictions_title)
        val reasons =
            listOf(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER to R.string.diag_reason_carrier,
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER to R.string.diag_reason_power,
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G to R.string.diag_reason_2g,
            )
        val blocking =
            reasons.mapNotNull { (reason, label) ->
                val allowed = moder.allowedNetworkTypes(reason) ?: return@mapNotNull null
                if (allowed and NR == 0L) context.getString(label) else null
            }
        return if (blocking.isEmpty()) {
            DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_restrictions_ok))
        } else {
            DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_restrictions_fail, blocking.joinToString()))
        }
    }

    private fun dataSim(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_data_sim_title)
        return when (moder.isDefaultDataSubscription) {
            true -> DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_data_sim_ok))
            // A warning, not a blocker: some modems keep NR on the other SIM too.
            false -> DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_data_sim_warn), DiagnosticFix.OPEN_SIM_SETTINGS)
            null -> DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        }
    }

    private fun registration(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_registration_title)
        val state =
            moder.serviceState
                ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        val cellular =
            state.networkRegistrationInfoList.filter {
                it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN && it.isRegistered
            }
        val nr = cellular.firstOrNull { it.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_NR }
        if (nr != null) {
            return DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_registration_sa, bandOf(nr)))
        }
        val lte = cellular.firstOrNull { it.accessNetworkTechnology == TelephonyManager.NETWORK_TYPE_LTE }
        if (lte != null) {
            return when (lte.nrState) {
                NetworkRegistrationInfo.NR_STATE_CONNECTED ->
                    DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_registration_nsa, bandOf(lte)))
                NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED ->
                    DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_registration_nsa_idle, bandOf(lte)))
                // Everything above passed and we are still on LTE: no 5G cell here, or the
                // network does not let this SIM on it. Not something a setting can fix.
                else -> DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_registration_lte, bandOf(lte)))
            }
        }
        return if (cellular.isEmpty()) {
            DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_registration_none))
        } else {
            val tech = TelephonyManager.getNetworkTypeName(cellular.first().accessNetworkTechnology)
            DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_registration_other, tech))
        }
    }

    /** " (band n79)" when the cell identity is readable, empty otherwise. */
    private fun bandOf(info: NetworkRegistrationInfo): String {
        val bands =
            when (val cell = info.cellIdentity) {
                is CellIdentityNr -> cell.bands.map { "n$it" }
                is CellIdentityLte -> cell.bands.map { "B$it" }
                else -> emptyList()
            }
        return if (bands.isEmpty()) "" else " (${bands.joinToString("/")})"
    }

    private fun ims(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_ims_title)
        val registered =
            try {
                moder.isIMSRegistered
            } catch (e: Throwable) {
                return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
            }
        if (!registered) {
            return DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_ims_fail), DiagnosticFix.RESTART_IMS)
        }
        val over =
            when (moder.imsRegistrationTech) {
                ImsRegistrationImplBase.REGISTRATION_TECH_NR -> "5G (VoNR)"
                ImsRegistrationImplBase.REGISTRATION_TECH_LTE -> "LTE (VoLTE)"
                ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN -> "Wi-Fi (VoWiFi)"
                ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM -> "Backup calling"
                else -> "?"
            }
        return DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_ims_ok, over))
    }
}
