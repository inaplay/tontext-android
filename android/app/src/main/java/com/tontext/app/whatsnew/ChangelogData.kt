package com.tontext.app.whatsnew

data class ChangelogEntry(val title: String, val description: String)

data class VersionChangelog(
    val versionCode: Int,
    val versionName: String,
    val entries: List<ChangelogEntry>
)

object ChangelogData {

    private val changelogs = listOf(
        VersionChangelog(
            versionCode = 2,
            versionName = "1.1.0",
            entries = listOf(
                ChangelogEntry(
                    title = "Text Polishing",
                    description = "New optional LLM-powered text polishing fixes punctuation, casing, and formatting automatically."
                ),
                ChangelogEntry(
                    title = "Smarter Return Key",
                    description = "The Return button now correctly submits in search bars and chat apps instead of inserting newlines."
                ),
                ChangelogEntry(
                    title = "Improved Silence Detection",
                    description = "Better handling of blank audio — the keyboard now returns to idle cleanly when no speech is detected."
                )
            )
        )
    )

    fun getEntriesSince(lastSeenVersionCode: Int): List<ChangelogEntry> {
        return changelogs
            .filter { it.versionCode > lastSeenVersionCode }
            .sortedBy { it.versionCode }
            .flatMap { it.entries }
    }
}
