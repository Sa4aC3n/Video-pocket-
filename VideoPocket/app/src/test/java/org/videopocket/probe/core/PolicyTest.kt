package org.videopocket.probe.core

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun rejectsNonWebSchemesCredentialsAndCommandText() {
        listOf("file:///sdcard/a.mp4", "javascript:alert(1)", "--exec=bad", "https://user:pass@example.org/v", "https://example.org/v\n--exec=bad", "https:///missing-host", "https://example.org/a b").forEach { input ->
            assertThrows(ProbeFailure::class.java) { LinkPolicy.validate(input) }
        }
    }
    @Test fun signedQueryIsPreservedWithoutShellInterpretation() {
        val url = "https://example.org/video.mp4?token=a%2Bb&expires=123"
        assertEquals(url, LinkPolicy.validate("  $url  "))
    }
    private val info = MediaInfo("test", 5.0, listOf(
        MediaFormat("a", "m4a", 0, false, true, 100),
        MediaFormat("v", "mp4", 1080, true, false, 1000),
        MediaFormat("low", "mp4", 360, true, true, 500),
        MediaFormat("4k", "webm", 2160, true, false, 2000)
    ))
    @Test fun defaultsTo1080AndExposes4k() {
        assertEquals(1080, info.defaultHeight)
        assertEquals(listOf(2160,1080,360), info.heights)
    }
    @Test fun mergeRequiresActualSeparateTracks() {
        assertEquals("v+a", FormatPolicy.select(info, ProbeAction.MERGE, 1080))
        assertThrows(ProbeFailure::class.java) { FormatPolicy.select(info, ProbeAction.MERGE, 360) }
    }
    @Test fun missingResolutionDoesNotSilentlyDowngrade() {
        assertThrows(ProbeFailure::class.java) { FormatPolicy.select(info, ProbeAction.VIDEO, 720) }
    }
    @Test fun videoAndAudioSelections() {
        assertEquals("low", FormatPolicy.select(info, ProbeAction.VIDEO, 360))
        assertEquals("v+a", FormatPolicy.select(info, ProbeAction.VIDEO, 1080))
        assertEquals("a", FormatPolicy.select(info, ProbeAction.MP3, 1080))
    }
    @Test fun rejectsUntrustedFormatExpressions() {
        val bad = info.copy(formats = listOf(MediaFormat("best/evil", "mp4", 360, true, true, 1)))
        assertThrows(ProbeFailure::class.java) { FormatPolicy.select(bad, ProbeAction.VIDEO, 360) }
    }
}
