package com.tontext.app.whatsnew

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class WhatsNewPagerAdapter(
    activity: FragmentActivity,
    private val entries: List<ChangelogEntry>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = entries.size

    override fun createFragment(position: Int): WhatsNewPageFragment {
        val entry = entries[position]
        return WhatsNewPageFragment.newInstance(entry.title, entry.description)
    }
}
