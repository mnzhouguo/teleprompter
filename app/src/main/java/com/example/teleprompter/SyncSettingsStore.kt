package com.example.teleprompter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机端同步配置与缓存（本地持久化）。
 *
 * - baseUrl / token：优先用用户在「同步设置」里手填的值；
 *   未填写时回退到 [MobileSyncApiService] 文档联调默认值
 * - 列表缓存：上次同步成功的结果，离线时仍可查看（按更新时间倒序）
 */
object SyncSettingsStore {

    private const val PREF = "teleprompter_sync"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_CACHED_LIST = "cached_list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 用户手填值；空表示尚未自定义 */
    fun getStoredBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "").orEmpty().trim()

    fun getStoredToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty().trim()

    /** 实际请求用的地址：手填优先，否则用文档默认 */
    fun getBaseUrl(context: Context): String =
        getStoredBaseUrl(context).ifEmpty { MobileSyncApiService.DEFAULT_BASE_URL }

    fun getToken(context: Context): String =
        getStoredToken(context).ifEmpty { MobileSyncApiService.DEFAULT_TOKEN }

    fun save(context: Context, baseUrl: String, token: String) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    /** 始终可用：至少有文档默认联调地址与 token */
    fun isConfigured(context: Context): Boolean =
        getBaseUrl(context).isNotEmpty() && getToken(context).isNotEmpty()

    fun loadCachedSpeeches(context: Context): List<MobileSyncApiService.RemoteSpeech> {
        val raw = prefs(context).getString(KEY_CACHED_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val speeches = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                MobileSyncApiService.RemoteSpeech(
                    id = item.getLong("id"),
                    title = item.getString("title"),
                    updatedAt = item.optString("updatedAt"),
                    charCount = item.optInt("charCount"),
                    preview = item.optString("preview")
                )
            }
            MobileSyncApiService.sortByUpdatedAtDesc(speeches)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedSpeeches(context: Context, speeches: List<MobileSyncApiService.RemoteSpeech>) {
        val sorted = MobileSyncApiService.sortByUpdatedAtDesc(speeches)
        val array = JSONArray()
        sorted.forEach { speech ->
            array.put(
                JSONObject()
                    .put("id", speech.id)
                    .put("title", speech.title)
                    .put("updatedAt", speech.updatedAt)
                    .put("charCount", speech.charCount)
                    .put("preview", speech.preview)
            )
        }
        prefs(context).edit().putString(KEY_CACHED_LIST, array.toString()).apply()
    }
}
