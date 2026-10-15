package com.example.teleprompter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 口语稿待同步队列 + 详情缓存（SharedPreferences）。
 */
object PendingSpeechStore {

    private const val PREF = "teleprompter_sync"
    private const val KEY_PENDING = "pending_speech_changes"
    private const val KEY_DETAIL_PREFIX = "speech_detail_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun newLocalKey(): String = UUID.randomUUID().toString()

    fun loadPending(context: Context): List<PendingSpeechSyncLogic.PendingChange> {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                PendingSpeechSyncLogic.PendingChange(
                    localKey = item.getString("localKey"),
                    kind = PendingSpeechSyncLogic.Kind.valueOf(item.getString("kind")),
                    remoteId = if (item.has("remoteId") && !item.isNull("remoteId")) {
                        item.getLong("remoteId")
                    } else {
                        null
                    },
                    title = item.optString("title"),
                    content = item.optString("content"),
                    baselineUpdatedAt = item.optString("baselineUpdatedAt"),
                    savedAtMillis = item.optLong("savedAtMillis")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePending(context: Context, pending: List<PendingSpeechSyncLogic.PendingChange>) {
        val array = JSONArray()
        pending.forEach { change ->
            array.put(
                JSONObject()
                    .put("localKey", change.localKey)
                    .put("kind", change.kind.name)
                    .put("remoteId", change.remoteId)
                    .put("title", change.title)
                    .put("content", change.content)
                    .put("baselineUpdatedAt", change.baselineUpdatedAt)
                    .put("savedAtMillis", change.savedAtMillis)
            )
        }
        prefs(context).edit().putString(KEY_PENDING, array.toString()).apply()
    }

    fun upsertCreate(
        context: Context,
        localKey: String,
        title: String,
        content: String
    ): PendingSpeechSyncLogic.PendingChange {
        val now = System.currentTimeMillis()
        val next = PendingSpeechSyncLogic.upsertCreate(
            existing = loadPending(context),
            localKey = localKey,
            title = title,
            content = content,
            savedAtMillis = now
        )
        savePending(context, next)
        return next.first { it.localKey == localKey }
    }

    fun upsertUpdate(
        context: Context,
        localKey: String,
        remoteId: Long,
        title: String,
        content: String,
        baselineUpdatedAt: String
    ): PendingSpeechSyncLogic.PendingChange {
        val now = System.currentTimeMillis()
        val next = PendingSpeechSyncLogic.upsertUpdate(
            existing = loadPending(context),
            localKey = localKey,
            remoteId = remoteId,
            title = title,
            content = content,
            baselineUpdatedAt = baselineUpdatedAt,
            savedAtMillis = now
        )
        savePending(context, next)
        return next.first { it.remoteId == remoteId && it.kind == PendingSpeechSyncLogic.Kind.UPDATE }
    }

    fun remove(context: Context, localKey: String) {
        savePending(context, PendingSpeechSyncLogic.removeByLocalKey(loadPending(context), localKey))
    }

    data class CachedDetail(
        val id: Long,
        val title: String,
        val content: String,
        val updatedAt: String
    )

    fun saveDetail(context: Context, detail: MobileSyncApiService.RemoteSpeechDetail) {
        val json = JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("content", detail.content)
            .put("updatedAt", detail.updatedAt)
            .toString()
        // commit：保证断网前全文已落盘，离线编辑/录制可用
        prefs(context).edit().putString(KEY_DETAIL_PREFIX + detail.id, json).commit()
    }

    fun loadDetail(context: Context, id: Long): CachedDetail? {
        val raw = prefs(context).getString(KEY_DETAIL_PREFIX + id, null) ?: return null
        return try {
            val item = JSONObject(raw)
            CachedDetail(
                id = item.getLong("id"),
                title = item.getString("title"),
                content = item.getString("content"),
                updatedAt = item.optString("updatedAt")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun hasFullContent(context: Context, id: Long): Boolean {
        val detail = loadDetail(context, id) ?: return false
        return detail.content.isNotBlank()
    }
}
