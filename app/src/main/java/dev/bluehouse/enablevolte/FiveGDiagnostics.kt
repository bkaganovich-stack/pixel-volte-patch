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
enum class DiagnosticFix { ENABLE_NR, OPEN_NETWORK_SETTINGS, OPEN_SIM_SETTINGS, RESTART_IMS }

data class DiagnosticCheck(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val fix: DiagnosticFix? = null,
)

/**
 * Answers "why is there no 5G on this SIM?" by walking the conditions in the order they
 * gate each other: the modem has to support NR, the carrier config has to allow the mode,
 * Android's network-type policy has to allow NR, and only then does the network side say
 * anything: whether the serving LTE cell can anchor 5G, and whether 5G was actually added.
 * The first failing check is the one to fix.
 *
 * Most networks, including the Russian ones launched in September 2026, run 5G as NSA on
 * an LTE anchor, so NSA is the mode that has to be allowed; SA is optional.
 *
 * The anchor check is what tells the network and the phone apart: if the cell offers 5G,
 * 5G is not restricted for this SIM, and 5G still never gets added while data flows, the
 * block is in the modem's own carrier profile, which no carrier config override reaches.
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
            anchorCell(context, moder),
            registration(context, moder),
            ims(context, moder),
        )

    /**
     * A warning among the phone-side checks (everything before the registration result),
     * e.g. this not being the data SIM: nothing blocks 5G outright, but it may still not
     * be used.
     */
    fun hasSetupWarnings(checks: List<DiagnosticCheck>): Boolean = checks.take(SETUP_CHECKS).any { it.status == CheckStatus.WARN }

    /** Checks in [run] that describe the phone setup; the rest report what the network did. */
    private const val SETUP_CHECKS = 5

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
            CARRIER_NR_AVAILABILITY_NSA in modes && CARRIER_NR_AVAILABILITY_SA in modes ->
                DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_carrier_ok, names))
            // NSA alone is what stock Pixel configs ship, and it is enough for NSA networks.
            CARRIER_NR_AVAILABILITY_NSA in modes ->
                DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_carrier_nsa_only, names))
            CARRIER_NR_AVAILABILITY_SA in modes ->
                DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_carrier_sa_only, names), DiagnosticFix.ENABLE_NR)
            else ->
                DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_carrier_none), DiagnosticFix.ENABLE_NR)
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

    /**
     * The cellular registrations, packet-switched first. Only the PS entry carries the
     * EN-DC indicators and the NR state; the CS entry for the same LTE cell always reads
     * NR_STATE_NONE, so picking it would hide NSA entirely.
     */
    private fun cellular(moder: SubscriptionModer): List<NetworkRegistrationInfo>? =
        moder.serviceState
            ?.networkRegistrationInfoList
            ?.filter { it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN && it.isRegistered }
            ?.sortedByDescending { it.domain and NetworkRegistrationInfo.DOMAIN_PS != 0 }

    private fun List<NetworkRegistrationInfo>.of(tech: Int) = firstOrNull { it.accessNetworkTechnology == tech }

    /**
     * Whether the LTE cell the phone is on can have 5G added on top (an EN-DC anchor) and
     * whether the network lets this SIM use it. These flags come from the cell and the
     * network, not from anything on the phone.
     */
    private fun anchorCell(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_anchor_title)
        val cellular =
            cellular(moder) ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        if (cellular.of(TelephonyManager.NETWORK_TYPE_NR) != null) {
            return DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_anchor_not_needed))
        }
        val lte =
            cellular.of(TelephonyManager.NETWORK_TYPE_LTE)
                ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_anchor_no_lte))
        val info =
            lte.dataSpecificInfo
                ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        val band = bandOf(lte)
        return when {
            !info.isEnDcAvailable && info.isNrAvailable ->
                DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_anchor_elsewhere, band))
            !info.isEnDcAvailable ->
                DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_anchor_none, band))
            info.isDcNrRestricted || !info.isNrAvailable ->
                DiagnosticCheck(title, CheckStatus.FAIL, context.getString(R.string.diag_anchor_restricted, band))
            else ->
                DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_anchor_ok, band))
        }
    }

    private fun registration(
        context: Context,
        moder: SubscriptionModer,
    ): DiagnosticCheck {
        val title = context.getString(R.string.diag_registration_title)
        val cellular =
            cellular(moder) ?: return DiagnosticCheck(title, CheckStatus.UNKNOWN, context.getString(R.string.diag_unknown_detail))
        val nr = cellular.of(TelephonyManager.NETWORK_TYPE_NR)
        if (nr != null) {
            return DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_registration_sa, bandOf(nr)))
        }
        val lte = cellular.of(TelephonyManager.NETWORK_TYPE_LTE)
        if (lte != null) {
            return when (lte.nrState) {
                NetworkRegistrationInfo.NR_STATE_CONNECTED ->
                    DiagnosticCheck(title, CheckStatus.PASS, context.getString(R.string.diag_registration_nsa, bandOf(lte)))
                // The cell offers 5G, but the network only adds it while data flows. If it
                // never does, even during a download, the modem is not using 5G here.
                NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED ->
                    DiagnosticCheck(title, CheckStatus.WARN, context.getString(R.string.diag_registration_nsa_idle, bandOf(lte)))
                // The anchor check above says why this cell gives no 5G.
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

    /**
     * " (n79)" / " (B3)" for the serving cell, empty when it cannot be read.
     *
     * The modem's band list is not just the serving band: on this Pixel it reads e.g.
     * [1, 38, 3] with the aggregated bands after the primary. The channel number is
     * unambiguous, so it wins; the list's first entry is the fallback.
     */
    private fun bandOf(info: NetworkRegistrationInfo): String {
        val band =
            when (val cell = info.cellIdentity) {
                is CellIdentityNr -> (nrBand(cell.nrarfcn) ?: cell.bands.firstOrNull())?.let { "n$it" }
                is CellIdentityLte -> (lteBand(cell.earfcn) ?: cell.bands.firstOrNull())?.let { "B$it" }
                else -> null
            }
        return band?.let { " ($it)" } ?: ""
    }

    /** LTE downlink EARFCN ranges (3GPP TS 36.101), for the bands seen in practice. */
    private val LTE_BANDS =
        listOf(
            1 to 0..599, 2 to 600..1199, 3 to 1200..1949, 4 to 1950..2399, 5 to 2400..2649,
            7 to 2750..3449, 8 to 3450..3799, 12 to 5010..5179, 13 to 5180..5279, 17 to 5730..5849,
            20 to 6150..6449, 25 to 8040..8689, 26 to 8690..9039, 28 to 9210..9659, 32 to 9920..10359,
            38 to 37750..38249, 39 to 38250..38649, 40 to 38650..39649, 41 to 39650..41589,
            42 to 41590..43589, 43 to 43590..45589, 66 to 66436..67335, 71 to 68586..68935,
        )

    private fun lteBand(earfcn: Int): Int? = LTE_BANDS.firstOrNull { earfcn in it.second }?.first

    /**
     * NR-ARFCN ranges (3GPP TS 38.101-1). Only bands whose range does not overlap another
     * band are resolved here (n77/n78, n20/n28, n1/n66 and n7/n38/n41 overlap), so the rest
     * fall back to the modem's own band list. n79 — Russia's high-band 5G layer — is unambiguous.
     */
    private val NR_BANDS =
        listOf(
            3 to 361000..376000, 8 to 185000..192000, 40 to 460000..480000, 79 to 693334..733333,
        )

    private fun nrBand(arfcn: Int): Int? = NR_BANDS.firstOrNull { arfcn in it.second }?.first

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
