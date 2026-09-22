package org.videopocket.probe.resolver

import org.videopocket.probe.core.MediaInfo
import org.videopocket.probe.engine.ProbeEngine

interface MediaProvider {
    val id: String
    val name: String
    val iconName: String
    val supportedMediaTypes: Set<MediaType>
    val supportedDomains: List<String>

    fun canHandle(url: String): Boolean
    fun normalizeUrl(url: String): String = url.trim()
    suspend fun resolve(url: String, engine: ProbeEngine): MediaResolverResult
}
