package com.tontext.app.whatsnew

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.tontext.app.AppPreferences
import com.tontext.app.BuildConfig
import com.tontext.app.R

class WhatsNewActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnSkip: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnDone: TextView
    private lateinit var dotContainer: LinearLayout
    private lateinit var prefs: AppPreferences

    private var entries: List<ChangelogEntry> = emptyList()
    private val dots = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whats_new)

        prefs = AppPreferences(this)
        val lastSeen = intent.getIntExtra(EXTRA_LAST_SEEN_VERSION_CODE, 0)
        entries = ChangelogData.getEntriesSince(lastSeen)

        if (entries.isEmpty()) {
            markSeenAndFinish()
            return
        }

        viewPager = findViewById(R.id.viewPager)
        btnSkip = findViewById(R.id.btnSkip)
        btnNext = findViewById(R.id.btnNext)
        btnDone = findViewById(R.id.btnDone)
        dotContainer = findViewById(R.id.dotContainer)

        viewPager.adapter = WhatsNewPagerAdapter(this, entries)

        buildDots()
        updateButtons(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateButtons(position)
            }
        })

        btnSkip.setOnClickListener { markSeenAndFinish() }
        btnNext.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem + 1
        }
        btnDone.setOnClickListener { markSeenAndFinish() }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (viewPager.currentItem > 0) {
            viewPager.currentItem = viewPager.currentItem - 1
        }
    }

    private fun buildDots() {
        dotContainer.removeAllViews()
        dots.clear()
        val dotSize = (8 * resources.displayMetrics.density).toInt()
        val dotMargin = (4 * resources.displayMetrics.density).toInt()

        for (i in entries.indices) {
            val dot = View(this).apply {
                val params = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dotMargin, 0, dotMargin, 0)
                }
                layoutParams = params
                setBackgroundResource(R.drawable.dot_indicator)
            }
            dots.add(dot)
            dotContainer.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(activePosition: Int) {
        for (i in dots.indices) {
            val color = if (i == activePosition) getColor(R.color.accent) else getColor(R.color.text_secondary)
            dots[i].background.setTint(color)
        }
    }

    private fun updateButtons(position: Int) {
        val isLastPage = position == entries.size - 1
        btnNext.visibility = if (isLastPage) View.GONE else View.VISIBLE
        btnDone.visibility = if (isLastPage) View.VISIBLE else View.GONE
    }

    private fun markSeenAndFinish() {
        prefs.lastSeenVersionCode = BuildConfig.VERSION_CODE
        finish()
    }

    companion object {
        const val EXTRA_LAST_SEEN_VERSION_CODE = "extra_last_seen_version_code"
    }
}
