package com.tontext.app

import android.content.Context

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("tontext_app", Context.MODE_PRIVATE)

    var lastSeenVersionCode: Int
        get() = prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, value).apply()

    companion object {
        private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    }
}
