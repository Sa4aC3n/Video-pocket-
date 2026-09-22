package org.videopocket.probe

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.videopocket.probe.core.*
import org.videopocket.probe.engine.ProbeEngine
import org.videopocket.probe.engine.PocketNotifier
import org.videopocket.probe.resolver.*
import org.videopocket.probe.download.*
import org.videopocket.probe.storage.*
import org.videopocket.probe.ui.*
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FeatureFlagsManager.init(this)
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
        setContent {
            VideoPocketTheme {
                MainContainer(sharedIntent = shared)
            }
        }
    }
}

@Composable
fun VideoPocketTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF7986CB),
            secondary = androidx.compose.ui.graphics.Color(0xFF4FC3F7),
            tertiary = androidx.compose.ui.graphics.Color(0xFF00ACC1),
            background = androidx.compose.ui.graphics.Color(0xFF121212),
            surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2C2C2C)
        )
    } else {
        lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF283593),
            secondary = androidx.compose.ui.graphics.Color(0xFF0288D1),
            tertiary = androidx.compose.ui.graphics.Color(0xFF00ACC1),
            background = androidx.compose.ui.graphics.Color(0xFFF8F9FA),
            surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F3F5)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

class ProbeModel(application: Application) : AndroidViewModel(application) {
    private val engine = ProbeEngine(application)
    val queueManager = DownloadQueueManager(application, engine)
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var info by mutableStateOf<MediaInfo?>(null); private set
    var normalizedMetadata by mutableStateOf<NormalizedMetadata?>(null); private set
    var resolverError by mutableStateOf<ResolverError?>(null); private set
    var problem by mutableStateOf<Problem?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var currentStageText by mutableStateOf(""); private set
    var results by mutableStateOf(listOf<ProbeResult>()); private set
    var libraryItems by mutableStateOf(listOf<PocketLibraryItem>()); private set
    var runtime by mutableStateOf(JSONObject()); private set
    var initErrorDetail by mutableStateOf<String?>(null); private set
    var inspectedUrl: String? = null; private set
    private val reportFile get() = File(getApplication<Application>().filesDir, "last-report.json")

    init {
        refreshLibrary()
        viewModelScope.launch {
            delay(1000)
            retryInit()
        }
    }

    fun refreshLibrary() {
        libraryItems = PocketLibraryManager.getItems(getApplication())
    }

    fun deleteLibraryItem(id: String) {
        PocketLibraryManager.deleteItem(getApplication(), id)
        refreshLibrary()
    }

    fun renameLibraryItem(id: String, newTitle: String) {
        PocketLibraryManager.renameItem(getApplication(), id, newTitle)
        refreshLibrary()
    }

    fun toggleFavorite(id: String) {
        PocketLibraryManager.toggleFavorite(getApplication(), id)
        refreshLibrary()
    }

    fun updateCollections(id: String, collections: List<String>) {
        PocketLibraryManager.updateCollections(getApplication(), id, collections)
        refreshLibrary()
    }

    fun cancel() {
        if (!busy) return
        engine.cancelCurrent()
        PocketNotifier.dismiss(getApplication())
        busy = false
        currentStageText = ""
        problem = Problem.CANCELED
    }

    fun retryInit() {
        if (busy || ready) return
        busy = true
        problem = null
        initErrorDetail = null
        viewModelScope.launch {
            try {
                runtime = withContext(Dispatchers.IO) { engine.initialize() }
                ready = true
            } catch (e: Exception) {
                problem = ProbeEngine.classify(e)
                initErrorDetail = e.message ?: e.javaClass.simpleName
                record(ProbeResult(ProbeAction.INSPECT, 0, 0, null, false, "Initialization: ${problem!!.name} - $initErrorDetail"))
            } finally {
                busy = false
            }
        }
    }

