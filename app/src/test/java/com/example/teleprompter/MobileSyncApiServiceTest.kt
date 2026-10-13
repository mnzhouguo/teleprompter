package com.example.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSyncApiServiceTest {

    @Test
    fun sortByUpdatedAtDesc_putsNewestFirst() {
        val speeches = listOf(
            speech(1, "2026-10-08 09:27:50"),
            speech(2, "2026-10-11 09:21:37"),
            speech(3, "2026-10-10 01:55:32"),
            speech(4, "2026-10-11 09:20:47")
        )
        val sorted = MobileSyncApiService.sortByUpdatedAtDesc(speeches)
        assertEquals(
            listOf(2L, 4L, 3L, 1L),
            sorted.map { it.id }
        )
    }

    @Test
    fun sortByUpdatedAtDesc_breaksTiesByIdDescending() {
        val speeches = listOf(
            speech(10, "2026-10-11 09:00:00"),
            speech(20, "2026-10-11 09:00:00")
        )
        val sorted = MobileSyncApiService.sortByUpdatedAtDesc(speeches)
        assertEquals(listOf(20L, 10L), sorted.map { it.id })
    }

    @Test
    fun defaultsMatchThirdPartySpeechApiDoc() {
        assertTrue(MobileSyncApiService.DEFAULT_BASE_URL.startsWith("https://"))
        assertTrue(MobileSyncApiService.DEFAULT_TOKEN.isNotBlank())
        assertTrue(
            MobileSyncApiService.DEFAULT_BASE_URL.contains("trycloudflare.com")
        )
    }

    @Test
    fun normalize_trimsTrailingSlash() {
        assertEquals(
            "https://example.com",
            MobileSyncApiService.normalize("https://example.com/")
        )
    }

    @Test
    fun buildCreateBody_includesTitleWhenPresent() {
        val body = MobileSyncApiService.buildCreateBody("正文内容", "我的标题")
        assertTrue(body.contains("\"content\""))
        assertTrue(body.contains("正文内容"))
        assertTrue(body.contains("\"title\""))
        assertTrue(body.contains("我的标题"))
    }

    @Test
    fun buildCreateBody_omitsBlankTitle() {
        val body = MobileSyncApiService.buildCreateBody("只有正文", "  ")
        assertTrue(body.contains("只有正文"))
        assertFalse(body.contains("\"title\""))
    }

    @Test
    fun buildUpdateBody_requiresAtLeastOneField() {
        try {
            MobileSyncApiService.buildUpdateBody(null, null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("title") || e.message!!.contains("content"))
        }
    }

    @Test
    fun buildUpdateBody_sendsTitleAndContent() {
        val body = MobileSyncApiService.buildUpdateBody("最终全文\n第二段", "修订标题")
        assertTrue(body.contains("修订标题"))
        assertTrue(body.contains("最终全文"))
        assertTrue(body.contains("第二段"))
    }

    @Test
    fun buildUpdateBody_contentOnly() {
        val body = MobileSyncApiService.buildUpdateBody("仅改正文", null)
        assertTrue(body.contains("仅改正文"))
        assertFalse(body.contains("\"title\""))
    }

    private fun speech(id: Long, updatedAt: String) =
        MobileSyncApiService.RemoteSpeech(
            id = id,
            title = "t$id",
            updatedAt = updatedAt,
            charCount = 1,
            preview = "p"
        )
}
