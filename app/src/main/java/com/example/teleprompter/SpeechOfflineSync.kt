package com.example.teleprompter

import android.content.Context

/**
 * 将本地待同步口语稿推到服务端。
 *
 * - CREATE：直接 POST
 * - UPDATE：先拉远端；若与基线冲突则丢弃本地（远程优先）；否则 PUT
 */
object SpeechOfflineSync {

    data class FlushResult(
        val synced: Int,
        val discardedByConflict: Int,
        val failed: Int
    )

    fun flush(context: Context): FlushResult {
        val baseUrl = SyncSettingsStore.getBaseUrl(context)
        val token = SyncSettingsStore.getToken(context)
        var synced = 0
        var discarded = 0
        var failed = 0

        val pending = PendingSpeechStore.loadPending(context)
        for (change in pending) {
            when (change.kind) {
                PendingSpeechSyncLogic.Kind.CREATE -> {
                    val result = MobileSyncApiService.createSpeech(
                        baseUrl = baseUrl,
                        token = token,
                        content = change.content,
                        title = change.title.trim().ifEmpty { null }
                    )
                    if (result.isSuccess) {
                        val detail = result.getOrThrow()
                        PendingSpeechStore.remove(context, change.localKey)
                        PendingSpeechStore.saveDetail(context, detail)
                        synced++
                    } else {
                        failed++
                    }
                }
                PendingSpeechSyncLogic.Kind.UPDATE -> {
                    val remoteId = change.remoteId
                    if (remoteId == null) {
                        PendingSpeechStore.remove(context, change.localKey)
                        discarded++
                    } else {
                        val remoteResult = MobileSyncApiService.fetchSpeech(baseUrl, token, remoteId)
                        if (remoteResult.isFailure) {
                            failed++
                        } else {
                            val remote = remoteResult.getOrThrow()
                            if (PendingSpeechSyncLogic.isRemoteConflict(
                                    change.baselineUpdatedAt,
                                    remote.updatedAt
                                )
                            ) {
                                PendingSpeechStore.remove(context, change.localKey)
                                PendingSpeechStore.saveDetail(context, remote)
                                discarded++
                            } else {
                                val updateResult = MobileSyncApiService.updateSpeech(
                                    baseUrl = baseUrl,
                                    token = token,
                                    id = remoteId,
                                    content = change.content,
                                    title = change.title.trim().ifEmpty { null }
                                )
                                if (updateResult.isSuccess) {
                                    val detail = updateResult.getOrThrow()
                                    PendingSpeechStore.remove(context, change.localKey)
                                    PendingSpeechStore.saveDetail(context, detail)
                                    synced++
                                } else {
                                    failed++
                                }
                            }
                        }
                    }
                }
            }
        }
        return FlushResult(synced = synced, discardedByConflict = discarded, failed = failed)
    }

    /** 用待同步项刷新列表缓存中的展示数据 */
    fun mergeCachedListWithPending(context: Context): List<MobileSyncApiService.RemoteSpeech> {
        val cached = SyncSettingsStore.loadCachedSpeeches(context)
        val pending = PendingSpeechStore.loadPending(context)
        return PendingSpeechSyncLogic.mergeForDisplay(cached, pending)
    }
}
