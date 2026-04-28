package com.example.teleprompter

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 提词文稿API服务
 * Base URL: http://49.234.19.222:4003
 */
object VideoScriptApiService {

    private const val BASE_URL = "http://49.234.19.222:4003"
    private const val API_PATH = "/api/video-scripts"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * API响应的数据结构
     */
    data class ApiScript(
        val id: Long,
        val title: String,
        val content: String,
        val word_count: Int,
        val created_at: String,
        val updated_at: String
    )

    data class ListResponse(
        val scripts: List<ApiScript>,
        val count: Int
    )

    data class SingleResponse(
        val script: ApiScript
    )

    /**
     * 获取文稿列表
     * @param search 搜索关键词（可选）
     */
    fun fetchScripts(search: String? = null): Result<ListResponse> {
        val urlBuilder = HttpUrl.Builder()
            .scheme("http")
            .host("49.234.19.222")
            .port(4003)
            .addPathSegments("api/video-scripts")

        if (search != null && search.isNotEmpty()) {
            urlBuilder.addQueryParameter("search", search)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        return executeRequest(request) { body ->
            val json = JSONObject(body)
            val scriptsArray = json.getJSONArray("scripts")
            val scripts = mutableListOf<ApiScript>()

            for (i in 0 until scriptsArray.length()) {
                val item = scriptsArray.getJSONObject(i)
                scripts.add(parseApiScript(item))
            }

            ListResponse(
                scripts = scripts,
                count = json.getInt("count")
            )
        }
    }

    /**
     * 获取单个文稿详情
     */
    fun fetchScript(id: Long): Result<ApiScript> {
        val request = Request.Builder()
            .url("$BASE_URL$API_PATH/$id")
            .get()
            .build()

        return executeRequest(request) { body ->
            val json = JSONObject(body)
            parseApiScript(json.getJSONObject("script"))
        }
    }

    /**
     * 创建新文稿
     */
    fun createScript(title: String, content: String): Result<ApiScript> {
        val jsonBody = JSONObject()
            .put("title", title)
            .put("content", content)

        val request = Request.Builder()
            .url("$BASE_URL$API_PATH")
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request) { body ->
            val json = JSONObject(body)
            parseApiScript(json.getJSONObject("script"))
        }
    }

    /**
     * 更新文稿
     */
    fun updateScript(id: Long, title: String?, content: String?): Result<ApiScript> {
        val jsonBody = JSONObject()
        if (title != null) jsonBody.put("title", title)
        if (content != null) jsonBody.put("content", content)

        val request = Request.Builder()
            .url("$BASE_URL$API_PATH/$id")
            .put(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        return executeRequest(request) { body ->
            val json = JSONObject(body)
            parseApiScript(json.getJSONObject("script"))
        }
    }

    /**
     * 删除文稿
     */
    fun deleteScript(id: Long): Result<Boolean> {
        val request = Request.Builder()
            .url("$BASE_URL$API_PATH/$id")
            .delete()
            .build()

        return executeRequest(request) { body ->
            val json = JSONObject(body)
            json.getBoolean("success")
        }
    }

    /**
     * 解析API返回的script对象
     */
    private fun parseApiScript(json: JSONObject): ApiScript {
        return ApiScript(
            id = json.getLong("id"),
            title = json.getString("title"),
            content = json.getString("content"),
            word_count = json.getInt("word_count"),
            created_at = json.getString("created_at"),
            updated_at = json.getString("updated_at")
        )
    }

    /**
     * 执行请求并解析响应
     */
    private inline fun <T> executeRequest(
        request: Request,
        parseResponse: (String) -> T
    ): Result<T> {
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return Result.failure(IOException("Empty response body"))
                Result.success(parseResponse(body))
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Result.failure(IOException("HTTP ${response.code}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 将ApiScript转换为本地Script模型
     */
    fun toScript(apiScript: ApiScript): Script {
        return Script(
            id = apiScript.id,
            title = apiScript.title,
            content = apiScript.content,
            createdAt = parseApiDate(apiScript.created_at),
            updatedAt = parseApiDate(apiScript.updated_at)
        )
    }

    /**
     * 解析API日期格式（ISO 8601）
     */
    private fun parseApiDate(dateStr: String): Long {
        return try {
            // 格式: 2024-01-15T10:30:00.000Z 或类似
            val cleanStr = dateStr.replace("Z", "").replace("T", " ")
            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
            format.parse(cleanStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}