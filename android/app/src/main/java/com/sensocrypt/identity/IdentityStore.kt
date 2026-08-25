package com.sensocrypt.identity

import android.content.Context

/** Persists the server-assigned device_id from enrollment. The actual key material lives
 * only in AndroidKeyStore -- this is just the pointer the server gave us back. */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("sensocrypt_identity", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()
}
