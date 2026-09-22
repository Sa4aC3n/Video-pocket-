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

    fun getGenericProvider(): GenericProvider = genericProvider

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
            val msg = t.message.orEmpty().lowercase()
            val errType = if ("sign in" in msg || "login" in msg || "log in" in msg) {
                ResolverErrorType.LOGIN_REQUIRED
            } else {
                ResolverErrorType.PROCESSING_FAILED
            }
            MediaResolverResult.Failure(
                ResolverError(
                    errType,
                    "Provider '${provider.name}' error: ${t.message ?: t.javaClass.simpleName}"
                )
            )
        }
        val latency = System.currentTimeMillis() - started

        when (result) {
            is MediaResolverResult.Success -> {
                ProviderHealthManager.recordSuccess(
                    providerId = provider.id,
                    latencyMs = latency,
                    strategy = result.metadata.resolutionStrategy
                )
                return result
            }
            is MediaResolverResult.Failure -> {
                // Intercept LOGIN_REQUIRED / PRIVATE_MEDIA for Smart Public Resolution
                if (result.error.type == ResolverErrorType.LOGIN_REQUIRED || result.error.type == ResolverErrorType.PRIVATE_MEDIA) {
                    val smartResult = SmartPublicResolver.resolvePublicFallback(cleanUrl, provider, engine)
                    if (smartResult is MediaResolverResult.Success) {
                        ProviderHealthManager.recordSuccess(
                            providerId = provider.id,
                            latencyMs = System.currentTimeMillis() - started,
                            strategy = smartResult.metadata.resolutionStrategy,
                            preventedLoginRequired = true
                        )
                        return smartResult
                    } else if (smartResult is MediaResolverResult.Failure) {
                        ProviderHealthManager.recordFailure(provider.id, smartResult.error.type)
                        return smartResult
                    }
                }

                // If specific provider failed with another non-login error, attempt GenericProvider fallback
                ProviderHealthManager.recordFailure(provider.id, result.error.type)
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
                        ProviderHealthManager.recordSuccess(
                            providerId = "generic",
                            latencyMs = System.currentTimeMillis() - started,
                            strategy = ResolutionStrategy.GENERIC_FALLBACK
                        )
                        return MediaResolverResult.Success(
                            fallbackResult.metadata.copy(
                                resolutionStrategy = ResolutionStrategy.GENERIC_FALLBACK,
                                classification = ResolutionClassification.PUBLIC_RESOLVED,
                                resolvedViaSmartPublicResolution = true
                            )
                        )
                    }
                }
            }
        }
        return result
    }
}
