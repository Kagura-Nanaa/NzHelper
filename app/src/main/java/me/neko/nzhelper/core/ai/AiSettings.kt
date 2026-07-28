package me.neko.nzhelper.core.ai

import android.content.Context
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.neko.nzhelper.NzApplication
import java.util.UUID.randomUUID

data class AiProvider(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modeKey: String = "openai",
    val model: String = "gpt-4o-mini",
    val isActive: Boolean = false,
    val cachedModels: List<String> = emptyList(),
    val extraFieldsJson: String? = null,
    val compatKey: String? = null
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && apiKey.isNotBlank() && baseUrl.isNotBlank()

    val mode: ApiMode get() = ApiMode.fromKey(modeKey)

    val extraFields: JsonObject?
        get() = extraFieldsJson?.let {
            try {
                JsonParser.parseString(it).asJsonObject
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        fun create(): AiProvider = AiProvider(
            id = randomUUID().toString().take(8)
        )
    }
}

object AiSettings {

    private const val PREFS = "ai_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_PROMPT_TONE = "prompt_tone"
    private const val KEY_PROMPT_LENGTH = "prompt_length"
    private const val KEY_PROMPT_CUSTOM = "prompt_custom"

    private val gson = NzApplication.gson

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 全局启停 ──
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    // ── 提示词设置 ──
    fun getPromptTone(context: Context): String =
        prefs(context).getString(KEY_PROMPT_TONE, "warm") ?: "warm"

    fun getPromptLength(context: Context): String =
        prefs(context).getString(KEY_PROMPT_LENGTH, "medium") ?: "medium"

    fun getPromptCustom(context: Context): String =
        prefs(context).getString(KEY_PROMPT_CUSTOM, "") ?: ""

    fun savePrompt(
        context: Context,
        tone: String,
        length: String,
        custom: String
    ) {
        prefs(context).edit {
            putString(KEY_PROMPT_TONE, tone)
            putString(KEY_PROMPT_LENGTH, length)
            putString(KEY_PROMPT_CUSTOM, custom)
        }
    }

    // ── 供应商列表 ──
    fun getProviders(context: Context): List<AiProvider> {
        val json = prefs(context).getString(KEY_PROVIDERS, null) ?: return emptyList()
        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, AiProvider::class.java
            ).type
            gson.fromJson<List<AiProvider>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveProviders(context: Context, providers: List<AiProvider>) {
        prefs(context).edit { putString(KEY_PROVIDERS, gson.toJson(providers)) }
    }

    fun saveProvider(context: Context, provider: AiProvider) {
        val list = getProviders(context).toMutableList()
        val idx = list.indexOfFirst { it.id == provider.id }
        if (idx >= 0) list[idx] = provider else list += provider
        if (provider.isActive) {
            list.replaceAll { if (it.id != provider.id) it.copy(isActive = false) else it }
        }
        saveProviders(context, list)
    }

    fun deleteProvider(context: Context, id: String) {
        val list = getProviders(context).filter { it.id != id }.toMutableList()
        val hasActive = list.any { it.isActive }
        if (!hasActive && list.isNotEmpty()) {
            list[0] = list[0].copy(isActive = true)
        }
        saveProviders(context, list)
    }

    fun setActive(context: Context, id: String) {
        val list = getProviders(context).map {
            it.copy(isActive = it.id == id)
        }
        saveProviders(context, list)
    }

    fun getActiveProvider(context: Context): AiProvider? =
        getProviders(context).firstOrNull { it.isActive }

    fun isConfigured(context: Context): Boolean =
        getActiveProvider(context)?.isComplete == true
}
