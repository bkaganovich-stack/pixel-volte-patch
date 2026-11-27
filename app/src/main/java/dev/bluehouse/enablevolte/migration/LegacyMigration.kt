package dev.bluehouse.enablevolte.migration

import android.app.IActivityManager
import android.app.IInstrumentationWatcher
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.util.Log
import dev.bluehouse.enablevolte.BootLog
import org.json.JSONObject
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MIGRATION_TAG = "PixelIMSX:Migration"

/**
 * Moves a user from the old Pixel IMS app (package `dev.bluehouse.enablevolte`) to Pixel IMS X.
 *
 * Both apps can be installed side by side, so the move is: copy the old app's settings here,
 * then remove the old app so the two do not both re-apply carrier config after a reboot.
 * Everything runs through Shizuku, which both apps already need, so the user only confirms once.
 */
object LegacyMigration {
    const val LEGACY_PACKAGE = "dev.bluehouse.enablevolte"

    /** The shell's package name, which IPackageInstaller expects as the caller for the shell uid. */
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val EXPORT_TIMEOUT_MS = 20_000L
    private const val UNINSTALL_TIMEOUT_MS = 15_000L
    private const val FRAMEWORK_ERROR_KEY = "Error"

    enum class Legacy {
        /** No old app on this device. */
        NONE,

        /** Old app signed with our key (1.3.10+): its settings can be copied automatically. */
        SAME_KEY,

        /** Old app signed with another key (our 1.3.9 or older, or the original upstream app). */
        OTHER_KEY,
    }

    data class ExportResult(
        val prefs: JSONObject,
        val bootLog: String?,
        val sourceVersion: String?,
    )

    fun detect(context: Context): Legacy {
        val pm = context.packageManager
        try {
            pm.getPackageInfo(LEGACY_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Legacy.NONE
        }
        return if (pm.checkSignatures(LEGACY_PACKAGE, context.packageName) == PackageManager.SIGNATURE_MATCH) {
            Legacy.SAME_KEY
        } else {
            Legacy.OTHER_KEY
        }
    }

    fun legacyVersion(context: Context): String? =
        try {
            context.packageManager.getPackageInfo(LEGACY_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    /**
     * Runs [LegacyExportInstrumentation] inside the old app and returns what it read.
     * Blocks for up to [EXPORT_TIMEOUT_MS]; never call it on the main thread.
     */
    fun exportFromLegacy(context: Context): Result<ExportResult> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "exportFromLegacy blocks; call it off the main thread" }
        val latch = CountDownLatch(1)
        var outcome: Result<ExportResult>? = null

        val watcher =
            object : IInstrumentationWatcher.Stub() {
                override fun instrumentationStatus(
                    name: ComponentName?,
                    resultCode: Int,
                    results: Bundle?,
                ) {
                    // Only the framework reports status, when it refuses to start the instrumentation.
                    val error = results?.getString(FRAMEWORK_ERROR_KEY) ?: return
                    outcome = Result.failure(IllegalStateException(error))
                    latch.countDown()
                }

                override fun instrumentationFinished(
                    name: ComponentName?,
                    resultCode: Int,
                    results: Bundle?,
                ) {
                    if (outcome == null) outcome = parse(results)
                    latch.countDown()
                }
            }

        val started =
            try {
                activityManager().startInstrumentation(
                    ComponentName(context, LegacyExportInstrumentation::class.java),
                    null,
                    0,
                    Bundle(),
                    watcher,
                    null,
                    userId(),
                    null,
                )
            } catch (e: Throwable) {
                Log.e(MIGRATION_TAG, "startInstrumentation threw", e)
                return Result.failure(e)
            }
        if (!started) return Result.failure(IllegalStateException("startInstrumentation returned false"))
        if (!latch.await(EXPORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return Result.failure(IllegalStateException("the old app did not answer within ${EXPORT_TIMEOUT_MS / 1000}s"))
        }
        return outcome ?: Result.failure(IllegalStateException("the old app finished without a result"))
    }

    private fun parse(results: Bundle?): Result<ExportResult> {
        if (results?.getBoolean(LegacyExportInstrumentation.RESULT_OK) != true) {
            val error = results?.getString(LegacyExportInstrumentation.RESULT_ERROR) ?: "export finished without a result"
            return Result.failure(IllegalStateException(error))
        }
        return try {
            Result.success(
                ExportResult(
                    prefs = JSONObject(results.getString(LegacyExportInstrumentation.RESULT_PREFS) ?: "{}"),
                    bootLog = results.getString(LegacyExportInstrumentation.RESULT_BOOT_LOG),
                    sourceVersion = results.getString(LegacyExportInstrumentation.RESULT_SOURCE_VERSION),
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes the old app's settings into ours, replacing what is there. Returns how many keys
     * were written. The old boot log is kept above our own entries.
     */
    fun importExport(
        context: Context,
        export: ExportResult,
    ): Int {
        val editor = context.getSharedPreferences(LegacyExportInstrumentation.PREFS_NAME, Context.MODE_PRIVATE).edit()
        var count = 0
        for (key in export.prefs.keys()) {
            val entry = export.prefs.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                "i" -> editor.putInt(key, entry.getInt("v"))
                "l" -> editor.putLong(key, entry.getLong("v"))
                "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                "s" -> editor.putString(key, entry.getString("v"))
                "ss" -> {
                    val array = entry.getJSONArray("v")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> continue
            }
            count++
        }
        check(editor.commit()) { "could not save the copied settings" }

        export.bootLog?.takeIf { it.isNotBlank() }?.let { oldLog ->
            val file = File(context.filesDir, BootLog.FILE_NAME)
            val ours = if (file.exists()) file.readText() else ""
            file.writeText(oldLog.trimEnd('\n') + "\n" + ours)
        }
        BootLog.append(
            context,
            MIGRATION_TAG,
            "copied $count setting(s) from Pixel IMS ${export.sourceVersion ?: ""}".trimEnd(),
        )
        return count
    }

    /**
     * Uninstalls the old app as the shell user, without a confirmation dialog.
     * Returns true once the package is gone. Blocks; never call it on the main thread.
     */
    fun uninstallLegacy(context: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "uninstallLegacy blocks; call it off the main thread" }
        try {
            val packageManager =
                IPackageManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")))
            val installer = IPackageInstaller.Stub.asInterface(ShizukuBinderWrapper(packageManager.packageInstaller.asBinder()))
            // The status goes to a broadcast nobody listens to: we check the result by looking
            // for the package, which avoids implementing the hidden IIntentSender interface.
            val statusReceiver =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("$LEGACY_PACKAGE.UNINSTALL_STATUS").setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            installer.uninstall(
                VersionedPackage(LEGACY_PACKAGE, PackageManager.VERSION_CODE_HIGHEST),
                SHELL_PACKAGE,
                0,
                statusReceiver.intentSender,
                userId(),
            )
        } catch (e: Throwable) {
            Log.e(MIGRATION_TAG, "uninstall through Shizuku failed", e)
            BootLog.appendError(context, MIGRATION_TAG, "removing the old app through Shizuku failed", e)
            return false
        }
        val deadline = System.currentTimeMillis() + UNINSTALL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (detect(context) == Legacy.NONE) {
                BootLog.append(context, MIGRATION_TAG, "removed the old Pixel IMS app")
                return true
            }
            Thread.sleep(300)
        }
        return false
    }

    /** The system uninstall dialog: the fallback when Shizuku cannot remove the old app. */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$LEGACY_PACKAGE"))

    private fun activityManager(): IActivityManager =
        IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)))

    private fun userId(): Int = Process.myUid() / 100_000
}
