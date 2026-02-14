package com.tontext.app.healing

object PromptBuilder {

    private val appContextMap = mapOf(
        // Messaging apps
        "com.whatsapp" to "a WhatsApp message",
        "org.telegram.messenger" to "a Telegram message",
        "com.facebook.orca" to "a Facebook Messenger message",
        "com.google.android.apps.messaging" to "an SMS/text message",
        "com.discord" to "a Discord message",
        "com.Slack" to "a Slack message",
        "org.thoughtcrime.securesms" to "a Signal message",

        // Email
        "com.google.android.gm" to "an email in Gmail",
        "com.microsoft.office.outlook" to "an email in Outlook",

        // Social media
        "com.twitter.android" to "a tweet/post on X (Twitter)",
        "com.instagram.android" to "an Instagram caption or comment",
        "com.reddit.frontpage" to "a Reddit post or comment",
        "com.linkedin.android" to "a LinkedIn post or message",

        // Code / Dev tools
        "com.termux" to "a terminal/code context",
        "com.github.android" to "a GitHub comment or issue",

        // Notes
        "com.google.android.keep" to "a Google Keep note",
        "com.samsung.android.app.notes" to "a Samsung Notes note",
        "com.microsoft.office.onenote" to "a OneNote note",

        // Browsers
        "com.android.chrome" to "a browser text field",
        "org.mozilla.firefox" to "a browser text field",
        "com.brave.browser" to "a browser text field",

        // Search
        "com.google.android.googlequicksearchbox" to "a Google search query",
    )

    fun buildSystemPrompt(context: InputContext, customSystemPrompt: String = ""): String {
        val parts = mutableListOf<String>()

        val basePrompt = if (customSystemPrompt.isNotBlank()) customSystemPrompt else HealingConfig.DEFAULT_SYSTEM_PROMPT
        parts.add(basePrompt)

        val appHint = appContextMap[context.packageName]
        if (appHint != null) {
            parts.add("The user is typing $appHint. Match the appropriate tone and formatting.")
        } else if (context.appLabel.isNotEmpty()) {
            parts.add("The user is typing in the app \"${context.appLabel}\".")
        }

        if (context.isSearchField) {
            parts.add("This is a search field. Keep the text concise as a search query — no punctuation needed.")
        }
        if (context.isEmailField) {
            parts.add("This is an email address field. The text should be a valid email address if possible.")
        }
        if (context.isUrlField) {
            parts.add("This is a URL field. The text should be formatted as a web address if possible.")
        }
        if (context.isNumberField) {
            parts.add("This is a number field. Extract and return only the numeric value.")
        }

        if (context.hintText.isNotEmpty()) {
            parts.add("The input field hint is: \"${context.hintText}\".")
        }

        return parts.joinToString("\n\n")
    }
}
