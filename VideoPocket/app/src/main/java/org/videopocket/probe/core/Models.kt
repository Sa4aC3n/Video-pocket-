package org.videopocket.probe.core

import java.net.URI

enum class ProbeAction { INSPECT, VIDEO, MERGE, MP3 }
enum class Problem { INVALID_URL, UNSUPPORTED, LOGIN_REQUIRED, REMOVED, RESTRICTED, LIVE_OR_PLAYLIST, SPACE, NETWORK, CONVERSION, ENGINE, CANCELED }
class ProbeFailure(val problem: Problem, val reason: String? = null) : Exception(reason ?: problem.name)
data class MediaFormat(val id: String, val extension: String, val height: Int, val video: Boolean, val audio: Boolean, val bytes: Long?)
data class MediaInfo(val title: String, val duration: Double?, val formats: List<MediaFormat>) {
    val heights get() = formats.filter { it.video && it.height in 1..2160 }.map { it.height }.distinct().sortedDescending()
    val defaultHeight get() = heights.firstOrNull { it <= 1080 } ?: heights.minOrNull() ?: 1080
}
data class ProbeResult(val action: ProbeAction, val elapsedMs: Long, val bytes: Long, val outputUri: String?, val passed: Boolean, val detail: String)

object LinkPolicy {
    fun validate(input: String): String {
        val text = input.trim()
        val uri = try { URI(text) } catch (_: Exception) { throw ProbeFailure(Problem.INVALID_URL) }
        if (text.length > 8192 || text.any { it.isWhitespace() || it.isISOControl() } ||
            uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null)
            throw ProbeFailure(Problem.INVALID_URL)
        return text
    }
}

object FormatPolicy {
    // No fallback to a lower resolution: make a missing requested format visible.
    fun select(info: MediaInfo, action: ProbeAction, height: Int): String {
        fun safe(id: String): String {
            if (!id.matches(Regex("[A-Za-z0-9_.-]+"))) throw ProbeFailure(Problem.UNSUPPORTED)
            return id
        }
        val audio = info.formats.filter { it.audio && !it.video }.maxByOrNull { it.bytes ?: 0 }
        val videos = info.formats.filter { it.video && (it.height == height || it.height == 0) }
        return when (action) {
            ProbeAction.INSPECT -> "best"
            ProbeAction.MP3 -> safe((audio ?: info.formats.lastOrNull { it.audio }
                ?: throw ProbeFailure(Problem.UNSUPPORTED)).id)
            ProbeAction.MERGE -> {
                val video = videos.lastOrNull { !it.audio } ?: throw ProbeFailure(Problem.UNSUPPORTED)
                safe(video.id) + "+" + safe((audio ?: throw ProbeFailure(Problem.UNSUPPORTED)).id)
            }
            ProbeAction.VIDEO -> {
                val combined = videos.lastOrNull { it.audio }
                if (combined != null) safe(combined.id)
                else {
                    val video = videos.lastOrNull() ?: throw ProbeFailure(Problem.UNSUPPORTED)
                    if (audio != null) safe(video.id) + "+" + safe(audio.id) else safe(video.id)
                }
            }
        }
    }
}
