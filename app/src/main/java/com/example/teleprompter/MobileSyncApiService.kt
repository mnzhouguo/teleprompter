package com.example.teleprompter

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PC 端内容创作工作台的「口语稿」读写客户端。
 *
 * 接口约定见 content-creation/docs/api/third-party-speech-api.md：
 *   GET  /api/mobile/speeches      → 列表
 *   GET  /api/mobile/speeches/:id  → 详情
 *   POST /api/mobile/speeches      → 新增
 *   PUT  /api/mobile/speeches/:id  → 修改（全文覆盖）
 * 鉴权：请求头 X-CC-Token
 */
object MobileSyncApiService {

    private const val TOKEN_HEADER = "X-CC-Token"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 联调隧道域名（指向本机带 POST/PUT 的服务；Quick Tunnel 重启后需同步更新） */
    const val DEFAULT_BASE_URL = "https://smaller-commonwealth-spreading-pharmacy.trycloudflare.com"

    /** 联调 Token（见 third-party-speech-api.md） */
    const val DEFAULT_TOKEN = "r6E3WoGDu0BV_1gzQDYzYSNcCDIIImHt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteSpeech(
        val id: Long,
        val title: String,
        val updatedAt: String,
        val charCount: Int,
        val preview: String
    )

    data class RemoteSpeechDetail(
        val id: Long,
        val title: String,
        val content: String,
        val updatedAt: String
    )

    fun fetchSpeeches(baseUrl: String, token: String): Result<List<RemoteSpeech>> {
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/mobile/speeches")
            .header(TOKEN_HEADER, token)
            .get()
            .build()

        return executeRequest(request) { body ->
            val array = JSONObject(body).getJSONArray("speeches")
            val speeches = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                RemoteSpeech(
                    id = item.getLong("id"),
                    title = item.getString("title"),
                    updatedAt = item.optString("updatedAt"),
                    charCount = item.optInt("charCount"),
                    preview = item.optString("preview")
                )
            }
            sortByUpdatedAtDesc(speeches)
        }
    }

    fun fetchSpeech(baseUrl: String, token: String, id: Long): Result<RemoteSpeechDetail> {
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/mobile/speeches/$id")
            .header(TOKEN_HEADER, token)
            .get()
            .build()

        return executeRequest(request) { body -> parseSpeechDetail(body) }
    }

    /**
     * 新增进行中创作。title 为空时不传，由服务端生成。
     */
    fun createSpeech(
        baseUrl: String,
        token: String,
        content: String,
        title: String? = null
    ): Result<RemoteSpeechDetail> {
        val body = buildCreateBody(content, title)
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/mobile/speeches")
            .header(TOKEN_HEADER, token)
            .post(body)
            .build()
        return executeRequest(request) { parseSpeechDetail(it) }
    }

    /**
     * 修改进行中创作。title / content 至少一项；编辑页通常两者都传。
     */
    fun updateSpeech(
        baseUrl: String,
        token: String,
        id: Long,
        content: String? = null,
        title: String? = null
    ): Result<RemoteSpeechDetail> {
        val body = buildUpdateBody(content, title)
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(normalize(baseUrl) + "/api/mobile/speeches/$id")
            .header(TOKEN_HEADER, token)
            .put(body)
            .build()
        return executeRequest(request) { parseSpeechDetail(it) }
    }

    fun sortByUpdatedAtDesc(speeches: List<RemoteSpeech>): List<RemoteSpeech> =
        speeches.sortedWith(
            compareByDescending<RemoteSpeech> { it.updatedAt }
                .thenByDescending { it.id }
        )

    fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /** 供单测与请求共用：新增 body（不用 JSONObject，便于 JVM 单测） */
    fun buildCreateBody(content: String, title: String?): String {
        val parts = mutableListOf("\"content\":${jsonQuote(content)}")
        val trimmedTitle = title?.trim().orEmpty()
        if (trimmedTitle.isNotEmpty()) {
            parts.add("\"title\":${jsonQuote(trimmedTitle)}")
        }
        return "{${parts.joinToString(",")}}"
    }

    /** 供单测与请求共用：修改 body（至少一项） */
    fun buildUpdateBody(content: String?, title: String?): String {
        val parts = mutableListOf<String>()
        val trimmedTitle = title?.trim()
        val trimmedContent = content?.trim()
        if (!trimmedTitle.isNullOrEmpty()) {
            parts.add("\"title\":${jsonQuote(trimmedTitle)}")
        }
        if (content != null && trimmedContent != null && trimmedContent.isNotEmpty()) {
            // 保留原文空白，仅用 trim 判断非空
            parts.add("\"content\":${jsonQuote(content)}")
        }
        if (parts.isEmpty()) {
            throw IllegalArgumentException("请至少提供 title 或 content 之一")
        }
        return "{${parts.joinToString(",")}}"
    }

    fun jsonQuote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun parseSpeechDetail(body: String): RemoteSpeechDetail {
        val speech = JSONObject(body).getJSONObject("speech")
        return RemoteSpeechDetail(
            id = speech.getLong("id"),
            title = speech.getString("title"),
            content = speech.getString("content"),
            updatedAt = speech.optString("updatedAt")
        )
    }

    private inline fun <T> executeRequest(
        request: Request,
        parseResponse: (String) -> T
    ): Result<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(parseResponse(body))
                } else {
                    Result.failure(IOException(describeError(response.code, body)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun describeError(code: Int, body: String): String {
        val message = try {
            JSONObject(body).optString("error")
        } catch (e: Exception) {
            ""
        }
        val reason = message.ifEmpty { "HTTP $code" }
        return when (code) {
            400 -> "参数错误（$reason）"
            401 -> "访问 token 无效（$reason）"
            404 -> "该口语稿不存在或不可用（$reason）"
            503 -> "PC 端未配置 CC_MOBILE_TOKEN（$reason）"
            else -> "请求失败：$reason"
        }
    }
}
