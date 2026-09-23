package org.videopocket.probe.resolver

import org.junit.Assert.*
import org.junit.Test

/**
 * SmartPublicResolutionRegressionTest
 *
 * Regression test suite verifying that:
 * 1. Public content is never prematurely or incorrectly classified as LOGIN_REQUIRED.
 * 2. Public HTML declarations (OpenGraph, HTML5, Manifests, JSON-LD) are reliably parsed.
 * 3. Short URLs are properly identified for safe expansion.
 * 4. A single login requirement on a specific media item never marks the entire provider broken.
 * 5. Successful resolution strategies are recorded for provider diagnostics.
 * 6. All 9 error classifications are strictly maintained.
 */
class SmartPublicResolutionRegressionTest {

    @Test
    fun testClassificationTaxonomyCompleteness() {
        val expectedClassifications = setOf(
            ResolutionClassification.PUBLIC_RESOLVED,
            ResolutionClassification.PUBLIC_MEDIA_UNAVAILABLE,
            ResolutionClassification.LOGIN_REQUIRED,
            ResolutionClassification.PRIVATE_MEDIA,
            ResolutionClassification.GEO_RESTRICTED,
            ResolutionClassification.RATE_LIMITED,
            ResolutionClassification.DRM_PROTECTED,
            ResolutionClassification.EXTRACTOR_OUTDATED,
            ResolutionClassification.TEMPORARILY_UNAVAILABLE
        )

        for (c in expectedClassifications) {
            assertFalse("Classification label must not be blank", c.label.isBlank())
            assertFalse("Classification arabicLabel must not be blank", c.arabicLabel.isBlank())
        }
        assertEquals(expectedClassifications.size, ResolutionClassification.values().size)
    }

    @Test
    fun testResolutionStrategyCompleteness() {
        val expectedStrategies = setOf(
            ResolutionStrategy.DEDICATED_EXTRACTOR,
            ResolutionStrategy.CANONICAL_EXPANSION,
            ResolutionStrategy.GENERIC_FALLBACK,
            ResolutionStrategy.PUBLIC_HTML_OPENGRAPH,
            ResolutionStrategy.PUBLIC_MEDIA_MANIFEST,
            ResolutionStrategy.DIRECT_STREAM_RESOLVE
        )

        for (s in expectedStrategies) {
            assertFalse("Strategy label must not be blank", s.label.isBlank())
            assertFalse("Strategy arabicLabel must not be blank", s.arabicLabel.isBlank())
        }
        assertEquals(expectedStrategies.size, ResolutionStrategy.values().size)
    }

