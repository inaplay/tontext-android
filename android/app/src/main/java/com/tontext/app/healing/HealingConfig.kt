package com.tontext.app.healing

object HealingConfig {
    // SharedPreferences
    const val PREFS_NAME = "tontext_healing"
    const val PREF_HEALING_ENABLED = "healing_enabled"
    const val PREF_LLM_PROVIDER = "llm_provider"
    const val PREF_API_KEY_ENCRYPTED = "api_key_encrypted"
    const val PREF_API_KEY_IV = "api_key_iv"
    const val PREF_SYSTEM_PROMPT = "system_prompt"

    // Android Keystore
    const val KEYSTORE_ALIAS = "tontext_healing_key"

    // LLM Providers
    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_OPENAI = "openai"

    // API URLs
    const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
    const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"

    // Model names
    const val ANTHROPIC_MODEL = "claude-haiku-4-5-20251001"
    const val OPENAI_MODEL = "gpt-4o-mini"

    // Timeouts (milliseconds)
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val READ_TIMEOUT_MS = 15_000L
    const val WRITE_TIMEOUT_MS = 10_000L

    // Limits
    const val MAX_INPUT_LENGTH = 1000
    const val MAX_RESPONSE_TOKENS = 256

    // Default system prompt
    const val DEFAULT_SYSTEM_PROMPT =
        "You are a speech-to-text post-processor. " +
        "Your job is to correct transcription errors in dictated text. " +
        "Fix punctuation, capitalization, and obvious word errors. " +
        "Return ONLY the corrected text — no explanations, no quotes, no prefixes. " +
        "Preserve the original language of the input."
}