    fun inspect(url: String, forcePlaylist: Boolean = false) {
        if (busy || !ready) return
        busy = true; problem = null; info = null; normalizedMetadata = null; resolverError = null; inspectedUrl = null
        currentStageText = "Inspecting link..."
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val valid = LinkPolicy.validate(url)
                val resolveRes = withContext(Dispatchers.IO) {
                    ProviderRegistry.resolve(valid, engine)
                }
                when (resolveRes) {
                    is MediaResolverResult.Success -> {
                        normalizedMetadata = resolveRes.metadata
                        info = resolveRes.metadata.rawInfo ?: withContext(Dispatchers.IO) { engine.inspect(valid, forcePlaylist) }
                        inspectedUrl = valid
                        val detailMsg = if (info!!.isPlaylist) "Playlist inspected; entries=${info!!.playlistCount}" else "Metadata extracted; formats=${info!!.formats.size}"
                        record(ProbeResult(ProbeAction.INSPECT, (System.nanoTime()-started)/1_000_000, 0, null, true, detailMsg))
                    }
                    is MediaResolverResult.Failure -> {
                        resolverError = resolveRes.error
                        problem = when (resolveRes.error.type) {
                            ResolverErrorType.INVALID_URL -> Problem.INVALID_URL
                            ResolverErrorType.UNSUPPORTED_SOURCE -> Problem.UNSUPPORTED
                            ResolverErrorType.MEDIA_NOT_FOUND -> Problem.REMOVED
                            ResolverErrorType.PRIVATE_MEDIA -> Problem.LOGIN_REQUIRED
                            ResolverErrorType.RATE_LIMITED -> Problem.RESTRICTED
                            ResolverErrorType.NETWORK_ERROR -> Problem.NETWORK
                            ResolverErrorType.INSUFFICIENT_STORAGE -> Problem.SPACE
                            else -> Problem.ENGINE
                        }
                        record(ProbeResult(ProbeAction.INSPECT, (System.nanoTime()-started)/1_000_000, 0, null, false, problem!!.name))
                    }
                }
            } catch (e: Exception) {
                problem = ProbeEngine.classify(e)
                record(ProbeResult(ProbeAction.INSPECT, (System.nanoTime()-started)/1_000_000, 0, null, false, problem!!.name))
            } finally {
                busy = false
                currentStageText = ""
            }
        }
    }

    fun run(action: ProbeAction, height: Int, bitrate: Int) {
        if (busy || !ready) return
        val media = info ?: return
        val url = inspectedUrl ?: return
        busy = true; problem = null; progress = 0f
        currentStageText = "Downloading..."
        PocketNotifier.showProgress(getApplication(), percent = 0, stageText = currentStageText, title = media.title)
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = withContext(Dispatchers.IO) { engine.download(url, media, action, height, bitrate) { p ->
                    val pInt = p.toInt()
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        progress = p
                        currentStageText = "Downloading ($pInt%)"
                    }
                    PocketNotifier.showProgress(getApplication(), percent = pInt, stageText = "Downloading ($pInt%)", title = media.title)
                } }
                record(result)
                val isAudio = action == ProbeAction.MP3 || action == ProbeAction.PLAYLIST_MP3
                val item = PocketLibraryItem(
                    id = UUID.randomUUID().toString(),
                    title = media.title,
                    uriString = result.outputUri ?: "",
                    isClip = false,
                    duration = media.duration?.let { ClipRequest.formatSeconds(it) } ?: "Full",
                    resolution = if (isAudio) "MP3 Audio" else "${height}p",
                    format = if (isAudio) "MP3" else "MP4",
                    fileSizeBytes = result.bytes,
                    createdAt = System.currentTimeMillis(),
                    thumbnailUri = media.thumbnailUrl,
                    platform = normalizedMetadata?.platform
                )
                PocketLibraryManager.addItem(getApplication(), item)
                refreshLibrary()
                PocketNotifier.showSuccess(getApplication(), title = "Download Complete ✓", message = media.title, uriString = result.outputUri)
            } catch (e: Exception) {
                problem = ProbeEngine.classify(e)
                PocketNotifier.dismiss(getApplication())
                record(ProbeResult(action, (System.nanoTime()-started)/1_000_000, 0, null, false, problem!!.name))
            } finally {
                busy = false
                currentStageText = ""
            }
        }
    }

    fun runClip(clip: ClipRequest) {
        if (busy || !ready) return
        val media = info ?: return
        val url = inspectedUrl ?: return
        busy = true; problem = null; progress = 0f
        currentStageText = "Preparing clip download..."
        PocketNotifier.showProgress(getApplication(), percent = 0, stageText = currentStageText, title = media.title)
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.downloadClip(url, media, clip) { p, stage ->
                        val pInt = p.toInt()
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            progress = p
                            currentStageText = stage
                        }
                        PocketNotifier.showProgress(getApplication(), percent = pInt, stageText = stage, title = media.title)
                    }
                }
                record(result)
                val tier = QualityTier.fromHeight(clip.height)
                val item = PocketLibraryItem(
                    id = UUID.randomUUID().toString(),
                    title = media.title,
                    uriString = result.outputUri ?: "",
                    isClip = true,
                    clipRange = "${clip.startFormatted()} → ${clip.endFormatted()}",
                    duration = clip.durationFormatted(),
                    resolution = if (clip.isAudioOnly) "MP3 Audio" else "${clip.height}p • ${tier.category}",
                    format = if (clip.isAudioOnly) "MP3" else clip.format.uppercase(),
                    fileSizeBytes = result.bytes,
                    createdAt = System.currentTimeMillis(),
                    thumbnailUri = media.thumbnailUrl,
                    platform = normalizedMetadata?.platform
                )
                PocketLibraryManager.addItem(getApplication(), item)
                refreshLibrary()
                PocketNotifier.showSuccess(getApplication(), title = "Clip Ready ✓", message = "${media.title} (${clip.durationFormatted()})", uriString = result.outputUri)
            } catch (e: Exception) {
                problem = ProbeEngine.classify(e)
                PocketNotifier.dismiss(getApplication())
                record(ProbeResult(ProbeAction.CLIP, (System.nanoTime()-started)/1_000_000, 0, null, false, problem!!.name))
            } finally {
                busy = false
                currentStageText = ""
            }
        }
    }

    private suspend fun record(result: ProbeResult) {
        results = results + result
        val text = report()
        withContext(Dispatchers.IO) { runCatching { reportFile.writeText(text) } }
    }

    fun report(): String = JSONObject().put("app", "VideoPocket 2.0")
        .put("generatedAt", Instant.now().toString()).put("runtime", runtime)
        .put("scope", "Foreground V2 experience; secure downloads and media management")
        .put("results", JSONArray(results.map { r -> JSONObject()
            .put("action", r.action.name).put("passed", r.passed).put("elapsedMs", r.elapsedMs)
            .put("bytes", r.bytes).put("detail", r.detail) })).toString(2)

    fun export(uri: Uri, onDone: (Boolean) -> Unit) {
        val text = report()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Unavailable") }.isSuccess
            }
            onDone(ok)
        }
    }
}

