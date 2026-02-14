package com.tontext.app.healing

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "LlmClient"

sealed class LlmResult {
    data class Success(val text: String) : LlmResult()
    data class Error(val message: String) : LlmResult()
}

class LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(HealingConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HealingConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(HealingConfig.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun heal(rawText: String, systemPrompt: String, provider: String, apiKey: String): LlmResult {
        val truncated = if (rawText.length > HealingConfig.MAX_INPUT_LENGTH) {
            rawText.take(HealingConfig.MAX_INPUT_LENGTH)
        } else rawText

        return when (provider) {
            HealingConfig.PROVIDER_ANTHROPIC -> callAnthropic(truncated, systemPrompt, apiKey)
            HealingConfig.PROVIDER_OPENAI -> callOpenAI(truncated, systemPrompt, apiKey)
            else -> LlmResult.Error("Unknown provider: $provider")
        }
    }

    fun testApiKey(provider: String, apiKey: String): LlmResult {
        return heal("Hello world test", "Return the input text exactly as-is.", provider, apiKey)
    }

    private fun callAnthropic(text: String, systemPrompt: String, apiKey: String): LlmResult {
        val body = JSONObject().apply {
            put("model", HealingConfig.ANTHROPIC_MODEL)
            put("max_tokens", HealingConfig.MAX_RESPONSE_TOKENS)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        val request = Request.Builder()
            .url(HealingConfig.ANTHROPIC_API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request) { responseBody ->
            val json = JSONObject(responseBody)
            val content = json.getJSONArray("content")
            if (content.length() > 0) {
                content.getJSONObject(0).getString("text").trim()
            } else ""
        }
    }

    private fun callOpenAI(text: String, systemPrompt: String, apiKey: String): LlmResult {
        val body = JSONObject().apply {
            put("model", HealingConfig.OPENAI_MODEL)
            put("max_tokens", HealingConfig.MAX_RESPONSE_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        val request = Request.Builder()
            .url(HealingConfig.OPENAI_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request) { responseBody ->
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
            } else ""
        }
    }

    private fun executeRequest(request: Request, parseResponse: (String) -> String): LlmResult {
        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errorJson = JSONObject(responseBody)
                    errorJson.optJSONObject("error")?.optString("message")
                        ?: "HTTP ${response.code}"
                } catch (_: Exception) {
                    "HTTP ${response.code}: $responseBody"
                }
                Log.w(LOG_TAG, "API error: $errorMsg")
                return LlmResult.Error(errorMsg)
            }

            val result = parseResponse(responseBody)
            if (result.isEmpty()) {
                LlmResult.Error("Empty response from LLM")
            } else {
                LlmResult.Success(result)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "LLM request failed", e)
            LlmResult.Error(e.message ?: "Unknown error")
        }
    }
}
