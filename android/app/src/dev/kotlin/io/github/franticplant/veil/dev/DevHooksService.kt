package io.github.franticplant.veil.dev

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.franticplant.veil.VeilApplication
import io.github.franticplant.veil.dev.api.DevIds
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.IDevHooks
import io.github.franticplant.veil.dev.api.MethodSpec
import io.github.franticplant.veil.dev.api.Reply
import org.json.JSONException
import org.json.JSONObject

/**
 * The dev agent's way into Veil. Exists only in the dev flavor (src/dev).
 *
 * Binding needs the signature-level DEV_HOOKS permission (the manifest enforces it), and each call
 * additionally checks that the caller is the dev agent package. Calls arrive on Binder threads and
 * may block (waiting for a screen to show something); the engine and UI keep running meanwhile.
 */
class DevHooksService : Service() {
    private val methods by lazy { AppMethods(application as VeilApplication) }

    override fun onBind(intent: Intent?): IBinder =
        object : IDevHooks.Stub() {
            override fun catalog(): String {
                checkCaller()
                return MethodSpec.catalogJson(methods.table.specs)
            }

            override fun call(method: String, paramsJson: String): String {
                checkCaller()
                val params =
                    try {
                        JSONObject(paramsJson)
                    } catch (e: JSONException) {
                        return Reply.error(ErrorCode.INVALID_PARAMS, "params are not a JSON object")
                            .toString()
                    }
                return methods.table.call(method, params).toString()
            }
        }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if (DevIds.DEVD_PACKAGE !in packages) {
            throw SecurityException("DevHooks is for ${DevIds.DEVD_PACKAGE}, not uid $uid")
        }
    }
}
