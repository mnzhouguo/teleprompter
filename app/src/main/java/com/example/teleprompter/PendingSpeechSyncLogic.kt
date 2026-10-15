package com.example.teleprompter

/**
 * 口语稿离线待同步的纯逻辑（无 Android 依赖，便于 JVM 单测）。
 *
 * 冲突策略：编辑基线 updatedAt 与远端不一致时，丢弃本地待同步，以远程为准。
 */
object PendingSpeechSyncLogic {

    enum class Kind { CREATE, UPDATE }

    data class PendingChange(
        val localKey: String,
        val kind: Kind,
        val remoteId: Long?,
        val title: String,
        val content: String,
        /** 打开编辑时服务端的 updatedAt；CREATE 为空 */
        val baselineUpdatedAt: String,
        val savedAtMillis: Long
    )

    /** 远端相对基线已变化 → 冲突，以远程为准 */
    fun isRemoteConflict(baselineUpdatedAt: String, remoteUpdatedAt: String): Boolean {
        if (baselineUpdatedAt.isBlank()) return false
        if (remoteUpdatedAt.isBlank()) return false
        return baselineUpdatedAt != remoteUpdatedAt
    }

    /** 本地新建用的稳定负 id（列表展示 / 点击识别） */
    fun localDisplayId(localKey: String): Long {
        val h = localKey.hashCode().toLong()
        val mag = if (h == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(h)
        return -mag.coerceAtLeast(1L)
    }

    fun isLocalPendingId(id: Long): Boolean = id < 0

    fun toListItem(change: PendingChange): MobileSyncApiService.RemoteSpeech {
        val title = change.title.trim().ifEmpty { "未命名草稿" }
        val preview = change.content.trim().replace("\n", " ").take(80)
        val id = when (change.kind) {
            Kind.CREATE -> localDisplayId(change.localKey)
            Kind.UPDATE -> change.remoteId ?: localDisplayId(change.localKey)
        }
        return MobileSyncApiService.RemoteSpeech(
            id = id,
            title = title,
            updatedAt = "待同步",
            charCount = change.content.length,
            preview = preview
        )
    }

    /**
     * 远程列表 + 待同步合并：
     * - CREATE：插到列表最前
     * - UPDATE：覆盖同 id 的标题/预览/字数，并标记 updatedAt=待同步
     */
    fun mergeForDisplay(
        remote: List<MobileSyncApiService.RemoteSpeech>,
        pending: List<PendingChange>
    ): List<MobileSyncApiService.RemoteSpeech> {
        val updateByRemoteId = pending
            .filter { it.kind == Kind.UPDATE && it.remoteId != null }
            .associateBy { it.remoteId!! }

        val mergedRemote = remote.map { item ->
            val local = updateByRemoteId[item.id] ?: return@map item
            toListItem(local).copy(
                id = item.id,
                // 保留远程时间信息不够准确时，用「待同步」提示用户
                updatedAt = "待同步"
            )
        }

        val creates = pending
            .filter { it.kind == Kind.CREATE }
            .sortedByDescending { it.savedAtMillis }
            .map { toListItem(it) }

        return creates + mergedRemote
    }

    fun pendingRemoteIds(pending: List<PendingChange>): Set<Long> =
        pending.mapNotNull { change ->
            when (change.kind) {
                Kind.CREATE -> localDisplayId(change.localKey)
                Kind.UPDATE -> change.remoteId
            }
        }.toSet()

    fun findByLocalDisplayId(pending: List<PendingChange>, displayId: Long): PendingChange? =
        pending.firstOrNull {
            it.kind == Kind.CREATE && localDisplayId(it.localKey) == displayId
        }

    fun findUpdateByRemoteId(pending: List<PendingChange>, remoteId: Long): PendingChange? =
        pending.firstOrNull { it.kind == Kind.UPDATE && it.remoteId == remoteId }

    /**
     * 保存新建：若已有同 localKey 则覆盖；否则追加。
     * 无 localKey 时生成调用方传入的新 key。
     */
    fun upsertCreate(
        existing: List<PendingChange>,
        localKey: String,
        title: String,
        content: String,
        savedAtMillis: Long
    ): List<PendingChange> {
        val next = PendingChange(
            localKey = localKey,
            kind = Kind.CREATE,
            remoteId = null,
            title = title,
            content = content,
            baselineUpdatedAt = "",
            savedAtMillis = savedAtMillis
        )
        val without = existing.filterNot { it.localKey == localKey }
        return without + next
    }

    /** 保存编辑：同一 remoteId 只保留最新一条 */
    fun upsertUpdate(
        existing: List<PendingChange>,
        localKey: String,
        remoteId: Long,
        title: String,
        content: String,
        baselineUpdatedAt: String,
        savedAtMillis: Long
    ): List<PendingChange> {
        val next = PendingChange(
            localKey = localKey,
            kind = Kind.UPDATE,
            remoteId = remoteId,
            title = title,
            content = content,
            baselineUpdatedAt = baselineUpdatedAt,
            savedAtMillis = savedAtMillis
        )
        val without = existing.filterNot {
            it.kind == Kind.UPDATE && it.remoteId == remoteId
        }
        return without + next
    }

    fun removeByLocalKey(existing: List<PendingChange>, localKey: String): List<PendingChange> =
        existing.filterNot { it.localKey == localKey }
}
