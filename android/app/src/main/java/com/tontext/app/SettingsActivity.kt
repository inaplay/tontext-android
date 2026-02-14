package com.tontext.app

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tontext.app.healing.HealingConfig
import com.tontext.app.healing.HealingPreferences
import com.tontext.app.healing.LlmClient
import com.tontext.app.healing.LlmResult
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var healingPreferences: HealingPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        healingPreferences = HealingPreferences(this)

        val healingToggle = findViewById<Switch>(R.id.healingToggle)
        val healingSection = findViewById<LinearLayout>(R.id.healingSettingsSection)
        val providerGroup = findViewById<RadioGroup>(R.id.providerRadioGroup)
        val providerAnthropic = findViewById<RadioButton>(R.id.providerAnthropic)
        val providerOpenAI = findViewById<RadioButton>(R.id.providerOpenAI)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val systemPromptInput = findViewById<EditText>(R.id.systemPromptInput)
        val testButton = findViewById<Button>(R.id.testConnectionButton)
        val testResultText = findViewById<TextView>(R.id.testResultText)

        // Back button
        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener {
            saveSettings(apiKeyInput, systemPromptInput)
            finish()
        }

        // Initialize state from preferences
        healingToggle.isChecked = healingPreferences.healingEnabled
        healingSection.visibility = if (healingPreferences.healingEnabled) View.VISIBLE else View.GONE

        when (healingPreferences.llmProvider) {
            HealingConfig.PROVIDER_ANTHROPIC -> providerAnthropic.isChecked = true
            HealingConfig.PROVIDER_OPENAI -> providerOpenAI.isChecked = true
        }

        val existingKey = healingPreferences.apiKey
        if (existingKey.isNotEmpty()) {
            apiKeyInput.setText(existingKey)
        }

        val existingPrompt = healingPreferences.systemPrompt
        if (existingPrompt.isNotEmpty()) {
            systemPromptInput.setText(existingPrompt)
        }

        // Toggle healing on/off
        healingToggle.setOnCheckedChangeListener { _, isChecked ->
            healingPreferences.healingEnabled = isChecked
            healingSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Provider selection
        providerGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.providerAnthropic -> healingPreferences.llmProvider = HealingConfig.PROVIDER_ANTHROPIC
                R.id.providerOpenAI -> healingPreferences.llmProvider = HealingConfig.PROVIDER_OPENAI
            }
            testResultText.visibility = View.GONE
        }

        // Test connection
        testButton.setOnClickListener {
            saveSettings(apiKeyInput, systemPromptInput)
            val apiKey = healingPreferences.apiKey
            if (apiKey.isEmpty()) {
                testResultText.text = getString(R.string.settings_test_no_key)
                testResultText.setTextColor(Color.RED)
                testResultText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            testButton.isEnabled = false
            testResultText.text = getString(R.string.settings_test_testing)
            testResultText.setTextColor(getColor(R.color.text_secondary))
            testResultText.visibility = View.VISIBLE

            val provider = healingPreferences.llmProvider
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    LlmClient().testApiKey(provider, apiKey)
                }
                when (result) {
                    is LlmResult.Success -> {
                        testResultText.text = getString(R.string.settings_test_success)
                        testResultText.setTextColor(Color.parseColor("#4CAF50"))
                    }
                    is LlmResult.Error -> {
                        testResultText.text = getString(R.string.settings_test_failed, result.message)
                        testResultText.setTextColor(Color.RED)
                    }
                }
                testButton.isEnabled = true
            }
        }
    }

    private fun saveSettings(apiKeyInput: EditText, systemPromptInput: EditText) {
        val key = apiKeyInput.text.toString().trim()
        if (key.isNotEmpty()) {
            healingPreferences.apiKey = key
        }
        healingPreferences.systemPrompt = systemPromptInput.text.toString().trim()
    }

    override fun onPause() {
        super.onPause()
        saveSettings(findViewById(R.id.apiKeyInput), findViewById(R.id.systemPromptInput))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
