package me.neko.nzhelper.core.ai

import android.content.Context
import com.google.gson.JsonObject
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

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        mode: ApiMode,
        extraFields: JsonObject? = null
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trimEnd('/')

        val models = mode.modelsPath?.let { path ->
            try {
                val modelsUrl = when (mode.authType) {
                    ApiMode.AuthType.QUERY_PARAM -> "$cleanBase$path?key=$apiKey"
                    else -> "$cleanBase$path"
                }
                val reqBuilder = Request.Builder().url(modelsUrl)
                when (mode.authType) {
                    ApiMode.AuthType.BEARER -> reqBuilder.header("Authorization", "Bearer $apiKey")
                    ApiMode.AuthType.X_API_KEY -> {
                        reqBuilder.header("x-api-key", apiKey)
                        reqBuilder.header("anthropic-version", "2023-06-01")
                    }

                    ApiMode.AuthType.QUERY_PARAM -> {}
                }
                val req = reqBuilder.build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful)
                    return@withContext Result.failure(Exception("$cleanBase$path HTTP ${resp.code}"))
                val root = JsonParser.parseString(resp.body.string()).asJsonObject
                root.getAsJsonArray("data")
                    ?.mapNotNull { it.asJsonObject.get("id")?.asString }?.sorted() ?: emptyList()
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        } ?: emptyList()

        try {
            val testModel = models.firstOrNull() ?: "test"
            val testBody = mode.buildRequestBody(testModel, "", "hi", 1, extraFields)
            val chatPath = mode.chatPath.replace("__MODEL__", testModel)
            val url = when (mode.authType) {
                ApiMode.AuthType.QUERY_PARAM -> "$cleanBase$chatPath?key=$apiKey"
                else -> "$cleanBase$chatPath"
            }
            val reqBuilder = Request.Builder().url(url)
                .post(testBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Content-Type", "application/json")
            when (mode.authType) {
                ApiMode.AuthType.BEARER -> reqBuilder.header("Authorization", "Bearer $apiKey")
                ApiMode.AuthType.X_API_KEY -> {
                    reqBuilder.header("x-api-key", apiKey)
                    reqBuilder.header("anthropic-version", "2023-06-01")
                }

                ApiMode.AuthType.QUERY_PARAM -> {}
            }
            val req = reqBuilder.build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful)
                return@withContext Result.failure(Exception("$url HTTP ${resp.code}"))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        Result.success(models)
    }

    suspend fun analyze(context: Context, sessions: List<Session>): Result<String> =
        withContext(Dispatchers.IO) {
            if (!AiSettings.isEnabled(context) || !AiSettings.isConfigured(context))
                return@withContext Result.failure(Exception("AI 未启用或未配置供应商"))
            val now = LocalDateTime.now()
            val recent = sessions.filter {
                !it.timestamp.isBefore(now.minusDays(7)) && !it.timestamp.isAfter(now)
            }
            if (recent.isEmpty()) return@withContext Result.failure(Exception("最近7天无记录"))
            val count = recent.size
            val days = recent.map { it.timestamp.toLocalDate() }.distinct().size
            val lateNight = recent.count { it.timestamp.hour >= 23 }

            val provider = AiSettings.getActiveProvider(context)
                ?: return@withContext Result.failure(Exception("无激活的供应商"))
            val mode = provider.mode
            val model = provider.model
            val apiKey = provider.apiKey
            val baseUrl = provider.baseUrl.trimEnd('/')
            val chatPath = mode.chatPath.replace("__MODEL__", model)
            val apiUrl = when (mode.authType) {
                ApiMode.AuthType.QUERY_PARAM -> "$baseUrl$chatPath?key=$apiKey"
                else -> "$baseUrl$chatPath"
            }

            val systemPrompt = "你是健康生活顾问，用户记录的是手淫数据。你的建议需简短、自然、不评判。"
            val userPrompt = buildPrompt(context, count, days, lateNight)
            val maxTokens = when (AiSettings.getPromptLength(context)) {
                "short" -> 40; "detailed" -> 120; else -> 80
            }
            val body = mode.buildRequestBody(
                model,
                systemPrompt,
                userPrompt,
                maxTokens,
                provider.extraFields
            )

            try {
                val reqBuilder = Request.Builder().url(apiUrl)
                    .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .header("Content-Type", "application/json")
                when (mode.authType) {
                    ApiMode.AuthType.BEARER -> reqBuilder.header("Authorization", "Bearer $apiKey")
                    ApiMode.AuthType.X_API_KEY -> {
                        reqBuilder.header("x-api-key", apiKey)
                        reqBuilder.header("anthropic-version", "2023-06-01")
                    }

                    ApiMode.AuthType.QUERY_PARAM -> {}
                }
                val req = reqBuilder.build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful)
                    return@withContext Result.failure(Exception("$apiUrl HTTP ${resp.code}"))
                val text = resp.body.string()
                val content = mode.parseResponse(text)
                    ?: return@withContext Result.failure(Exception("解析失败\n${text.take(400)}"))
                return@withContext Result.success(content)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun buildPrompt(context: Context, count: Int, days: Int, lateNight: Int): String {
        val tone = when (AiSettings.getPromptTone(context)) {
            "professional" -> "你是专业健康顾问，回答严谨客观"
            "humorous" -> "你是幽默的健康顾问，回答轻松有趣"
            "caring" -> "你是贴心的健康伙伴，回答温柔关怀"
            "encouraging" -> "你是积极的健康教练，回答充满鼓励"
            "concise" -> "你是高效的健康助手，回答极其简洁"
            else -> "你是温暖的健康顾问，回答简短温馨"
        }
        val len = when (AiSettings.getPromptLength(context)) {
            "short" -> "限制30字以内"
            "detailed" -> "可以给80字左右的详细建议"
            else -> "限制50字以内"
        }
        val extra = AiSettings.getPromptCustom(context).trim()
            .takeIf { it.isNotBlank() }?.let { "，$it" } ?: ""
        val late = if (lateNight > 0) "，${lateNight}次在深夜" else ""
        return "这是手淫记录：最近7天共${count}次，分${days}天${late}。" +
                "用${tone}口吻给${len}的健康建议${extra}。只输出建议不要推理。"
    }
}
