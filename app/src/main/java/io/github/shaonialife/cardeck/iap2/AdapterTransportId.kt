package io.github.shaonialife.cardeck.iap2

import android.content.Context
import java.security.SecureRandom

/**
 * UDID-shaped transport identifier for 0x4E0E param 1 in adapter mode, stable per install.
 *
 * Same shape as the reference sender (unapple-phone session.rs `random_udid`, field-tested with
 * the smartBox): "00008150" + 16 uppercase hex digits = 24 hex characters.
 */
object AdapterTransportId {
    private const val FILE = "adapter_identity"
    private const val KEY = "transport_udid"

    @Volatile
    private var processFallback: String? = null

    fun get(context: Context?): String {
        val prefs = context?.applicationContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs?.getString(KEY, null)?.takeIf(::isValid)?.let { return it }
        val created = prefs?.let { generate() } ?: processFallback ?: generate().also { processFallback = it }
        prefs?.edit()?.putString(KEY, created)?.apply()
        return created
    }

    fun generate(): String {
        val tail = ByteArray(8).also(SecureRandom()::nextBytes)
        return "00008150" + tail.joinToString("") { "%02X".format(it) }
    }

    fun isValid(value: String): Boolean = value.length == 24 && value.all { it in '0'..'9' || it in 'A'..'F' }
}
