package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSpeechSyncLogicTest {

    @Test
    fun isRemoteConflict_whenUpdatedAtChanged() {
        assertTrue(
            PendingSpeechSyncLogic.isRemoteConflict(
                "2026-10-11 09:00:00",
                "2026-10-11 10:00:00"
            )
        )
    }

    @Test
    fun isRemoteConflict_whenSameBaseline_noConflict() {
        assertFalse(
            PendingSpeechSyncLogic.isRemoteConflict(
                "2026-10-11 09:00:00",
                "2026-10-11 09:00:00"
            )
        )
    }

    @Test
    fun isRemoteConflict_blankBaseline_allowsPush() {
        assertFalse(PendingSpeechSyncLogic.isRemoteConflict("", "2026-10-11 09:00:00"))
    }

    @Test
    fun localDisplayId_isNegative() {
        val id = PendingSpeechSyncLogic.localDisplayId("abc-123")
        assertTrue(id < 0)
        assertTrue(PendingSpeechSyncLogic.isLocalPendingId(id))
    }

    @Test
    fun mergeForDisplay_putsCreatesFirst_andOverlaysUpdates() {
        val remote = listOf(
            speech(10, "远端标题", "2026-10-11 09:00:00", 3, "远端")
        )
        val pending = listOf(
            PendingSpeechSyncLogic.PendingChange(
                localKey = "c1",
                kind = PendingSpeechSyncLogic.Kind.CREATE,
                remoteId = null,
                title = "本地新建",
                content = "新建正文",
                baselineUpdatedAt = "",
                savedAtMillis = 100
            ),
            PendingSpeechSyncLogic.PendingChange(
                localKey = "u1",
                kind = PendingSpeechSyncLogic.Kind.UPDATE,
                remoteId = 10L,
                title = "本地改过",
                content = "本地正文很长",
                baselineUpdatedAt = "2026-10-11 09:00:00",
                savedAtMillis = 200
            )
        )
        val merged = PendingSpeechSyncLogic.mergeForDisplay(remote, pending)
        assertEquals(2, merged.size)
        assertEquals("本地新建", merged[0].title)
        assertTrue(merged[0].id < 0)
        assertEquals(10L, merged[1].id)
        assertEquals("本地改过", merged[1].title)
        assertEquals("待同步", merged[1].updatedAt)
        assertEquals("本地正文很长".length, merged[1].charCount)
    }

    @Test
    fun upsertUpdate_replacesSameRemoteId() {
        val first = PendingSpeechSyncLogic.upsertUpdate(
            existing = emptyList(),
            localKey = "a",
            remoteId = 5L,
            title = "v1",
            content = "c1",
            baselineUpdatedAt = "t0",
            savedAtMillis = 1
        )
        val second = PendingSpeechSyncLogic.upsertUpdate(
            existing = first,
            localKey = "b",
            remoteId = 5L,
            title = "v2",
            content = "c2",
            baselineUpdatedAt = "t0",
            savedAtMillis = 2
        )
        assertEquals(1, second.size)
        assertEquals("v2", second[0].title)
        assertEquals("c2", second[0].content)
    }

    private fun speech(
        id: Long,
        title: String,
        updatedAt: String,
        charCount: Int,
        preview: String
    ) = MobileSyncApiService.RemoteSpeech(id, title, updatedAt, charCount, preview)
}