@Composable
fun MainContainer(sharedIntent: String, model: ProbeModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var arabic by rememberSaveable { mutableStateOf(Locale.getDefault().language == "ar") }
    fun t(en: String, ar: String) = if (arabic) ar else en

    CompositionLocalProvider(LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(t("Home", "الرئيسية")) },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        label = { Text(t("Downloads", "التنزيلات")) },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                        label = { Text(t("Library", "المكتبة")) },
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(t("Settings", "الإعدادات")) },
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 }
                    )
                }
            }
        ) { paddingValues ->
            Box(Modifier.padding(paddingValues).fillMaxSize()) {
                when (selectedTab) {
                    0 -> HomeScreen(sharedIntent, model, arabic, { arabic = !arabic }, { selectedTab = 1 })
                    1 -> DownloadsScreen(model, arabic)
                    2 -> LibraryScreen(model, arabic)
                    3 -> SettingsScreen(model, arabic, { arabic = !arabic })
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    sharedIntent: String,
    model: ProbeModel,
    arabic: Boolean,
    onToggleLanguage: () -> Unit,
    onNavigateDownloads: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    fun t(en: String, ar: String) = if (arabic) ar else en
    var url by remember { mutableStateOf(sharedIntent) }
    var playlistMode by rememberSaveable { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(1080) }
    var bitrate by remember { mutableIntStateOf(192) }
    var videoFormat by rememberSaveable { mutableStateOf("mp4") }
    var audioFormat by rememberSaveable { mutableStateOf("mp3") }

    var showSupportedSources by remember { mutableStateOf(false) }
    var showBatchDownload by remember { mutableStateOf(false) }
    var showStorageManager by remember { mutableStateOf(false) }
    var showSubtitleCenter by remember { mutableStateOf(false) }
    var duplicateItemToConfirm by remember { mutableStateOf<PocketLibraryItem?>(null) }
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var detectedClipboardUrl by remember { mutableStateOf<String?>(null) }
    var dismissedClipboardUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model.info) { model.info?.let { height = it.defaultHeight } }
    LaunchedEffect(url) {
        if (url.contains("list=") || url.contains("/playlist") || url.contains("/sets/")) {
            playlistMode = true
        }
    }
    LaunchedEffect(sharedIntent) {
        if (sharedIntent.isNotBlank() && model.ready) {
            url = sharedIntent
            model.inspect(sharedIntent, false)
        }
    }

    val pasteAction = {
        try {
            val clip = clipboardManager.getText()?.text?.toString()
            if (!clip.isNullOrBlank()) {
                url = clip.trim()
                model.inspect(url, playlistMode)
            }
        } catch (_: Throwable) {}
    }

    val triggerDownloadWithDuplicateCheck: (() -> Unit) -> Unit = { action ->
        val mediaTitle = model.info?.title.orEmpty()
        val existing = model.libraryItems.firstOrNull { it.title.equals(mediaTitle, ignoreCase = true) }
        if (existing != null) {
            duplicateItemToConfirm = existing
            pendingDownloadAction = action
        } else {
            action()
            onNavigateDownloads()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Video Pocket",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = t("Universal Media & Clip Studio", "استوديو تحميل وقص الوسائط الشامل"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            FilledTonalIconButton(onClick = onToggleLanguage) {
                Icon(Icons.Default.Language, contentDescription = t("Toggle Language", "تغيير اللغة"))
            }
        }

        // Quick Tools Row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestionChip(
                onClick = { showSupportedSources = true },
                label = { Text(t("Supported Sources", "المنصات المدعومة")) },
                icon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            SuggestionChip(
                onClick = { showBatchDownload = true },
                label = { Text(t("Batch Download", "تنزيل دفعات")) },
                icon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            SuggestionChip(
                onClick = { showStorageManager = true },
                label = { Text(t("Storage Manager", "إدارة التخزين")) },
                icon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        // Clipboard Smart Detection Banner
        detectedClipboardUrl?.let { clipUrl ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t("📋 Media link detected on clipboard", "📋 تم اكتشاف رابط وسائط في الحافظة"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = clipUrl.take(45) + if (clipUrl.length > 45) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    Row {
                        TextButton(onClick = {
                            dismissedClipboardUrl = clipUrl
                            detectedClipboardUrl = null
                        }) {
                            Text(t("Dismiss", "تجاهل"))
                        }
                        Button(
                            onClick = {
                                url = clipUrl
                                dismissedClipboardUrl = clipUrl
                                detectedClipboardUrl = null
                                model.inspect(url, playlistMode)
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(t("Analyze", "فحص"))
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (model.ready) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(14.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (model.ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (model.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (model.ready) t("Engine Ready (v2.0)", "المحرك جاهز (إصدار ٢.٠)")
                        else t("Initializing Engine...", "جاري تهيئة المحرك..."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (!model.ready && !model.busy) {
                    TextButton(onClick = { model.retryInit() }) {
                        Text(t("Retry", "إعادة المحاولة"))
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = t("Paste Media Link", "لصق رابط الوسائط"),
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = !model.busy,
                    placeholder = { Text(t("Paste video, audio, reel, or playlist...", "ألصق رابط فيديو أو صوت أو قائمة...")) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (url.isNotBlank()) {
                                IconButton(onClick = { url = "" }, enabled = !model.busy) {
                                    Icon(Icons.Default.Clear, contentDescription = t("Clear", "مسح"))
                                }
                            }
                            IconButton(onClick = pasteAction, enabled = !model.busy) {
                                Icon(Icons.Default.ContentPaste, contentDescription = t("Paste", "لصق"))
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = pasteAction,
                        enabled = !model.busy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("Smart Paste", "لصق ذكي"))
                    }
                    FilterChip(
                        selected = playlistMode,
                        onClick = { playlistMode = !playlistMode },
                        enabled = !model.busy,
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text(t("Playlist", "قائمة")) }
                    )
                }

                Button(
                    onClick = { model.inspect(url, playlistMode) },
                    enabled = model.ready && !model.busy && url.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (playlistMode) t("Analyze Playlist", "فحص قائمة التشغيل") else t("Analyze & Preview", "فحص ومعاينة"))
                }
            }
        }

        if (model.busy) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (model.currentStageText.isNotBlank()) model.currentStageText
                                else t("Processing media... ", "جاري معالجة الوسائط... ") + "(${model.progress.toInt()}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${model.progress.toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (model.progress > 0f) model.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { model.cancel() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(t("Cancel", "إلغاء العملية"))
                        }
                    }
                }
            }
        }

        model.problem?.let { prob ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = problemText(prob, arabic),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        model.info?.let { media ->
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!media.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = media.thumbnailUrl,
                                contentDescription = media.title,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (media.isPlaylist) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val platformName = model.normalizedMetadata?.platform ?: "Web"
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        text = platformName,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (media.subtitles.isNotEmpty()) {
                                    Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                        Text(
                                            text = "CC (${media.subtitles.size})",
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = media.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (media.isPlaylist) "${media.playlistCount} " + t("items in playlist", "عنصراً في القائمة")
                                else (media.duration?.let { ClipRequest.formatSeconds(it) } ?: t("Ready to download", "جاهز للتنزيل")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    HorizontalDivider()

                    if (media.isPlaylist) {
                        Text(t("Video Quality for Playlist:", "دقة الفيديو لقائمة التشغيل:"), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1080, 720, 480, 360).forEach { h ->
                                FilterChip(selected = height == h, onClick = { height = h }, enabled = !model.busy, label = { Text("${h}p") })
                            }
                        }
                        Button(
                            onClick = {
                                triggerDownloadWithDuplicateCheck {
                                    model.run(ProbeAction.PLAYLIST_VIDEO, height, bitrate)
                                }
                            },
                            enabled = !model.busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.VideoLibrary, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Download Full Playlist (Video)", "تنزيل القائمة كاملة (فيديو)"))
                        }
                        Button(
                            onClick = {
                                triggerDownloadWithDuplicateCheck {
                                    model.run(ProbeAction.PLAYLIST_MP3, height, bitrate)
                                }
                            },
                            enabled = !model.busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Audiotrack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Download Full Playlist (MP3)", "تنزيل القائمة كاملة (صوت MP3)"))
                        }
                    } else {
                        // 4-Tab Media Studio: Video, Audio, Clip Studio, Subtitles
                        var mediaTab by rememberSaveable(media.title) { mutableIntStateOf(0) }
                        val totalDur = media.duration ?: 60.0

                        // Clip state
                        var clipStart by remember(media.title) { mutableDoubleStateOf(0.0) }
                        var clipEnd by remember(media.title) {
                            mutableDoubleStateOf(if (totalDur > 30.0) 30.0.coerceAtMost(totalDur) else totalDur)
                        }
                        var clipHeight by remember(media.title) { mutableIntStateOf(media.defaultHeight) }
                        var clipIsBest by remember(media.title) { mutableStateOf(false) }
                        var clipFormat by remember(media.title) { mutableStateOf("mp4") }
                        var clipIsAudio by remember(media.title) { mutableStateOf(false) }

                        TabRow(
                            selectedTabIndex = mediaTab,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Tab(
                                selected = mediaTab == 0,
                                onClick = { mediaTab = 0 },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(t("Video", "فيديو"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                            Tab(
                                selected = mediaTab == 1,
                                onClick = { mediaTab = 1 },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(t("Audio", "صوت"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                            Tab(
                                selected = mediaTab == 2,
                                onClick = { mediaTab = 2 },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(t("Clip", "قص"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                            Tab(
                                selected = mediaTab == 3,
                                onClick = { mediaTab = 3 },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text(t("Subs", "ترجمة"), fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }

                        when (mediaTab) {
                            0 -> {
                                // Video Tab
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(t("Resolution:", "الدقة:"), fontWeight = FontWeight.SemiBold)
                                    if (media.heights.isEmpty()) {
                                        Text(t("Single stream available", "تدفق واحد متاح"))
                                    }
                                    media.heights.chunked(4).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            row.forEach { h ->
                                                FilterChip(selected = height == h, onClick = { height = h }, enabled = !model.busy, label = { Text("${h}p") })
                                            }
                                        }
                                    }

                                    Text(t("Format:", "الصيغة:"), fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("mp4", "mkv", "webm").forEach { fmt ->
                                            FilterChip(selected = videoFormat == fmt, onClick = { videoFormat = fmt }, label = { Text(fmt.uppercase()) })
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            triggerDownloadWithDuplicateCheck {
                                                model.run(ProbeAction.VIDEO, height, bitrate)
                                            }
                                        },
                                        enabled = !model.busy,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(t("Download Full Video (${height}p)", "تنزيل الفيديو كاملاً (${height}p)"))
                                    }
                                }
                            }
                            1 -> {
                                // Audio Tab
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(t("Bitrate Quality:", "جودة الصوت:"), fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(320 to "320 kbps (High)", 192 to "192 kbps (Standard)", 128 to "128 kbps (Saver)").forEach { (br, label) ->
                                            FilterChip(selected = bitrate == br, onClick = { bitrate = br }, label = { Text(label) })
                                        }
                                    }

                                    Text(t("Audio Format:", "صيغة الصوت:"), fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("mp3", "m4a").forEach { fmt ->
                                            FilterChip(selected = audioFormat == fmt, onClick = { audioFormat = fmt }, label = { Text(fmt.uppercase()) })
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            triggerDownloadWithDuplicateCheck {
                                                model.run(ProbeAction.MP3, height, bitrate)
                                            }
                                        },
                                        enabled = !model.busy,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Audiotrack, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(t("Extract & Download Audio ($bitrate kbps)", "استخراج وتحميل الصوت ($bitrate kbps)"))
                                    }
                                }
                            }
                            2 -> {
                                // Clip Studio Tab
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    DualHandleTimeline(
                                        totalDuration = totalDur,
                                        startSeconds = clipStart,
                                        endSeconds = clipEnd,
                                        onRangeChanged = { s, e ->
                                            clipStart = s
                                            clipEnd = e
                                        },
                                        arabic = arabic
                                    )

                                    PreciseTimeSection(
                                        startSeconds = clipStart,
                                        endSeconds = clipEnd,
                                        totalDuration = totalDur,
                                        onStartChanged = { clipStart = it },
                                        onEndChanged = { clipEnd = it },
                                        arabic = arabic
                                    )

                                    QuickSelectionChips(
                                        totalDuration = totalDur,
                                        onApplyRange = { s, e ->
                                            clipStart = s
                                            clipEnd = e
                                        },
                                        arabic = arabic
                                    )

                                    ClipPreviewPlayer(
                                        streamUrl = media.streamUrl,
                                        startSeconds = clipStart,
                                        endSeconds = clipEnd,
                                        arabic = arabic
                                    )

                                    QualitySelectionSection(
                                        availableHeights = media.heights,
                                        selectedHeight = clipHeight,
                                        isBestQuality = clipIsBest,
                                        onSelectQuality = { h, best ->
                                            clipHeight = h
                                            clipIsBest = best
                                        },
                                        arabic = arabic
                                    )

                                    FormatSelectionSection(
                                        selectedFormat = clipFormat,
                                        isAudioOnly = clipIsAudio,
                                        onSelectFormat = { f, audio ->
                                            clipFormat = f
                                            clipIsAudio = audio
                                        },
                                        arabic = arabic
                                    )

                                    ClipSummaryCard(
                                        clip = ClipRequest(
                                            startSeconds = clipStart,
                                            endSeconds = clipEnd,
                                            height = clipHeight,
                                            format = clipFormat,
                                            isBestQuality = clipIsBest,
                                            isAudioOnly = clipIsAudio
                                        ),
                                        totalDuration = totalDur,
                                        availableFormats = media.formats,
                                        onDownloadClick = {
                                            triggerDownloadWithDuplicateCheck {
                                                model.runClip(
                                                    ClipRequest(
                                                        startSeconds = clipStart,
                                                        endSeconds = clipEnd,
                                                        height = clipHeight,
                                                        format = clipFormat,
                                                        isBestQuality = clipIsBest,
                                                        isAudioOnly = clipIsAudio
                                                    )
                                                )
                                            }
                                        },
                                        busy = model.busy,
                                        arabic = arabic
                                    )
                                }
                            }
                            3 -> {
                                // Subtitles Tab
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val currentSubs = model.normalizedMetadata?.subtitles ?: media.subtitles.map {
                                        MediaSubtitle(
                                            languageCode = it.lang,
                                            languageName = it.name.ifBlank { it.lang },
                                            format = it.ext,
                                            url = it.url,
                                            isAutoGenerated = it.isAuto
                                        )
                                    }
                                    if (currentSubs.isEmpty()) {
                                        Text(
                                            t("No subtitles or captions available for this media.", "لا تتوفر ملفات ترجمة لهذا الوسيط."),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    } else {
                                        Text(
                                            t("Available Subtitles (${currentSubs.size}):", "الترجمات المتاحة (${currentSubs.size}):"),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        currentSubs.take(4).forEach { track ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("${track.languageName} (${track.languageCode.uppercase()})", style = MaterialTheme.typography.bodyMedium)
                                                if (track.isAutoGenerated) {
                                                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                                        Text("Auto", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                        Button(
                                            onClick = { showSubtitleCenter = true },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Subtitles, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(t("Open Subtitle Center & Export", "فتح مركز الترجمة والتصدير"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showSupportedSources) {
        SupportedSourcesDialog(onDismiss = { showSupportedSources = false }, arabic = arabic)
    }

    if (showBatchDownload) {
        BatchDownloadDialog(queueManager = model.queueManager, onDismiss = { showBatchDownload = false }, arabic = arabic)
    }

    if (showStorageManager) {
        StorageManagerDialog(onDismiss = { showStorageManager = false }, arabic = arabic)
    }

    if (showSubtitleCenter && model.info != null) {
        val subsList = model.normalizedMetadata?.subtitles ?: model.info!!.subtitles.map {
            MediaSubtitle(
                languageCode = it.lang,
                languageName = it.name.ifBlank { it.lang },
                format = it.ext,
                url = it.url,
                isAutoGenerated = it.isAuto
            )
        }
        SubtitleCenterDialog(
            title = model.info!!.title,
            subtitles = subsList,
            onDismiss = { showSubtitleCenter = false },
            arabic = arabic
        )
    }

    duplicateItemToConfirm?.let { existingItem ->
        AlertDialog(
            onDismissRequest = {
                duplicateItemToConfirm = null
                pendingDownloadAction = null
            },
            title = { Text(t("Already Saved", "محفوظ بالفعل")) },
            text = {
                Text(
                    t(
                        "'${existingItem.title}' is already in your Pocket Library. Do you want to open the existing file or download a new copy?",
                        "العنصر '${existingItem.title}' موجود بالفعل في مكتبة الجيب. هل ترغب في فتح الملف الحالي أم تنزيل نسخة جديدة؟"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val action = pendingDownloadAction
                        duplicateItemToConfirm = null
                        pendingDownloadAction = null
                        action?.invoke()
                        onNavigateDownloads()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(t("Download Again", "تنزيل مجدداً"))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        duplicateItemToConfirm = null
                        pendingDownloadAction = null
                    }) {
                        Text(t("Cancel", "إلغاء"))
                    }
                    Button(
                        onClick = {
                            PocketLibraryManager.playItem(context, existingItem)
                            duplicateItemToConfirm = null
                            pendingDownloadAction = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(t("Open Existing", "فتح الموجود"))
                    }
                }
            }
        )
    }
}

@Composable
fun DownloadsScreen(model: ProbeModel, arabic: Boolean) {
    val context = LocalContext.current
    fun t(en: String, ar: String) = if (arabic) ar else en

    var queueFilter by rememberSaveable { mutableStateOf("all") }
    val allTasks = model.queueManager.tasks
    val filteredTasks = remember(allTasks, queueFilter) {
        when (queueFilter) {
            "active" -> allTasks.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.MERGING || it.state == DownloadState.SAVING || it.state == DownloadState.RESOLVING }
            "completed" -> allTasks.filter { it.state == DownloadState.COMPLETED }
            "failed" -> allTasks.filter { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED }
            "paused" -> allTasks.filter { it.state == DownloadState.PAUSED }
            else -> allTasks
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = t("Downloads & Queue", "التنزيلات وقائمة الانتظار"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = t("Saved to Downloads/VideoPocket", "تم الحفظ في مجلد التنزيلات/VideoPocket"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (allTasks.any { it.state == DownloadState.COMPLETED }) {
                TextButton(onClick = { model.queueManager.clearCompleted() }) {
                    Text(t("Clear Completed", "مسح المكتمل"))
                }
            }
        }

        if (allTasks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = queueFilter == "all",
                    onClick = { queueFilter = "all" },
                    label = { Text(t("All (${allTasks.size})", "الكل (${allTasks.size})")) }
                )
                FilterChip(
                    selected = queueFilter == "active",
                    onClick = { queueFilter = "active" },
                    label = { Text(t("Active", "النشطة")) }
                )
                FilterChip(
                    selected = queueFilter == "completed",
                    onClick = { queueFilter = "completed" },
                    label = { Text(t("Completed", "المكتملة")) }
                )
                FilterChip(
                    selected = queueFilter == "failed",
                    onClick = { queueFilter = "failed" },
                    label = { Text(t("Failed", "فشل")) }
                )
            }

            filteredTasks.forEach { task ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Badge(
                                containerColor = when (task.state) {
                                    DownloadState.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                                    DownloadState.FAILED, DownloadState.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                                    DownloadState.DOWNLOADING, DownloadState.MERGING, DownloadState.TRANSCODING -> MaterialTheme.colorScheme.tertiaryContainer
                                    DownloadState.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (arabic) task.state.arabicLabel else task.state.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "${task.platform} • ${task.quality} • ${task.format.uppercase()}" +
                                if (task.isClip) " • ✂ ${task.clipRequest?.durationFormatted() ?: ""}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        if (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.TRANSCODING || task.state == DownloadState.MERGING || task.state == DownloadState.SAVING) {
                            LinearProgressIndicator(
                                progress = { if (task.progress > 0) task.progress / 100f else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${task.progress.toInt()}%", style = MaterialTheme.typography.labelSmall)
                                if (task.speedText.isNotBlank()) {
                                    Text("${task.speedText} · ETA: ${task.etaText}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (task.state == DownloadState.FAILED && task.error != null) {
                            Text(
                                text = task.error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (task.state) {
                                DownloadState.DOWNLOADING -> {
                                    IconButton(onClick = { model.queueManager.pause(task.id) }) {
                                        Icon(Icons.Default.Pause, contentDescription = t("Pause", "إيقاف مؤقت"))
                                    }
                                }
                                DownloadState.PAUSED -> {
                                    IconButton(onClick = { model.queueManager.resume(task.id) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = t("Resume", "استئناف"))
                                    }
                                }
                                DownloadState.FAILED -> {
                                    IconButton(onClick = { model.queueManager.retry(task.id) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = t("Retry", "إعادة المحاولة"))
                                    }
                                }
                                DownloadState.COMPLETED -> {
                                    if (task.outputUri != null) {
                                        Button(
                                            onClick = {
                                                runCatching {
                                                    val mime = if (task.format.contains("mp3", true)) "audio/*" else "video/*"
                                                    val intent = Intent(Intent.ACTION_VIEW)
                                                        .setDataAndType(Uri.parse(task.outputUri), mime)
                                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(t("Play", "تشغيل"))
                                        }
                                    }
                                }
                                else -> {}
                            }

                            if (task.state != DownloadState.COMPLETED) {
                                IconButton(onClick = { model.queueManager.cancel(task.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = t("Cancel", "إلغاء"), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (model.results.isNotEmpty()) {
            Text(
                text = t("Recent Activity Logs", "سجل العمليات الأخير"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            model.results.reversed().take(10).forEach { result ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = result.action.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Badge(
                                containerColor = if (result.passed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = if (result.passed) t("SUCCESS", "نجح") else t("FAILED", "فشل"),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${result.elapsedMs} ms · ${result.bytes / 1024} KB · ${result.detail}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        result.outputUri?.let { uriStr ->
                            Button(
                                onClick = {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
                                            Uri.parse(uriStr),
                                            if (result.action.name.contains("MP3")) "audio/mpeg" else "video/*"
                                        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(intent)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(t("Play / Open Saved File", "تشغيل / فتح الملف المحفوظ"))
                            }
                        }
                    }
                }
            }
        }

        if (allTasks.isEmpty() && model.results.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(32.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(t("No downloads yet", "لا توجد تنزيلات حتى الآن"), style = MaterialTheme.typography.titleMedium)
                    Text(t("Paste a link in Home to start downloading.", "ألصق رابطاً في الرئيسية لبدء التنزيل."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(model: ProbeModel, arabic: Boolean) {
    val context = LocalContext.current
    fun t(en: String, ar: String) = if (arabic) ar else en

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableStateOf("all") }
    var selectedCollection by rememberSaveable { mutableStateOf<String?>(null) }

    var itemToRename by remember { mutableStateOf<PocketLibraryItem?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<PocketLibraryItem?>(null) }

    var playingItem by remember { mutableStateOf<PocketLibraryItem?>(null) }
    var collectionTargetItem by remember { mutableStateOf<PocketLibraryItem?>(null) }

    val allCollections = remember(model.libraryItems) {
        model.libraryItems.flatMap { it.collections }.distinct()
    }

    val filteredList = remember(model.libraryItems, filterMode, searchQuery, selectedCollection) {
        model.libraryItems.filter { item ->
            val matchesFilter = when (filterMode) {
                "clips" -> item.isClip
                "videos" -> !item.isClip && !item.format.equals("mp3", true) && !item.resolution.contains("Audio", true)
                "audio" -> item.format.equals("mp3", true) || item.resolution.contains("Audio", true)
                "favorites" -> item.isFavorite
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                (item.platform?.contains(searchQuery, ignoreCase = true) == true)
            val matchesCollection = selectedCollection == null || item.collections.contains(selectedCollection)

            matchesFilter && matchesSearch && matchesCollection
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = t("Pocket Library", "مكتبة الجيب"),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = t("Your downloaded media & clips archive", "أرشيف الوسائط والمقاطع المحفوظة"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(t("Search by title or platform...", "ابحث بالعنوان أو المنصة...")) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = t("Clear", "مسح"))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterMode == "all",
                onClick = { filterMode = "all" },
                label = { Text(t("All (${model.libraryItems.size})", "الكل (${model.libraryItems.size})")) }
            )
            FilterChip(
                selected = filterMode == "videos",
                onClick = { filterMode = "videos" },
                leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(t("Videos", "فيديو")) }
            )
            FilterChip(
                selected = filterMode == "audio",
                onClick = { filterMode = "audio" },
                leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(t("Audio", "صوت")) }
            )
            FilterChip(
                selected = filterMode == "clips",
                onClick = { filterMode = "clips" },
                leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(t("Clips", "مقاطع")) }
            )
            FilterChip(
                selected = filterMode == "favorites",
                onClick = { filterMode = "favorites" },
                leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(t("Favorites", "المفضلة")) }
            )
        }

        // Collections filter chips (if any collections exist)
        if (allCollections.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCollection == null,
                    onClick = { selectedCollection = null },
                    label = { Text(t("All Collections", "كل المجموعات")) }
                )
                allCollections.forEach { colName ->
                    FilterChip(
                        selected = selectedCollection == colName,
                        onClick = { selectedCollection = if (selectedCollection == colName) null else colName },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        label = { Text(colName) }
                    )
                }
            }
        }

        if (filteredList.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(32.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(t("No media found", "لا توجد عناصر محفوظة"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("Downloaded videos and clips will appear here automatically.", "الفيديوهات والمقاطع المحملة ستظهر هنا تلقائياً."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        } else {
            filteredList.forEach { item ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!item.thumbnailUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = item.thumbnailUri,
                                    contentDescription = item.title,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (item.isClip) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(60.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (item.isClip) Icons.Default.ContentCut else Icons.Default.Movie,
                                            contentDescription = null,
                                            tint = if (item.isClip) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    item.platform?.let { plat ->
                                        Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                            Text(
                                                text = plat,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (item.isClip) {
                                        Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                            Text(
                                                text = t("✂ CLIP", "✂ مقطع"),
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${item.resolution} • ${item.format}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2
                                )
                                Text(
                                    text = if (item.isClip) "${item.clipRange} (${item.duration})" else item.duration,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (item.collections.isNotEmpty()) {
                                    Text(
                                        text = "📁 " + item.collections.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }

                            IconButton(onClick = { model.toggleFavorite(item.id) }) {
                                Icon(
                                    imageVector = if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = t("Favorite", "المفضلة"),
                                    tint = if (item.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp)

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { playingItem = item },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(t("Play", "تشغيل"))
                            }

                            IconButton(onClick = { collectionTargetItem = item }) {
                                Icon(Icons.Default.Folder, contentDescription = t("Collections", "المجموعات"))
                            }

                            IconButton(onClick = { PocketLibraryManager.shareItem(context, item) }) {
                                Icon(Icons.Default.Share, contentDescription = t("Share", "مشاركة"))
                            }

                            IconButton(onClick = {
                                itemToRename = item
                                renameInput = item.title
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = t("Rename", "تعديل الاسم"))
                            }

                            IconButton(onClick = { itemToDelete = item }) {
                                Icon(Icons.Default.Delete, contentDescription = t("Delete", "حذف"), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // Built-in Player Dialog
    playingItem?.let { currentItem ->
        BuiltInPlayerDialog(
            item = currentItem,
            onDismiss = { playingItem = null },
            arabic = arabic
        )
    }

    // Collections Management Dialog
    collectionTargetItem?.let { currentItem ->
        CollectionsDialog(
            item = currentItem,
            onDismiss = { collectionTargetItem = null },
            onUpdated = {
                collectionTargetItem = null
                model.refreshLibrary()
            },
            arabic = arabic
        )
    }

    itemToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text(t("Rename Media", "إعادة تسمية العنصر")) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(t("New Title", "الاسم الجديد")) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            model.renameLibraryItem(target.id, renameInput.trim())
                        }
                        itemToRename = null
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(t("Save", "حفظ"))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text(t("Cancel", "إلغاء"))
                }
            }
        )
    }

    itemToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(t("Delete Item?", "حذف هذا العنصر؟")) },
            text = {
                Text(
                    t(
                        "Are you sure you want to delete '${target.title}' from Pocket Library?",
                        "هل أنت متأكد من رغبتك في حذف '${target.title}' من مكتبة الجيب؟"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        model.deleteLibraryItem(target.id)
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(t("Delete", "حذف"))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(t("Cancel", "إلغاء"))
                }
            }
        )
    }
}

@Composable
fun SettingsScreen(model: ProbeModel, arabic: Boolean, onToggleLanguage: () -> Unit) {
    fun t(en: String, ar: String) = if (arabic) ar else en
    var exportStatus by remember { mutableStateOf("") }
    var showSupportedSources by remember { mutableStateOf(false) }
    var showStorageManager by remember { mutableStateOf(false) }

    var selectedPreset by rememberSaveable { mutableStateOf("best") }
    var maxConcurrent by rememberSaveable { mutableIntStateOf(model.queueManager.maxConcurrent) }
    var wifiOnly by rememberSaveable { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri) { ok -> exportStatus = if (ok) t("Report saved successfully", "تم حفظ التقرير بنجاح") else t("Could not save report", "تعذر حفظ التقرير") }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = t("Settings", "الإعدادات"),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        // Download Preferences Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Download Preferences", "تفضيلات التنزيل"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)

                Text(t("Quality Preset:", "إعداد الجودة الافتراضي:"), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("best" to "Best (1080p+)", "balanced" to "Balanced (720p)", "saver" to "Saver (480p)", "audio" to "Audio Only").forEach { (preset, label) ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(label) }
                        )
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)

                Text(t("Max Concurrent Downloads:", "الحد الأقصى للتنزيلات المتزامنة:"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { count ->
                        FilterChip(
                            selected = maxConcurrent == count,
                            onClick = {
                                maxConcurrent = count
                                model.queueManager.maxConcurrent = count
                            },
                            label = { Text("$count") }
                        )
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(t("Wi-Fi Only Mode", "التنزيل عبر Wi-Fi فقط"), style = MaterialTheme.typography.bodyMedium)
                        Text(t("Prevents mobile cellular data usage", "يمنع استهلاك باقة بيانات الهاتف"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
            }
        }

        // Tools & Storage Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("Platform & Storage Tools", "أدوات المنصات والتخزين"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)

                OutlinedButton(
                    onClick = { showSupportedSources = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Public, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("Supported Platforms & Feature Matrix", "المنصات المدعومة ومصفوفة الميزات"))
                }

                OutlinedButton(
                    onClick = { showStorageManager = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("Storage Manager & Cache Cleanup", "إدارة التخزين ومسح الذاكرة المؤقتة"))
                }
            }
        }

        // App Preferences & Diagnostics
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Language & Diagnostics", "اللغة والتشخيص"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t("Language / اللغة", "اللغة / Language"))
                    OutlinedButton(onClick = onToggleLanguage, shape = RoundedCornerShape(10.dp)) {
                        Text(if (arabic) "English" else "العربية")
                    }
                }

                HorizontalDivider(thickness = 0.5.dp)

                OutlinedButton(
                    onClick = { exporter.launch("VideoPocket-V2-Report.json") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("Export Diagnostic Report", "تصدير تقرير التشخيص"))
                }
                if (exportStatus.isNotBlank()) {
                    Text(exportStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showSupportedSources) {
        SupportedSourcesDialog(onDismiss = { showSupportedSources = false }, arabic = arabic)
    }

    if (showStorageManager) {
        StorageManagerDialog(onDismiss = { showStorageManager = false }, arabic = arabic)
    }
}

private fun problemText(p: Problem, ar: Boolean): String = when (p) {
    Problem.INVALID_URL -> if (ar) "أدخل رابط HTTP/HTTPS صالحًا." else "Enter a valid HTTP/HTTPS URL."
    Problem.UNSUPPORTED -> if (ar) "الرابط أو الصيغة المطلوبة غير مدعومة." else "Unsupported URL or format."
    Problem.LOGIN_REQUIRED -> if (ar) "المصدر يتطلب تسجيل دخول." else "The source requires login."
    Problem.REMOVED -> if (ar) "المحتوى محذوف أو غير موجود." else "Content removed or not found."
    Problem.RESTRICTED -> if (ar) "تم تقييد الوصول أو تجاوز حد الطلبات مؤقتاً (Rate limit). يرجى الانتظار قليلاً والمحاولة لاحقاً." else "Access restricted or rate limit exceeded. Please wait a moment and try again."
    Problem.LIVE_OR_PLAYLIST -> if (ar) "البث المباشر غير مدعوم؛ استخدم فيديو أو قائمة تشغيل مكتملة." else "Live streams are not supported; use completed videos or playlists."
    Problem.SPACE -> if (ar) "مساحة التخزين غير كافية." else "Insufficient storage space."
    Problem.NETWORK -> if (ar) "تعذر الاتصال بالشبكة." else "Network connection error."
    Problem.CONVERSION -> if (ar) "فشل التحويل أو معالجة الوسائط." else "Conversion or media processing failed."
    Problem.ENGINE -> if (ar) "خطأ في محرك yt-dlp أو توافق الجهاز." else "Engine error or device compatibility issue."
    Problem.CANCELED -> if (ar) "تم إلغاء العملية." else "Operation canceled."
}
