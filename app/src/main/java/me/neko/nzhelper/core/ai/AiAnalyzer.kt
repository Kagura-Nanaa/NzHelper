package me.neko.nzhelper.core.ai

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.neko.nzhelper.core.model.Session
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object AiAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 测试连接并拉取可用模型列表。返回模型 ID 列表或错误信息。
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.trimEnd('/') + "/models"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            val json = response.body.string()
            val root = JsonParser.parseString(json).asJsonObject
            val models = root.getAsJsonArray("data")
                ?.mapNotNull { it.asJsonObject.get("id")?.asString }
                ?.sorted()
                ?: emptyList()
            if (models.isEmpty()) {
                return@withContext Result.failure(Exception("未找到可用模型"))
            }
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 调用 AI 接口分析最近一周数据并返回健康建议。
     */
    suspend fun analyze(context: Context, sessions: List<Session>): String? =
        withContext(Dispatchers.IO) {
            if (!AiSettings.isEnabled(context) || !AiSettings.isConfigured(context))
                return@withContext null

            val now = LocalDateTime.now()
            val weekAgo = now.minusDays(7)
            val recent = sessions.filter {
                !it.timestamp.isBefore(weekAgo) && !it.timestamp.isAfter(now)
            }
            if (recent.isEmpty()) return@withContext null

            val count = recent.size
            val days = recent.map { it.timestamp.toLocalDate() }.distinct().size
            val lateNight = recent.count { it.timestamp.hour >= 23 }
            val systemPrompt = buildSystemPrompt(context)
            val userPrompt = buildUserPrompt(count, days, lateNight)

            try {
                val apiUrl = AiSettings.getBaseUrl(context).trimEnd('/') + "/chat/completions"
                val model = AiSettings.getModel(context)
                val apiKey = AiSettings.getApiKey(context)
                val body = buildJsonBody(model, systemPrompt, userPrompt, context)

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val text = response.body.string()
                if (!response.isSuccessful) return@withContext null
                return@withContext parseResponse(text)
            } catch (_: Exception) {
                null
            }
        }

    private fun buildSystemPrompt(context: Context): String {
        val tone = when (AiSettings.getPromptTone(context)) {
            "professional" -> "你是专业健康顾问，回答严谨客观"
            "humorous" -> "你是幽默的健康顾问，回答轻松有趣"
            "caring" -> "你是贴心的健康伙伴，回答温柔关怀"
            "encouraging" -> "你是积极的健康教练，回答充满鼓励"
            "concise" -> "你是高效的健康助手，回答极其简洁"
            else -> "你是温暖的健康顾问，回答简短温馨"
        }
        val length = when (AiSettings.getPromptLength(context)) {
            "short" -> "限制30字以内"
            "detailed" -> "可以给80字左右的详细建议"
            else -> "限制50字以内"
        }
        val custom = AiSettings.getPromptCustom(context).trim()
            .takeIf { it.isNotBlank() }
            ?.let { "。额外要求：$it" } ?: ""
        return "$tone，$length，不要复述数据$custom。"
    }

    private fun buildUserPrompt(count: Int, days: Int, lateNight: Int): String {
        val lateStr = if (lateNight > 0) "，其中 $lateNight 次在深夜（23点后）" else ""
        return "根据最近7天数据：共${count}次，分布在${days}天${lateStr}，请给出健康建议。"
    }

    private fun buildJsonBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        context: Context
    ): String {
        val maxTokens = when (AiSettings.getPromptLength(context)) {
            "short" -> 40
            "detailed" -> 120
            else -> 80
        }
        return """
        {
          "model": "$model",
          "messages": [
            {"role": "system", "content": "$systemPrompt"},
            {"role": "user", "content": "$userPrompt"}
          ],
          "max_tokens": $maxTokens,
          "temperature": 0.8
        }
        """.trimIndent()
    }

    private fun parseResponse(json: String): String? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            root.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
