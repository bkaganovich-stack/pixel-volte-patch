package dev.bluehouse.enablevolte

import android.content.Context
import android.content.pm.PackageManager

/**
 * Other tools that write the same carrier config overrides. Android keeps a single merged
 * override per SIM, so whichever app writes last wins — and a tool that re-applies its own
 * values on every Shizuku start can silently undo settings made here.
 *
 * The package list must stay in sync with the <queries> block in AndroidManifest.xml;
 * without it, Android 11+ hides these packages from us.
 */
object ConflictDetector {
    data class Tool(
        val packageName: String,
        /** Re-applies its own settings by itself (boot / Shizuku start), not only when used. */
        val appliesAutomatically: Boolean,
    )

    data class Conflict(
        val packageName: String,
        val label: String,
        val appliesAutomatically: Boolean,
        /** Its marker key is in this SIM's live overrides right now. */
        val activeOnSubscriptions: List<Int>,
    )

    private val KNOWN =
        listOf(
            // vvb2060/Ims, and the TensorIMS / Carrier IMS for Pixel forks built on it:
            // overrides every SIM whenever Shizuku starts.
            Tool("io.github.vvb2060.ims", appliesAutomatically = true),
            // TakaiSaisei/pixel_ims: optional apply-on-boot.
            Tool("com.takaisaisei.pixelims", appliesAutomatically = true),
            // barrylk/Pixel-IMS-5G: restores its profile on boot in root mode.
            Tool("com.nirmala.pixel5gims", appliesAutomatically = true),
            // iKirby/PixelCarrierSettings: only writes when used, but also flips the VoIMS opt-in.
            Tool("me.ikirby.pixelutils", appliesAutomatically = false),
            // svenuks/ImsForPixel: only writes when used.
            Tool("com.svenuks.imsforpixel", appliesAutomatically = false),
        )

    /** Config keys other tools stamp into the overrides they write. */
    private val MARKERS = mapOf("vvb2060_config_version" to "io.github.vvb2060.ims")

    fun detect(
        context: Context,
        subscriptionIds: List<Int>,
    ): List<Conflict> {
        val pm = context.packageManager
        val activeBy = mutableMapOf<String, MutableList<Int>>()
        for (subId in subscriptionIds) {
            val moder = SubscriptionModer(context, subId)
            for ((key, pkg) in MARKERS) {
                val present =
                    try {
                        moder.getValue(key) != null
                    } catch (e: Throwable) {
                        false
                    }
                if (present) activeBy.getOrPut(pkg) { mutableListOf() } += subId
            }
        }
        return KNOWN.mapNotNull { tool ->
            val label =
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(tool.packageName, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
            Conflict(tool.packageName, label, tool.appliesAutomatically, activeBy[tool.packageName].orEmpty())
        }
    }
}
