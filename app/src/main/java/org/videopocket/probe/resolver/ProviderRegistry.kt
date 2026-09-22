package org.videopocket.probe.resolver

import org.videopocket.probe.engine.ProbeEngine

object ProviderRegistry {
    private val genericProvider = GenericProvider()

    private val providers: List<MediaProvider> = listOf(
        TikTokProvider(),
        InstagramProvider(),
        FacebookProvider(),
        XProvider(),
        RedditProvider(),
        VimeoProvider(),
        DailymotionProvider(),
        BilibiliProvider(),
        SoundCloudProvider(),
        TumblrProvider(),
        SnapchatProvider(),
        PinterestProvider(),
        TedProvider(),
        TwitchProvider(),
        genericProvider
    )

    fun getSupportedProviders(): List<MediaProvider> = providers

    fun findProvider(url: String): MediaProvider {
        val cleanUrl = url.trim()
        val specific = providers.filter { it.id != "generic" }.firstOrNull { it.canHandle(cleanUrl) }
        return specific ?: genericProvider
    }

    suspend fun resolve(url: String, engine: ProbeEngine): MediaResolverResult {
        val cleanUrl = url.trim()
        val provider = findProvider(cleanUrl)

        if (!FeatureFlagsManager.isProviderEnabled(provider.id)) {
            return MediaResolverResult.Failure(
                ResolverError(
                    ResolverErrorType.UNSUPPORTED_SOURCE,
                    "Provider '${provider.name}' is currently disabled in Settings."
                )
            )
        }

        val started = System.currentTimeMillis()
        val result = try {
            provider.resolve(cleanUrl, engine)
        } catch (t: Throwable) {
            MediaResolverResult.Failure(
                ResolverError(
                    ResolverErrorType.PROCESSING_FAILED,
                    "Provider '${provider.name}' error: ${t.message ?: t.javaClass.simpleName}"
                )
            )
        }
        val latency = System.currentTimeMillis() - started

        when (result) {
            is MediaResolverResult.Success -> {
                ProviderHealthManager.recordSuccess(provider.id, latency)
            }
            is MediaResolverResult.Failure -> {
                ProviderHealthManager.recordFailure(provider.id, result.error.type)
                // If specific provider failed with UNSUPPORTED or PROVIDER_CHANGED, attempt GenericProvider fallback
                if (provider.id != "generic" && FeatureFlagsManager.isProviderEnabled("generic")) {
                    val fallbackResult = try {
                        genericProvider.resolve(cleanUrl, engine)
                    } catch (t: Throwable) {
                        MediaResolverResult.Failure(
                            ResolverError(
                                ResolverErrorType.PROCESSING_FAILED,
                                "Generic fallback error: ${t.message ?: t.javaClass.simpleName}"
                            )
                        )
                    }
                    if (fallbackResult is MediaResolverResult.Success) {
                        ProviderHealthManager.recordSuccess("generic", System.currentTimeMillis() - started)
                        return fallbackResult
                    }
                }
            }
        }
        return result
    }
}
