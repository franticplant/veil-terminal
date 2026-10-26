package io.github.franticplant.veil.devd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After devd is updated (its process ends mid-install), switches the listener back on if it was on,
 * with the time it had left. Without this, a self-update would lock the laptop out until someone
 * opened devd on the phone.
 */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as DevdApp
        val offAt = app.settings.listenerOffAt ?: return
        if (offAt <= System.currentTimeMillis()) {
            app.settings.rememberListener(null)
            return
        }
        app.log.info("listener", "devd was updated; resuming the listener")
        DevdService.resume(context, offAt)
    }
}
