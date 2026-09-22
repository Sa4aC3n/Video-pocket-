package org.videopocket.probe.resolver

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

interface SubtitleTranslator {
    suspend fun translate(text: String, fromLang: String, toLang: String): String
}

object SubtitleCenter {

    suspend fun exportSubtitle(
        context: Context,
        title: String,
        subtitle: MediaSubtitle,
        targetFormat: String = "srt" // "srt", "vtt", "txt"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val content = fetchSubtitleContent(subtitle.url)
            val formatted = when (targetFormat.lowercase()) {
                "txt" -> stripTimestamps(content)
                "vtt" -> convertToVtt(content)
                else -> content // Assume original or srt
            }

            val safeTitle = title.take(30).replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
            val displayName = "${safeTitle}_${subtitle.languageCode}.$targetFormat"

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoPocket/Subtitles")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext Result.failure(Exception("Failed to create storage entry"))

            resolver.openOutputStream(uri)?.use { out ->
                out.write(formatted.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.failure(Exception("Failed to open output stream"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }

            Result.success(uri.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchSubtitleContent(url: String): String {
        return URL(url).openStream().bufferedReader().use { it.readText() }
    }

    fun stripTimestamps(subContent: String): String {
        val lines = subContent.lines()
        val textLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.matches(Regex("^[0-9]+$"))) continue // Index number
            if (trimmed.contains("-->")) continue // Timestamp line
            if (trimmed.startsWith("WEBVTT") || trimmed.startsWith("NOTE")) continue
            textLines.add(trimmed)
        }
        return textLines.joinToString("\n")
    }

    fun convertToVtt(subContent: String): String {
        if (subContent.trimStart().startsWith("WEBVTT")) return subContent
        val converted = subContent.replace(Regex("([0-9]{2}:[0-9]{2}:[0-9]{2}),([0-9]{3})"), "$1.$2")
        return "WEBVTT\n\n$converted"
    }
}
