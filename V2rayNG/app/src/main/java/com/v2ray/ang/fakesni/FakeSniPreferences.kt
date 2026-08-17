package com.v2ray.ang.fakesni

import android.content.Context

/** Persistent settings for the integrated FakeSNI layer. */
class FakeSniPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("integrated_fakesni", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var fakeSni: String
        get() = prefs.getString("fakeSni", "hcaptcha.com") ?: "hcaptcha.com"
        set(value) = prefs.edit().putString("fakeSni", value).apply()

    var listenPort: Int
        get() = prefs.getInt("listenPort", 40443)
        set(value) = prefs.edit().putInt("listenPort", value).apply()

    var utls: String
        get() = prefs.getString("utls", "firefox") ?: "firefox"
        set(value) = prefs.edit().putString("utls", value).apply()

    var injector: String
        get() = prefs.getString("injector", "passive") ?: "passive"
        set(value) = prefs.edit().putString("injector", value).apply()

    var fakeRepeat: Int
        get() = prefs.getInt("fakeRepeat", 1)
        set(value) = prefs.edit().putInt("fakeRepeat", value.coerceIn(1, 20)).apply()

    var fakeDelay: String
        get() = prefs.getString("fakeDelay", "2ms") ?: "2ms"
        set(value) = prefs.edit().putString("fakeDelay", value).apply()

    var ackTimeout: String
        get() = prefs.getString("ackTimeout", "2s") ?: "2s"
        set(value) = prefs.edit().putString("ackTimeout", value).apply()

    var enableFragment: Boolean
        get() = prefs.getBoolean("enableFragment", false)
        set(value) = prefs.edit().putBoolean("enableFragment", value).apply()

    var fragmentDelay: String
        get() = prefs.getString("fragmentDelay", "500ms") ?: "500ms"
        set(value) = prefs.edit().putString("fragmentDelay", value).apply()

    var sniChunk: Int
        get() = prefs.getInt("sniChunk", 3)
        set(value) = prefs.edit().putInt("sniChunk", value.coerceIn(1, 32)).apply()
}
