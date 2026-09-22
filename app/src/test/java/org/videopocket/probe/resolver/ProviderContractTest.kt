package org.videopocket.probe.resolver

import org.junit.Assert.*
import org.junit.Test

/**
 * Base Provider Contract Test
 *
 * Verifies that all registered media providers strictly fulfill the Video Pocket
 * Provider Contract and maintain complete failure isolation.
 */
class ProviderContractTest {

    private val providers = ProviderRegistry.getSupportedProviders()

    @Test
    fun testAllProvidersMeetContract() {
        assertTrue("Provider registry must have registered providers", providers.isNotEmpty())

        val ids = mutableSetOf<String>()
        for (provider in providers) {
            // ID contract
            assertFalse("Provider ID must not be blank", provider.id.isBlank())
            assertTrue(
                "Provider ID '${provider.id}' must be lowercase alphanumeric or underscore/hyphen",
                provider.id.matches(Regex("^[a-z0-9_-]+$"))
            )
            assertTrue("Provider ID must be unique: ${provider.id}", ids.add(provider.id))

            // Metadata contract
            assertFalse("Provider name must not be blank for ${provider.id}", provider.name.isBlank())
            assertFalse("Provider iconName must not be blank for ${provider.id}", provider.iconName.isBlank())
            assertTrue("Provider must declare supportedMediaTypes for ${provider.id}", provider.supportedMediaTypes.isNotEmpty())

            // Domain contract
            if (provider.id != "generic") {
                assertTrue("Non-generic provider must declare supportedDomains for ${provider.id}", provider.supportedDomains.isNotEmpty())
                for (domain in provider.supportedDomains) {
                    assertFalse("Domain must not contain scheme: $domain", domain.contains("://"))
                    assertFalse("Domain must not contain path slash: $domain", domain.contains("/"))
                }
            }
        }
    }

    @Test
    fun testProviderUrlMatching() {
        val testCases = mapOf(
            "tiktok" to "https://www.tiktok.com/@creator/video/7123456789012345678",
            "instagram" to "https://www.instagram.com/reel/C123abc456/",
            "facebook" to "https://www.facebook.com/watch/?v=10158234567890123",
            "twitter" to "https://x.com/AndroidDev/status/1789123456789012345",
            "reddit" to "https://www.reddit.com/r/Android/comments/abc123/video_title/",
            "vimeo" to "https://vimeo.com/76979871",
            "dailymotion" to "https://www.dailymotion.com/video/x8abcdef",
            "bilibili" to "https://www.bilibili.com/video/BV1xx411c7mD",
            "soundcloud" to "https://soundcloud.com/artist/track-name",
            "tumblr" to "https://artist.tumblr.com/post/123456789/video-slug",
            "snapchat" to "https://story.snapchat.com/s/sample_story"
        )

        for ((expectedId, testUrl) in testCases) {
            val provider = ProviderRegistry.findProvider(testUrl)
            assertEquals("URL $testUrl should route to provider $expectedId", expectedId, provider.id)
            assertTrue("Provider $expectedId canHandle should return true for $testUrl", provider.canHandle(testUrl))
        }

        // Test that specialized providers reject unrelated URLs
        val unrelatedUrl = "https://randomnews.org/article/1234"
        val nonGenericProviders = providers.filter { it.id != "generic" }
        for (provider in nonGenericProviders) {
            assertFalse(
                "Provider ${provider.id} should reject unrelated URL $unrelatedUrl",
                provider.canHandle(unrelatedUrl)
            )
        }

        // Test GenericProvider handles fallback
        val fallbackProvider = ProviderRegistry.findProvider(unrelatedUrl)
        assertEquals("Unrelated URL should route to GenericProvider", "generic", fallbackProvider.id)
        assertTrue("GenericProvider canHandle must return true for any valid HTTP URL", fallbackProvider.canHandle(unrelatedUrl))
    }

    @Test
    fun testUrlNormalizationStripsTrackingParams() {
        val dirtyUrl = "https://www.instagram.com/reel/C123abc456/?utm_source=share&utm_medium=copy_link&igsh=XYZ123&fbclid=abc"
        val provider = ProviderRegistry.findProvider(dirtyUrl)
        val clean = provider.normalizeUrl(dirtyUrl)

        assertFalse("Clean URL must not contain utm_source", clean.contains("utm_source"))
        assertFalse("Clean URL must not contain utm_medium", clean.contains("utm_medium"))
        assertFalse("Clean URL must not contain igsh", clean.contains("igsh"))
        assertFalse("Clean URL must not contain fbclid", clean.contains("fbclid"))
        assertTrue("Clean URL must retain base path", clean.contains("/reel/C123abc456/"))
    }

    @Test
    fun testUrlNormalizationPreservesVitalMediaParams() {
        val videoUrl = "https://example.com/watch?v=dQw4w9WgXcQ&list=PL12345&t=42s&utm_source=bad"
        val provider = GenericProvider()
        val clean = provider.normalizeUrl(videoUrl)

        assertTrue("Clean URL must preserve video id 'v'", clean.contains("v=dQw4w9WgXcQ"))
        assertTrue("Clean URL must preserve playlist id 'list'", clean.contains("list=PL12345"))
        assertTrue("Clean URL must preserve timestamp 't'", clean.contains("t=42s"))
        assertFalse("Clean URL must strip 'utm_source'", clean.contains("utm_source"))
    }

    @Test
    fun testProviderHealthMonitoring() {
        val testProviderId = "vimeo"
        // Initially available
        val initialHealth = ProviderHealthManager.getHealth(testProviderId)
        assertTrue(
            "Initial health should be AVAILABLE or WORKING",
            initialHealth == ProviderHealth.AVAILABLE || initialHealth == ProviderHealth.WORKING
        )

        // Record a success
        ProviderHealthManager.recordSuccess(testProviderId, 120L)
        val metrics = ProviderHealthManager.getMetrics(testProviderId)
        assertEquals(1, metrics.successCount)
        assertEquals(120L, metrics.lastLatencyMs)

        // Record rate limited failure
        ProviderHealthManager.recordFailure(testProviderId, ResolverErrorType.RATE_LIMITED)
        val degradedHealth = ProviderHealthManager.getHealth(testProviderId)
        assertTrue(
            "Rate limited health should be TEMPORARILY_BROKEN or TEMPORARILY_UNAVAILABLE",
            degradedHealth == ProviderHealth.TEMPORARILY_BROKEN || degradedHealth == ProviderHealth.TEMPORARILY_UNAVAILABLE
        )
    }

    @Test
    fun testProviderFeatureFlagToggle() {
        val testProviderId = "dailymotion"
        assertTrue("Provider should be enabled by default", FeatureFlagsManager.isProviderEnabled(testProviderId))

        FeatureFlagsManager.setProviderEnabled(testProviderId, false)
        assertFalse("Provider should be disabled after toggle", FeatureFlagsManager.isProviderEnabled(testProviderId))
        assertEquals(
            "Disabled provider health should be DISABLED",
            ProviderHealth.DISABLED,
            ProviderHealthManager.getHealth(testProviderId)
        )

        // Reset for subsequent tests
        FeatureFlagsManager.setProviderEnabled(testProviderId, true)
        assertTrue("Provider should be re-enabled", FeatureFlagsManager.isProviderEnabled(testProviderId))
    }

    @Test
    fun testUniversalMediaEngineFacade() {
        val testUrl = "https://x.com/test/status/123"
        val provider = UniversalMediaEngine.findProvider(testUrl)
        assertEquals("twitter", provider.id)

        val all = UniversalMediaEngine.getSupportedProviders()
        assertEquals(providers.size, all.size)
    }
}