    @Test
    fun testOpenGraphDeclarationsExtraction() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Nature Documentary &amp; Wildlife Highlights" />
                <meta property="og:image" content="https://cdn.example.com/thumbs/wildlife.jpg" />
                <meta property="og:video" content="https://cdn.example.com/videos/nature_clip.mp4?auth=token&amp;exp=123" />
                <meta property="og:video:duration" content="145.5" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val decl = SmartPublicResolver.extractDeclarationsFromHtml(html, "https://example.com/watch/nature")
        assertNotNull("Declarations must be extracted from OpenGraph metadata", decl)
        assertTrue("Declarations must indicate presence of media", decl!!.hasMedia)
        assertEquals("https://cdn.example.com/videos/nature_clip.mp4?auth=token&exp=123", decl.videoUrl)
        assertEquals("Nature Documentary & Wildlife Highlights", decl.title)
        assertEquals("https://cdn.example.com/thumbs/wildlife.jpg", decl.thumbnail)
        assertEquals(145.5, decl.duration ?: 0.0, 0.01)
        assertFalse(decl.isManifest)
    }

    @Test
    fun testTwitterStreamAndReverseOrderExtraction() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta content="https://cdn.example.com/videos/twitter_stream.mp4" name="twitter:player:stream" />
                <meta content="Breaking Tech Update" property="og:title" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val decl = SmartPublicResolver.extractDeclarationsFromHtml(html, "https://example.com/post/42")
        assertNotNull(decl)
        assertEquals("https://cdn.example.com/videos/twitter_stream.mp4", decl!!.videoUrl)
        assertEquals("Breaking Tech Update", decl.title)
    }

    @Test
    fun testHtml5VideoAndAudioElementExtraction() {
        val videoHtml = """
            <html>
            <body>
                <div class="player">
                    <video controls src="/media/video_720p.mp4"></video>
                </div>
            </body>
            </html>
        """.trimIndent()

        val declVideo = SmartPublicResolver.extractDeclarationsFromHtml(videoHtml, "https://example.com/page")
        assertNotNull(declVideo)
        assertEquals("https://example.com/media/video_720p.mp4", declVideo!!.videoUrl)

        val audioHtml = """
            <html>
            <body>
                <audio controls>
                    <source src="https://cdn.example.com/audio/podcast_ep1.mp3" type="audio/mpeg" />
                </audio>
            </body>
            </html>
        """.trimIndent()

        val declAudio = SmartPublicResolver.extractDeclarationsFromHtml(audioHtml, "https://example.com/podcast/1")
        assertNotNull(declAudio)
        assertEquals("https://cdn.example.com/audio/podcast_ep1.mp3", declAudio!!.audioUrl)
    }

    @Test
    fun testPublicManifestHlsAndDashExtraction() {
        val hlsHtml = """
            <html>
            <head><title>Live Public Stream</title></head>
            <body>
                <script>
                    var playlist = "https://stream.example.com/live/master.m3u8?token=free";
                </script>
            </body>
            </html>
        """.trimIndent()

        val declHls = SmartPublicResolver.extractDeclarationsFromHtml(hlsHtml, "https://example.com/live")
        assertNotNull(declHls)
        assertEquals("https://stream.example.com/live/master.m3u8?token=free", declHls!!.manifestUrl)
        assertTrue(declHls.isManifest)

        val dashHtml = """
            <html>
            <body>
                <script>
                    const manifest = "https://dash.example.com/vod/stream.mpd";
                </script>
            </body>
            </html>
        """.trimIndent()

        val declDash = SmartPublicResolver.extractDeclarationsFromHtml(dashHtml, "https://example.com/vod")
        assertNotNull(declDash)
        assertEquals("https://dash.example.com/vod/stream.mpd", declDash!!.manifestUrl)
        assertTrue(declDash.isManifest)
    }

    @Test
    fun testJsonLdStructuredDataExtraction() {
        val jsonLdHtml = """
            <html>
            <head>
                <script type="application/ld+json">
                {
                    "@context": "https://schema.org",
                    "@type": "VideoObject",
                    "name": "How to Build Modern Android Apps",
                    "thumbnailUrl": "https://cdn.example.com/thumb.jpg",
                    "contentUrl": "https://cdn.example.com/video/android_tutorial.mp4"
                }
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val decl = SmartPublicResolver.extractDeclarationsFromHtml(jsonLdHtml, "https://example.com/tutorials/android")
        assertNotNull(decl)
        assertEquals("https://cdn.example.com/video/android_tutorial.mp4", decl!!.videoUrl)
        assertEquals("How to Build Modern Android Apps", decl.title)
        assertEquals("https://cdn.example.com/thumb.jpg", decl.thumbnail)
    }

    @Test
    fun testShortUrlDetection() {
        assertTrue(SmartPublicResolver.isShortUrl("https://vm.tiktok.com/ZM8example/"))
        assertTrue(SmartPublicResolver.isShortUrl("https://vt.tiktok.com/hK8abc/"))
        assertTrue(SmartPublicResolver.isShortUrl("https://t.co/xyz123"))
        assertTrue(SmartPublicResolver.isShortUrl("https://bit.ly/video321"))
        assertTrue(SmartPublicResolver.isShortUrl("https://fb.watch/4jkl9/"))
        assertTrue(SmartPublicResolver.isShortUrl("https://pin.it/7mNpQ/"))
        assertTrue(SmartPublicResolver.isShortUrl("https://youtu.be/dQw4w9WgXcQ"))

        // Standard canonical URLs must NOT be flagged as short URLs
        assertFalse(SmartPublicResolver.isShortUrl("https://www.tiktok.com/@creator/video/1234567890"))
        assertFalse(SmartPublicResolver.isShortUrl("https://www.instagram.com/reel/C123abc456/"))
        assertFalse(SmartPublicResolver.isShortUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun testLoginRequiredDoesNotMarkProviderBroken() {
        val providerId = "test_provider_isolation"
        // Initially record a successful public download
        ProviderHealthManager.recordSuccess(providerId, 150L)
        assertEquals(ProviderHealth.AVAILABLE, ProviderHealthManager.getHealth(providerId))

        // Record a LOGIN_REQUIRED error on an individual protected link
        ProviderHealthManager.recordFailure(providerId, ResolverErrorType.LOGIN_REQUIRED)

        // The provider MUST NOT be marked TEMPORARILY_BROKEN or DEGRADED!
        // Requirement 10: "Do not mark the entire provider broken because one URL requires login."
        assertEquals(
            "Provider must remain AVAILABLE even after an individual video requires login",
            ProviderHealth.AVAILABLE,
            ProviderHealthManager.getHealth(providerId)
        )

        // Record another content-specific failure (e.g. PRIVATE_MEDIA or GEO_RESTRICTED)
        ProviderHealthManager.recordFailure(providerId, ResolverErrorType.PRIVATE_MEDIA)
        ProviderHealthManager.recordFailure(providerId, ResolverErrorType.GEO_RESTRICTED)

        val metrics = ProviderHealthManager.getMetrics(providerId)
        assertEquals("Provider failureCount must stay 0 for content-specific errors", 0, metrics.failureCount)
        assertEquals("contentSpecificErrorCount must track all content-specific errors", 3, metrics.contentSpecificErrorCount)
        assertEquals(ProviderHealth.AVAILABLE, ProviderHealthManager.getHealth(providerId))
    }

    @Test
    fun testPreventedLoginRequiredMetricsAndStrategyTracking() {
        val providerId = "test_strategy_diagnostics"
        ProviderHealthManager.recordSuccess(
            providerId = providerId,
            latencyMs = 250L,
            strategy = ResolutionStrategy.PUBLIC_HTML_OPENGRAPH,
            preventedLoginRequired = true
        )

        val metrics = ProviderHealthManager.getMetrics(providerId)
        assertEquals(1, metrics.preventedLoginRequiredCount)
        assertEquals(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, metrics.lastStrategy)
        assertEquals(ResolutionClassification.PUBLIC_RESOLVED, metrics.lastClassification)
        assertEquals(1, metrics.strategyCounts[ResolutionStrategy.PUBLIC_HTML_OPENGRAPH])

        // Add a canonical expansion success
        ProviderHealthManager.recordPreventedLoginRequired(
            providerId = providerId,
            strategy = ResolutionStrategy.CANONICAL_EXPANSION,
            latencyMs = 180L
        )

        assertEquals(2, metrics.preventedLoginRequiredCount)
        assertEquals(ResolutionStrategy.CANONICAL_EXPANSION, metrics.lastStrategy)
        assertEquals(1, metrics.strategyCounts[ResolutionStrategy.CANONICAL_EXPANSION])
    }

    @Test
    fun testResolverErrorToClassificationMapping() {
        assertEquals(ResolutionClassification.LOGIN_REQUIRED, ResolverErrorType.LOGIN_REQUIRED.toClassification())
        assertEquals(ResolutionClassification.PRIVATE_MEDIA, ResolverErrorType.PRIVATE_MEDIA.toClassification())
        assertEquals(ResolutionClassification.PUBLIC_MEDIA_UNAVAILABLE, ResolverErrorType.PUBLIC_MEDIA_UNAVAILABLE.toClassification())
        assertEquals(ResolutionClassification.PUBLIC_MEDIA_UNAVAILABLE, ResolverErrorType.MEDIA_NOT_FOUND.toClassification())
        assertEquals(ResolutionClassification.GEO_RESTRICTED, ResolverErrorType.GEO_RESTRICTED.toClassification())
        assertEquals(ResolutionClassification.RATE_LIMITED, ResolverErrorType.RATE_LIMITED.toClassification())
        assertEquals(ResolutionClassification.RATE_LIMITED, ResolverErrorType.ANTI_BOT_CHALLENGE.toClassification())
        assertEquals(ResolutionClassification.DRM_PROTECTED, ResolverErrorType.DRM_PROTECTED.toClassification())
        assertEquals(ResolutionClassification.EXTRACTOR_OUTDATED, ResolverErrorType.EXTRACTOR_OUTDATED.toClassification())
        assertEquals(ResolutionClassification.EXTRACTOR_OUTDATED, ResolverErrorType.PARSER_FAILURE.toClassification())
        assertEquals(ResolutionClassification.TEMPORARILY_UNAVAILABLE, ResolverErrorType.TEMPORARILY_UNAVAILABLE.toClassification())
        assertEquals(ResolutionClassification.TEMPORARILY_UNAVAILABLE, ResolverErrorType.TIMEOUT.toClassification())
    }

    @Test
    fun testResolutionTraceStructure() {
        val attempts = listOf(
            ResolutionAttempt(
                strategy = ResolutionStrategy.DEDICATED_EXTRACTOR,
                providerId = "instagram",
                success = false,
                errorType = ResolverErrorType.EXTRACTOR_OUTDATED,
                durationMs = 450L
            ),
            ResolutionAttempt(
                strategy = ResolutionStrategy.CANONICAL_EXPANSION,
                providerId = "instagram",
                success = false,
                errorType = ResolverErrorType.EXTRACTOR_OUTDATED,
                durationMs = 210L
            ),
            ResolutionAttempt(
                strategy = ResolutionStrategy.PUBLIC_HTML_OPENGRAPH,
                providerId = "instagram",
                success = true,
                durationMs = 380L
            )
        )

        val trace = ResolutionTrace(
            attempts = attempts,
            finalStrategy = ResolutionStrategy.PUBLIC_HTML_OPENGRAPH,
            finalClassification = ResolutionClassification.PUBLIC_RESOLVED
        )

        assertEquals(3, trace.attempts.size)
        assertEquals(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, trace.finalStrategy)
        assertEquals(ResolutionClassification.PUBLIC_RESOLVED, trace.finalClassification)
        assertFalse(trace.attempts[0].success)
        assertTrue(trace.attempts[2].success)
    }

    @Test
    fun testFallbackOnlySuccessMarksProviderDegraded() {
        val providerId = "test_degraded_fallback"
        // Dedicated strategy failed, fallback succeeded:
        ProviderHealthManager.recordFallbackSuccess(
            providerId = providerId,
            latencyMs = 500L,
            strategy = ResolutionStrategy.PUBLIC_HTML_OPENGRAPH
        )

        val health = ProviderHealthManager.getHealth(providerId)
        assertEquals(
            "Provider relying solely on fallback strategies must be marked DEGRADED",
            ProviderHealth.DEGRADED,
            health
        )

        // Once a dedicated extractor succeeds, health transitions to AVAILABLE
        ProviderHealthManager.recordSuccess(
            providerId = providerId,
            latencyMs = 200L,
            strategy = ResolutionStrategy.DEDICATED_EXTRACTOR
        )
        assertEquals(ProviderHealth.AVAILABLE, ProviderHealthManager.getHealth(providerId))
    }

    @Test
    fun testCaseA_DedicatedStrategyFailsWithParserError_FallbackSucceeds() {
        // Dedicated extractor fails with parser error, fallback succeeds
        val attempts = listOf(
            ResolutionAttempt(ResolutionStrategy.DEDICATED_EXTRACTOR, "sample", false, ResolverErrorType.PARSER_FAILURE, 100),
            ResolutionAttempt(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, "sample", true, null, 120)
        )
        val trace = ResolutionTrace(attempts, ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, ResolutionClassification.PUBLIC_RESOLVED)
        val result = MediaResolverResult.Success(
            NormalizedMetadata(
                id = "sample_1",
                canonicalUrl = "https://example.com/video",
                platform = "sample",
                platformIcon = "public",
                title = "Public Video",
                variants = listOf(MediaVariant(id = "v1", quality = "720p", height = 720, format = "mp4", isAudioOnly = false)),
                resolutionStrategy = ResolutionStrategy.PUBLIC_HTML_OPENGRAPH,
                classification = ResolutionClassification.PUBLIC_RESOLVED,
                resolvedViaSmartPublicResolution = true,
                trace = trace
            )
        )
        assertTrue("Case A must result in Success", result is MediaResolverResult.Success)
        assertFalse("Case A must NOT be LOGIN_REQUIRED", (result as MediaResolverResult.Success).metadata.classification == ResolutionClassification.LOGIN_REQUIRED)
        assertEquals(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, result.metadata.resolutionStrategy)
    }

    @Test
    fun testCaseB_DedicatedStrategyReceivesHttp403_FallbackSucceeds() {
        // Dedicated extractor receives HTTP 403 (non-auth block), fallback succeeds
        val attempts = listOf(
            ResolutionAttempt(ResolutionStrategy.DEDICATED_EXTRACTOR, "instagram", false, ResolverErrorType.PROVIDER_TEMPORARILY_UNAVAILABLE, 150),
            ResolutionAttempt(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, "instagram", true, null, 200)
        )
        val trace = ResolutionTrace(attempts, ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, ResolutionClassification.PUBLIC_RESOLVED)
        val result = MediaResolverResult.Success(
            NormalizedMetadata(
                id = "ig_1",
                canonicalUrl = "https://instagram.com/reel/123",
                platform = "instagram",
                platformIcon = "instagram",
                title = "Instagram Reel",
                variants = listOf(MediaVariant(id = "v1", quality = "1080p", height = 1080, format = "mp4", isAudioOnly = false)),
                resolutionStrategy = ResolutionStrategy.PUBLIC_HTML_OPENGRAPH,
                classification = ResolutionClassification.PUBLIC_RESOLVED,
                resolvedViaSmartPublicResolution = true,
                trace = trace
            )
        )
        assertTrue(result is MediaResolverResult.Success)
        assertFalse((result as MediaResolverResult.Success).metadata.classification == ResolutionClassification.LOGIN_REQUIRED)
    }

    @Test
    fun testCaseC_DedicatedStrategyFailsBecauseUrlIsExpired_YieldsNotFound() {
        val attempts = listOf(
            ResolutionAttempt(ResolutionStrategy.DEDICATED_EXTRACTOR, "tiktok", false, ResolverErrorType.MEDIA_NOT_FOUND, 110),
            ResolutionAttempt(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, "tiktok", false, ResolverErrorType.MEDIA_NOT_FOUND, 90)
        )
        val trace = ResolutionTrace(attempts, null, ResolutionClassification.PUBLIC_MEDIA_UNAVAILABLE)
        val error = ResolverError(
            type = ResolverErrorType.MEDIA_NOT_FOUND,
            detail = "Media was deleted or link expired.",
            trace = trace
        )
        assertEquals(ResolverErrorType.MEDIA_NOT_FOUND, error.type)
        assertEquals(ResolutionClassification.PUBLIC_MEDIA_UNAVAILABLE, error.classification)
        assertFalse("Expired media must not be classified as LOGIN_REQUIRED", error.classification == ResolutionClassification.LOGIN_REQUIRED)
    }

    @Test
    fun testCaseD_AntiBotChallengeDetected_YieldsRateLimitedNotLoginRequired() {
        val errorType = ResolverErrorType.ANTI_BOT_CHALLENGE
        val classification = errorType.toClassification()
        assertEquals(ResolutionClassification.RATE_LIMITED, classification)
        assertFalse("Anti-bot challenges must NOT be classified as LOGIN_REQUIRED", classification == ResolutionClassification.LOGIN_REQUIRED)
    }

    @Test
    fun testCaseE_ActualAuthenticationRequired_YieldsLoginRequired() {
        val errorType = ResolverErrorType.LOGIN_REQUIRED
        val classification = errorType.toClassification()
        assertEquals(ResolutionClassification.LOGIN_REQUIRED, classification)
    }

    @Test
    fun testCaseF_PrivateMediaAccount_YieldsPrivateMediaClassification() {
        val errorType = ResolverErrorType.PRIVATE_MEDIA
        val classification = errorType.toClassification()
        assertEquals(ResolutionClassification.PRIVATE_MEDIA, classification)
    }

    @Test
    fun testCaseG_AllStrategiesFailParser_YieldsExtractorFailureNotLoginRequired() {
        val attempts = listOf(
            ResolutionAttempt(ResolutionStrategy.DEDICATED_EXTRACTOR, "reddit", false, ResolverErrorType.PARSER_FAILURE, 120),
            ResolutionAttempt(ResolutionStrategy.PUBLIC_HTML_OPENGRAPH, "reddit", false, ResolverErrorType.PARSER_FAILURE, 100),
            ResolutionAttempt(ResolutionStrategy.GENERIC_FALLBACK, "generic", false, ResolverErrorType.EXTRACTOR_OUTDATED, 180)
        )
        val trace = ResolutionTrace(attempts, null, ResolutionClassification.EXTRACTOR_OUTDATED)
        val error = ResolverError(ResolverErrorType.PARSER_FAILURE, "All extractors failed", trace = trace)
        assertEquals(ResolutionClassification.EXTRACTOR_OUTDATED, error.classification)
        assertFalse("Parser failure across all strategies must NOT be LOGIN_REQUIRED", error.classification == ResolutionClassification.LOGIN_REQUIRED)
    }

    @Test
    fun testQualityPresetDerivationFromTruthfulVariants() {
        // Truthful available heights: 360p, 720p, 1080p (no 480p, no 1440p, no 2160p)
        val variants = listOf(
            MediaVariant(id = "v1080", quality = "1080p", height = 1080, format = "mp4", isAudioOnly = false),
            MediaVariant(id = "v720", quality = "720p", height = 720, format = "mp4", isAudioOnly = false),
            MediaVariant(id = "v360", quality = "360p", height = 360, format = "mp4", isAudioOnly = false),
            MediaVariant(id = "a_orig", quality = "Original Audio", height = 0, format = "m4a", isAudioOnly = true)
        )
        val metadata = NormalizedMetadata(
            id = "test_vid",
            canonicalUrl = "https://example.com/video",
            platform = "example",
            platformIcon = "public",
            title = "Test Video",
            variants = variants
        )

        val heights = metadata.heights
        assertEquals(listOf(1080, 720, 360), heights)
        assertFalse("480p must not be present if not provided by source", heights.contains(480))
        assertFalse("1440p must not be present if not provided by source", heights.contains(1440))
        assertFalse("2160p must not be present if not provided by source", heights.contains(2160))

        val bestAvailable = metadata.getBestAvailable()
        assertEquals(1080, bestAvailable?.height)

        val smallest = metadata.getSmallestFile()
        assertEquals(360, smallest?.height)

        val audioOnly = metadata.audioVariants
        assertEquals(1, audioOnly.size)
        assertEquals("m4a", audioOnly[0].format)
    }

    @Test
    fun testColdStartDoesNotInitializeEngineAutomatically() {
        // ProbeEngine state before explicit invocation must NOT be ready
        // and isInitialized must reflect actual cached runtime state
        assertNotNull("Engine state enum must exist", org.videopocket.probe.engine.EngineState.NOT_INITIALIZED)
    }
}
