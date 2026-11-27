package dev.bluehouse.enablevolte.migration

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads the settings of the old Pixel IMS app (package [LegacyMigration.LEGACY_PACKAGE]) and
 * hands them back to Pixel IMS X.
 *
 * Declared in our manifest with `targetPackage` set to the old package, so the system runs this
 * class inside the old app's process, as the old app's uid. That is the only identity allowed to
 * read its private SharedPreferences, and the old app needs no code of its own for it. Android
 * starts it only when both APKs are signed with the same key (1.3.10 and newer).
 *
 * The old app's own classes may share this process, so this class must stay self-contained:
 * framework classes and org.json only, nothing else from our code.
 */
class LegacyExportInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val results = Bundle()
        try {
            val target = targetContext
            val prefs = target.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            results.putString(RESULT_PREFS, encode(prefs).toString())
            results.putInt(RESULT_PREF_COUNT, prefs.size)
            val log = File(target.filesDir, BOOT_LOG_FILE)
            if (log.exists()) results.putString(RESULT_BOOT_LOG, log.readText().takeLast(MAX_LOG_CHARS))
            results.putString(
                RESULT_SOURCE_VERSION,
                target.packageManager.getPackageInfo(target.packageName, 0).versionName,
            )
            results.putBoolean(RESULT_OK, true)
        } catch (e: Throwable) {
            results.putBoolean(RESULT_OK, false)
            results.putString(RESULT_ERROR, "${e.javaClass.simpleName}: ${e.message}")
        }
        finish(Activity.RESULT_OK, results)
    }

    companion object {
        // Copies of SettingsRepository / BootLog constants: see the class comment.
        const val PREFS_NAME = "pixel_ims_settings"
        const val BOOT_LOG_FILE = "boot_log.txt"
        private const val MAX_LOG_CHARS = 32_000

        const val RESULT_OK = "legacy_ok"
        const val RESULT_ERROR = "legacy_error"
        const val RESULT_PREFS = "legacy_prefs"
        const val RESULT_PREF_COUNT = "legacy_pref_count"
        const val RESULT_BOOT_LOG = "legacy_boot_log"
        const val RESULT_SOURCE_VERSION = "legacy_version"

        /** Each value is stored as {"t": type, "v": value} so it can be written back with the same type. */
        fun encode(prefs: Map<String, *>): JSONObject {
            val out = JSONObject()
            for ((key, value) in prefs) {
                val entry =
                    when (value) {
                        is Boolean -> JSONObject().put("t", "b").put("v", value)
                        is Int -> JSONObject().put("t", "i").put("v", value)
                        is Long -> JSONObject().put("t", "l").put("v", value)
                        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                        is String -> JSONObject().put("t", "s").put("v", value)
                        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                        else -> continue
                    }
                out.put(key, entry)
            }
            return out
        }
    }
}
