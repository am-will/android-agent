package dev.androidagent

import android.content.Context

data class AgentConfig(
    val hostUrl: String,
    val deviceId: String,
    val token: String,
    val openAiApiKey: String,
    val systemPrompt: String,
    val model: String,
    val reasoningEffort: String
)

object AgentConfigStore {
    private const val PREFS = "android_agent_config"
    private const val HOST_URL = "host_url"
    private const val DEVICE_ID = "device_id"
    private const val TOKEN = "token"
    private const val OPENAI_API_KEY = "openai_api_key"
    private const val SYSTEM_PROMPT = "system_prompt"
    private const val MODEL = "model"
    private const val REASONING_EFFORT = "reasoning_effort"

    fun load(context: Context): AgentConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AgentConfig(
            hostUrl = prefs.getString(HOST_URL, "ws://127.0.0.1:8787/phone") ?: "ws://127.0.0.1:8787/phone",
            deviceId = prefs.getString(DEVICE_ID, "pixel") ?: "pixel",
            token = prefs.getString(TOKEN, "change-me") ?: "change-me",
            openAiApiKey = prefs.getString(OPENAI_API_KEY, "") ?: "",
            systemPrompt = prefs.getString(SYSTEM_PROMPT, DefaultSystemPrompt.text) ?: DefaultSystemPrompt.text,
            model = prefs.getString(MODEL, "gpt-5.5") ?: "gpt-5.5",
            reasoningEffort = prefs.getString(REASONING_EFFORT, "medium") ?: "medium"
        )
    }

    fun save(context: Context, config: AgentConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST_URL, config.hostUrl)
            .putString(DEVICE_ID, config.deviceId)
            .putString(TOKEN, config.token)
            .putString(OPENAI_API_KEY, config.openAiApiKey)
            .putString(SYSTEM_PROMPT, config.systemPrompt)
            .putString(MODEL, config.model)
            .putString(REASONING_EFFORT, config.reasoningEffort)
            .apply()
    }
}
