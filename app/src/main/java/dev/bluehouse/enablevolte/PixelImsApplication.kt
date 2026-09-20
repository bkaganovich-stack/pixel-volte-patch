package dev.bluehouse.enablevolte

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

private const val APP_TAG = "PixelIMS:Application"

/**
 * Catches the moment Shizuku becomes available and queues a re-apply.
 *
 * This is what makes auto-apply work on a non-rooted device. ShizukuProvider — already
 * declared in the manifest but never acted on until now — is called by the daemon when it
 * starts, which starts our process even if nothing else has. Registering the sticky
 * listener here means we react whenever that happens: two seconds after boot or two hours,
 * neither of which a boot-time service could have waited for.
 */
class PixelImsApplication : Application() {
    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            val repo = SettingsRepository(this)
            if (!repo.autoApplyEnabled) return@OnBinderReceivedListener
            if (!repo.needsApply()) {
                Log.d(APP_TAG, "Shizuku up, but settings already applied this boot")
                return@OnBinderReceivedListener
            }
            BootLog.append(this, APP_TAG, "Shizuku binder received, queueing auto-apply")
            AutoApplyWorker.enqueue(this)
        }

    override fun onCreate() {
        super.onCreate()

        // Needed before any telephony call, and this process may be started by
        // ShizukuProvider with no Activity ever created.
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        } catch (e: Throwable) {
            Log.w(APP_TAG, "Could not register Shizuku listener", e)
        }
    }
}
