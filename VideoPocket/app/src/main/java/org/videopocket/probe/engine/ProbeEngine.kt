package org.videopocket.probe.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import org.videopocket.probe.core.*
import org.videopocket.probe.R
import java.io.File
import java.util.UUID

interface MediaInspector { fun inspect(url: String): MediaInfo }
interface MediaDownloader {
    fun download(url: String, info: MediaInfo, action: ProbeAction, height: Int, bitrate: Int, progress: (Float) -> Unit): ProbeResult
}

/** Blocking methods run on Dispatchers.IO. Never expose yt-dlp output or URLs in diagnostics. */
class ProbeEngine(private val context: Context) : MediaInspector, MediaDownloader {
    private val engine = YoutubeDL.getInstance()
    private val workRoot = File(context.noBackupFilesDir, "probe-files")
    

    @Synchronized fun initialize(): JSONObject {
        FFmpeg.getInstance().init(context)
        engine.init(context)
        // Replace the previous extracted engine with this APK's pinned resource on upgrades.
        val targetFile = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        val rawSize = try { context.resources.openRawResource(R.raw.ytdlp).use { it.available().toLong() } } catch (_: Exception) { 0L }
        if (!targetFile.exists() || (rawSize > 0L && targetFile.length() != rawSize)) {
            targetFile.parentFile?.mkdirs()
            val pinned = AtomicFile(targetFile)
            val stream = pinned.startWrite()
            try {
                context.resources.openRawResource(R.raw.ytdlp).use { it.copyTo(stream) }
                pinned.finishWrite(stream)
            } catch (e: Exception) { pinned.failWrite(stream); throw e }
        }
        for (name in listOf("libpython.so", "libffmpeg.so", "libqjs.so")) {
            val f = File(context.applicationInfo.nativeLibraryDir, name)
            if (!f.exists()) throw ProbeFailure(Problem.ENGINE, "Missing native binary: $name in ${context.applicationInfo.nativeLibraryDir}")
        }
        workRoot.mkdirs()
        // Only our own disposable probe files; no shared user files.
        workRoot.listFiles()?.forEach { it.deleteRecursively() }
        try {
            Os.setenv("PYTHONDONTWRITEBYTECODE", "1", true)
            Os.setenv("PYTHONNOUSERSITE", "1", true)
            val pythonHome = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr").absolutePath
            val pythonLib = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib/python3.12").absolutePath
            val sitePackages = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib/python3.12/site-packages").absolutePath
            Os.setenv("PYTHONPATH", "$pythonLib:$sitePackages", true)
            Os.setenv("PYTHONHOME", pythonHome, true)
        } catch (_: Throwable) {}
        val response = engine.execute(
            YoutubeDLRequest(emptyList())
                .addOption("--ignore-config")
                .addOption("--no-config-locations")
                .addOption("--no-plugin-dirs")
                .addOption("--no-cache-dir")
                .addOption("--version"),
            null,
            null
        )
        val ver = response.out.trim()
        if (ver != "2026.08.19") throw ProbeFailure(Problem.ENGINE, "yt-dlp version mismatch: expected 2026.08.19, got $ver")
        return JSONObject().put("androidApi", Build.VERSION.SDK_INT)
            .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("wrapper", "0.18.1").put("ytDlp", ver.take(80))
            .put("quickJsPresent", true)
    }

    private fun request(url: String) = YoutubeDLRequest(LinkPolicy.validate(url))
        .addOption("--ignore-config")
        .addOption("--no-config-locations")
        .addOption("--no-plugin-dirs")
        .addOption("--no-playlist")
        .addOption("--no-cache-dir")
        .addOption("--no-warnings")
        .addOption("--socket-timeout", 20)
        .addOption("--retries", 2)
        .addOption("--fragment-retries", 2)
        .addOption("--no-check-formats")
        .addOption("--no-remote-components")

