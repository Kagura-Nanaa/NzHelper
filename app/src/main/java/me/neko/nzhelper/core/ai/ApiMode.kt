package me.neko.nzhelper.core.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

sealed class ApiMode(
    val key: String,
    val label: String,
    val chatPath: String,
    val modelsPath: String?,
    val authType: AuthType = AuthType.BEARER
) {
    enum class AuthType { BEARER, X_API_KEY, QUERY_PARAM }

    abstract fun buildRequestBody(
        model: String, systemPrompt: String, userPrompt: String,
        maxTokens: Int, extraFields: JsonObject? = null
    ): String

    abstract fun parseResponse(json: String): String?

    object OpenAI : ApiMode("openai", "OpenAI", "/chat/completions", "/models", AuthType.BEARER) {
        override fun buildRequestBody(
            model: String, systemPrompt: String, userPrompt: String,
            maxTokens: Int, extraFields: JsonObject?
        ): String = buildOpenAiBody(model, systemPrompt, userPrompt, maxTokens, extraFields)
        override fun parseResponse(json: String): String? = parseOpenAiResponse(json)
    }

    object Google : ApiMode("google", "Google", "/v1/models/__MODEL__:generateContent", "/models", AuthType.QUERY_PARAM) {
        override fun buildRequestBody(
            model: String, systemPrompt: String, userPrompt: String,
            maxTokens: Int, extraFields: JsonObject?
        ): String {
            val body = JsonObject().apply {
                add("contents", JsonArray().apply {
                    add(JsonObject().apply {
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", "$systemPrompt\n\n$userPrompt") })
                        })
                    })
                })
                add("generationConfig", JsonObject().apply {
                    addProperty("maxOutputTokens", maxTokens)
                    addProperty("temperature", 0.7)
                })
            }
            extraFields?.entrySet()?.forEach { (k, v) -> body.add(k, v) }
            return body.toString()
        }
        override fun parseResponse(json: String): String? = parseGoogleResponse(json)
    }

    object Claude : ApiMode("claude", "Claude", "/v1/messages", null, AuthType.X_API_KEY) {
        override fun buildRequestBody(
            model: String, systemPrompt: String, userPrompt: String,
            maxTokens: Int, extraFields: JsonObject?
        ): String {
            val body = JsonObject().apply {
                addProperty("model", model)
                addProperty("system", systemPrompt)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userPrompt)
                    })
                })
                addProperty("max_tokens", maxTokens)
            }
            extraFields?.entrySet()?.forEach { (k, v) -> body.add(k, v) }
            return body.toString()
        }
        override fun parseResponse(json: String): String? = parseClaudeResponse(json)
    }

    companion object {
        val ALL = listOf(OpenAI, Google, Claude)
        fun fromKey(key: String): ApiMode = ALL.firstOrNull { it.key == key } ?: OpenAI

        private fun buildOpenAiBody(
            model: String, systemPrompt: String, userPrompt: String,
            maxTokens: Int, extraFields: JsonObject?
        ): String {
            val body = JsonObject().apply {
                addProperty("model", model)
                add("messages", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system"); addProperty("content", systemPrompt)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user"); addProperty("content", userPrompt)
                    })
                })
                addProperty("max_tokens", maxTokens)
                addProperty("temperature", 0.7f)
            }
            extraFields?.entrySet()?.forEach { (k, v) -> body.add(k, v) }
            return body.toString()
        }

        private fun parseOpenAiResponse(json: String): String? {
            return try {
                val root = JsonParser.parseString(json).asJsonObject
                val choices = root.getAsJsonArray("choices") ?: return null
                val first = choices.get(0)?.asJsonObject ?: return null
                val msg = first.getAsJsonObject("message") ?: return null
                msg.get("content")?.asString?.trim()?.takeIf { it.isNotBlank() }
                    ?: msg.get("reasoning_content")?.asString?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { it.substringAfterLast("。").takeIf { s -> s.isNotBlank() } }
            } catch (_: Exception) { null }
        }

        private fun parseGoogleResponse(json: String): String? {
            return try {
                JsonParser.parseString(json).asJsonObject
                    .getAsJsonArray("candidates")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString?.trim()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
        }

        private fun parseClaudeResponse(json: String): String? {
            return try {
                JsonParser.parseString(json).asJsonObject
                    .getAsJsonArray("content")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString?.trim()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }
}
