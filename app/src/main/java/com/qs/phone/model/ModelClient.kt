package com.qs.phone.model

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * 模型配置
 */
data class ModelConfig(
    val baseUrl: String = "http://localhost:8000/v1",
    val apiKey: String = "EMPTY",
    val modelName: String = "autoglm-phone-9b",
    val maxTokens: Int = 3000,
    val temperature: Float = 0.0f,
    val topP: Float = 0.85f
)

/**
 * 模型响应
 */
data class ModelResponse(
    val thinking: String,
    val action: String,
    val rawContent: String,
    val timeToFirstToken: Long? = null,
    val totalTime: Long? = null
)

/**
 * 消息构建器
 */
object MessageBuilder {
    fun createSystemMessage(content: String): Map<String, Any> {
        return mapOf("role" to "system", "content" to content)
    }

    fun createUserMessage(text: String, imageBase64: String? = null): Map<String, Any> {
        val content = mutableListOf<Map<String, Any>>()

        if (imageBase64 != null) {
            content.add(
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64")
                )
            )
        }

        content.add(mapOf("type" to "text", "text" to text))

        return mapOf("role" to "user", "content" to content)
    }

    fun createAssistantMessage(content: String): Map<String, Any> {
        return mapOf("role" to "assistant", "content" to content)
    }

    fun buildScreenInfo(currentApp: String): String {
        return """{"current_app": "$currentApp"}"""
    }

    fun removeImagesFromMessage(message: Map<String, Any>): Map<String, Any> {
        val content = message["content"]
        if (content is List<*>) {
            val filtered = content.filterIsInstance<Map<String, Any>>()
                .filter { it["type"] == "text" }
            return message.toMutableMap().apply { this["content"] = filtered }
        }
        return message
    }
}

/**
 * 模型客户端 - 与 OpenAI 兼容的 API 通信
 */
class ModelClient(private val config: ModelConfig) {
    companion object {
        private const val TAG = "ModelClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 发送请求并获取流式响应
     */
    fun requestStream(messages: List<Map<String, Any>>): Flow<StreamChunk> = flow {
        val requestBody = mapOf(
            "model" to config.modelName,
            "messages" to messages,
            "max_tokens" to config.maxTokens,
            "temperature" to config.temperature,
            "top_p" to config.topP,
            "stream" to true
        )

        val jsonBody = gson.toJson(requestBody)
        Log.d(TAG, "Request body: ${jsonBody.take(500)}")

        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(StreamChunk.Error("HTTP ${response.code}: ${response.body?.string()}"))
                return@flow
            }

        val reader = BufferedReader(response.body?.charStream())
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val data = line ?: continue
            if (!data.startsWith("data: ")) continue
            if (data == "data: [DONE]") break

            val jsonStr = data.removePrefix("data: ")
            try {
                val json = gson.fromJson(jsonStr, JsonObject::class.java)
                val choices = json.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                    val content = delta?.get("content")?.asString
                    if (content != null) {
                        emit(StreamChunk.Content(content))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Parse error: ${e.message}")
            }
        }

        reader.close()
        response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            emit(StreamChunk.Error("连接失败: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 发送请求并获取完整响应
     */
    suspend fun request(messages: List<Map<String, Any>>): ModelResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var timeToFirstToken: Long? = null
        var firstTokenReceived = false

        val rawContent = StringBuilder()

        var errorMessage: String? = null

        requestStream(messages).collect { chunk ->
            when (chunk) {
                is StreamChunk.Content -> {
                    if (!firstTokenReceived) {
                        timeToFirstToken = System.currentTimeMillis() - startTime
                        firstTokenReceived = true
                    }
                    rawContent.append(chunk.text)
                }
                is StreamChunk.Error -> {
                    errorMessage = chunk.message
                }
            }
        }

        // 如果有错误，返回错误信息而不是抛出异常
        if (errorMessage != null) {
            return@withContext ModelResponse(
                thinking = "",
                action = "{\"_metadata\": \"finish\", \"message\": \"$errorMessage\"}",
                rawContent = "",
                timeToFirstToken = timeToFirstToken,
                totalTime = System.currentTimeMillis() - startTime
            )
        }

        val totalTime = System.currentTimeMillis() - startTime
        val (thinking, action) = parseResponse(rawContent.toString())

        ModelResponse(
            thinking = thinking,
            action = action,
            rawContent = rawContent.toString(),
            timeToFirstToken = timeToFirstToken,
            totalTime = totalTime
        )
    }

    /**
     * 解析响应内容
     */
    private fun parseResponse(content: String): Pair<String, String> {
        // Rule 1: finish(message=
        if (content.contains("finish(message=")) {
            val parts = content.split("finish(message=", limit = 2)
            val thinking = parts[0].trim()
            val action = "finish(message=" + parts[1]
            return Pair(thinking, action)
        }

        // Rule 2: do(action=
        if (content.contains("do(action=")) {
            val parts = content.split("do(action=", limit = 2)
            val thinking = parts[0].trim()
            val action = "do(action=" + parts[1]
            return Pair(thinking, action)
        }

        // Rule 3: <answer> tag
        if (content.contains("<answer>")) {
            val parts = content.split("<answer>", limit = 2)
            val thinking = parts[0].replace("<think>", "").replace("</think>", "").trim()
            val action = parts[1].replace("</answer>", "").trim()
            return Pair(thinking, action)
        }

        return Pair("", content)
    }
}

sealed class StreamChunk {
    data class Content(val text: String) : StreamChunk()
    data class Error(val message: String) : StreamChunk()
}