    override fun inspect(url: String): MediaInfo {
        val response = engine.execute(request(url).addOption("--dump-single-json").addOption("--skip-download"), null, null)
        val json = JSONObject(response.out)
        if (json.optString("_type") in setOf("playlist", "multi_video") || json.optBoolean("is_live") || json.optString("live_status") == "is_upcoming")
            throw ProbeFailure(Problem.LIVE_OR_PLAYLIST)
        val array = json.optJSONArray("formats")
        val rows = if (array == null) listOf(json) else (0 until array.length()).map { array.getJSONObject(it) }
        val formats = rows.filter { !it.optBoolean("has_drm") }.map { f ->
            fun codec(key: String): Boolean = f.optString(key).let { it.isNotBlank() && it != "none" && it != "null" }
            org.videopocket.probe.core.MediaFormat(
                f.optString("format_id", "best"), f.optString("ext"), f.optInt("height", 0),
                if (!f.has("vcodec")) f.optString("ext") !in setOf("mp3", "m4a", "aac", "ogg", "opus") else codec("vcodec"), if (!f.has("acodec")) true else codec("acodec"),
                f.optLong("filesize", f.optLong("filesize_approx", 0)).takeIf { it > 0 }
            )
        }
        if (formats.isEmpty()) throw ProbeFailure(Problem.UNSUPPORTED)
        return MediaInfo(json.optString("title", "Video"), json.optDouble("duration").takeIf { it.isFinite() }, formats)
    }

    override fun download(url: String, info: MediaInfo, action: ProbeAction, height: Int, bitrate: Int, progress: (Float) -> Unit): ProbeResult {
        require(action != ProbeAction.INSPECT)
        require(bitrate in setOf(128, 192, 320))
        val format = FormatPolicy.select(info, action, height)
        val estimate = info.formats.filter { it.id in format.split('+') }.mapNotNull { it.bytes }.sum()
        if (StatFs(context.noBackupFilesDir.path).availableBytes < maxOf(256L * 1024 * 1024, estimate * 3))
            throw ProbeFailure(Problem.SPACE)
        val id = UUID.randomUUID().toString()
        val folder = File(workRoot, id).apply { mkdirs() }
        val started = System.nanoTime()
        try {
            val req = request(url).addOption("--format", format)
                .addOption("--no-simulate").addOption("--newline")
                .addOption("--output", File(folder, "media.%(ext)s").absolutePath)
                .addOption("--max-filesize", "512M")
                .addOption("--merge-output-format", "mkv")
            if (action == ProbeAction.MP3) req.addOption("--extract-audio")
                .addOption("--audio-format", "mp3").addOption("--audio-quality", "${bitrate}K")

            engine.execute(req, id) { percent, _, _ -> progress(percent.coerceIn(0f, 100f)) }
            val output = folder.listFiles()?.singleOrNull { it.isFile && it.extension in setOf("mp4", "webm", "mkv", "mp3", "m4a", "mov") }
                ?: throw ProbeFailure(Problem.CONVERSION)
            val tracks = validateOutput(output, action)
            val saved = publish(output, action)
            return ProbeResult(action, (System.nanoTime() - started) / 1_000_000, output.length(), saved, true, tracks)
        } finally {

            folder.deleteRecursively()
        }
    }

    private fun validateOutput(file: File, action: ProbeAction): String {
        if (file.length() == 0L) throw ProbeFailure(Problem.CONVERSION)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val types = tracks.map { it.getString(MediaFormat.KEY_MIME).orEmpty() }
            val audio = types.any { it.startsWith("audio/") }
            val video = types.any { it.startsWith("video/") }
            if (action == ProbeAction.MP3 && (!audio || video || "audio/mpeg" !in types)) throw ProbeFailure(Problem.CONVERSION)
            if (action == ProbeAction.MERGE && (!audio || !video)) throw ProbeFailure(Problem.CONVERSION)
            if (action == ProbeAction.VIDEO && !video) throw ProbeFailure(Problem.CONVERSION)
            return types.joinToString(",") + "; container/track check only; playback sync requires listening"
        } finally { extractor.release() }
    }

    private fun publish(file: File, action: ProbeAction): String {
        val mime = when (file.extension) {
            "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"; "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"; "m4a" -> "audio/mp4"; else -> "video/quicktime"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "VideoPocket-${action.name}-${System.currentTimeMillis()}.${file.extension}")
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoPocket")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw ProbeFailure(Problem.SPACE)
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw ProbeFailure(Problem.SPACE)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri.toString()
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    companion object {
        fun classify(error: Throwable): Problem {
            if (error is ProbeFailure) return error.problem
            val message = error.message.orEmpty().lowercase()
            return when {
                "no space" in message -> Problem.SPACE
                "sign in" in message || "login" in message || "private" in message || "cookies" in message -> Problem.LOGIN_REQUIRED
                "unsupported url" in message || "requested format" in message -> Problem.UNSUPPORTED
                "removed" in message || "not found" in message || "404" in message -> Problem.REMOVED
                "403" in message || "429" in message || "restricted" in message -> Problem.RESTRICTED
                "timed out" in message || "resolve" in message || "network" in message -> Problem.NETWORK
                "ffmpeg" in message -> Problem.CONVERSION
                else -> Problem.ENGINE
            }
        }
    }
}

