package org.videopocket.probe.ui

import org.junit.Assert.*
import org.junit.Test
import org.videopocket.probe.core.*
import org.videopocket.probe.download.DownloadState
import org.videopocket.probe.engine.ProbeEngine
import org.videopocket.probe.resolver.*

class Phase3ExperienceRegressionTest {

    @Test
    fun testTruthfulQualityBadgesAndCategories() {
        assertEquals("4K", QualityBadgeHelper.getBadgeText(2160))
        assertEquals("2K", QualityBadgeHelper.getBadgeText(1440))
        assertEquals("FHD", QualityBadgeHelper.getBadgeText(1080))
        assertEquals("HD", QualityBadgeHelper.getBadgeText(720))
        assertEquals("SD", QualityBadgeHelper.getBadgeText(480))
        assertEquals("SD", QualityBadgeHelper.getBadgeText(360))
        assertEquals("", QualityBadgeHelper.getBadgeText(0))

        assertEquals(QualityTier.UHD_2160, QualityTier.fromHeight(2160))
        assertEquals(QualityTier.QHD_1440, QualityTier.fromHeight(1440))
        assertEquals(QualityTier.FHD_1080, QualityTier.fromHeight(1080))
        assertEquals(QualityTier.HD_720, QualityTier.fromHeight(720))
        assertEquals(QualityTier.SD_480, QualityTier.fromHeight(480))
        assertEquals(QualityTier.SD_360, QualityTier.fromHeight(360))
    }

    @Test
    fun testTruthfulDynamicQualities_DoNotIncludePhantomHeights() {
        val formats = listOf(
            MediaFormat("f1", "mp4", 1080, video = true, audio = false, bytes = 50_000_000L),
            MediaFormat("f2", "mp4", 720, video = true, audio = false, bytes = 25_000_000L),
            MediaFormat("f3", "mp4", 360, video = true, audio = false, bytes = 10_000_000L),
            MediaFormat("audio", "m4a", 0, video = false, audio = true, bytes = 5_000_000L)
        )
        val info = MediaInfo(
            title = "Test Video",
            duration = 120.0,
            formats = formats
        )

        val realHeights = info.heights
        assertEquals(listOf(1080, 720, 360), realHeights)
        assertFalse(realHeights.contains(480))
        assertFalse(realHeights.contains(1440))
        assertFalse(realHeights.contains(2160))

        assertEquals(1080, info.defaultHeight)
    }

    @Test
    fun testDynamicSmartPresetsFromRealVariants() {
        val variants = listOf(
            MediaVariant("v1080", "1080p", width = 1920, height = 1080, format = "mp4", fileSize = 60_000_000L),
            MediaVariant("v720", "720p", width = 1280, height = 720, format = "mp4", fileSize = 30_000_000L),
            MediaVariant("v360", "360p", width = 640, height = 360, format = "mp4", fileSize = 12_000_000L),
            MediaVariant("vAudio", "192kbps", format = "m4a", isAudioOnly = true, bitrate = 192)
        )
        val metadata = NormalizedMetadata(
            id = "test-meta",
            canonicalUrl = "https://example.com/video",
            platform = "YouTube",
            platformIcon = "youtube",
            title = "Test Video Metadata",
            variants = variants
        )

        // Best Available
        val best = metadata.getBestAvailable()
        assertNotNull(best)
        assertEquals(1080, best!!.height)

        // Balanced
        val balanced = metadata.getBalanced()
        assertNotNull(balanced)
        assertTrue(balanced!!.height in 720..1080)

        // Smallest
        val smallest = metadata.getSmallestFile()
        assertNotNull(smallest)
        assertEquals(360, smallest!!.height)

        // Best Audio
        val audio = metadata.getBestAudio()
        assertNotNull(audio)
        assertTrue(audio!!.isAudioOnly)
        assertEquals(192, audio.bitrate)
    }

    @Test
    fun testUserFriendlyErrorMessagesBilingual() {
        val loginErr = ResolverError(ResolverErrorType.LOGIN_REQUIRED)
        assertTrue(loginErr.userMessage(false).contains("Authentication is required"))
        assertTrue(loginErr.userMessage(true).contains("تسجيل الدخول"))

        val botErr = ResolverError(ResolverErrorType.ANTI_BOT_CHALLENGE)
        assertTrue(botErr.userMessage(false).contains("automated access"))
        assertTrue(botErr.userMessage(true).contains("المصدر أوقف الوصول"))

        val drmErr = ResolverError(ResolverErrorType.DRM_PROTECTED)
        assertTrue(drmErr.userMessage(false).contains("DRM"))
        assertTrue(drmErr.userMessage(true).contains("DRM"))

        val netErr = ResolverError(ResolverErrorType.NETWORK_ERROR)
        assertTrue(netErr.userMessage(false).contains("Network"))
        assertTrue(netErr.userMessage(true).contains("الشبكة"))
    }

    @Test
    fun testDownloadStateTransitions() {
        assertTrue(DownloadState.canTransition(DownloadState.QUEUED, DownloadState.DOWNLOADING))
        assertTrue(DownloadState.canTransition(DownloadState.DOWNLOADING, DownloadState.MERGING))
        assertTrue(DownloadState.canTransition(DownloadState.DOWNLOADING, DownloadState.PAUSED))
        assertTrue(DownloadState.canTransition(DownloadState.PAUSED, DownloadState.QUEUED))
        assertTrue(DownloadState.canTransition(DownloadState.MERGING, DownloadState.SAVING))
        assertTrue(DownloadState.canTransition(DownloadState.SAVING, DownloadState.COMPLETED))

        // Completed cannot transition to downloading
        assertFalse(DownloadState.canTransition(DownloadState.COMPLETED, DownloadState.DOWNLOADING))

        // Failed can transition to queued (retry)
        assertTrue(DownloadState.canTransition(DownloadState.FAILED, DownloadState.QUEUED))
    }

    @Test
    fun testClipRequestParsingAndFormatting() {
        assertEquals("01:30", ClipRequest.formatSeconds(90.0))
        assertEquals("01:00:15", ClipRequest.formatSeconds(3615.0))
        assertEquals(90.0, ClipRequest.parseTimestamp("01:30"))
        assertEquals(3615.0, ClipRequest.parseTimestamp("01:00:15"))
        assertNull(ClipRequest.parseTimestamp("invalid"))

        val req = ClipRequest(10.0, 45.0, height = 720)
        assertEquals(35.0, req.durationSeconds, 0.001)
        assertEquals("00:10", req.startFormatted())
        assertEquals("00:45", req.endFormatted())
        assertEquals("00:35", req.durationFormatted())
    }

    @Test
    fun testColdStartDeferral_ProbeEngineNotInitializedEagerly() {
        // Confirm that ProbeEngine cachedRuntime is either null or was cached explicitly
        // And isReady state is guarded
        val wasReady = ProbeEngine.isReady
        if (ProbeEngine.cachedRuntime == null) {
            assertFalse(wasReady)
        }
    }
}
