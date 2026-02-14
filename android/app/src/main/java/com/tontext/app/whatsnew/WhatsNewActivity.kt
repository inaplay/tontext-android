package com.tontext.app.whatsnew

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tontext.app.AppPreferences
import com.tontext.app.BuildConfig
import com.tontext.app.R

class WhatsNewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whats_new)

        val changelog = assets.open("changelog.txt").bufferedReader().use { it.readText() }

        findViewById<TextView>(R.id.changelogText).text = changelog
        findViewById<TextView>(R.id.btnDone).setOnClickListener {
            AppPreferences(this).lastSeenVersionCode = BuildConfig.VERSION_CODE
            finish()
        }
    }
}
