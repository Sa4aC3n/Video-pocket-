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
import java.io.File
import java.time.Instant
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var info by mutableStateOf<MediaInfo?>(null); private set
    var problem by mutableStateOf<Problem?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var results by mutableStateOf(listOf<ProbeResult>()); private set
    var runtime by mutableStateOf(JSONObject()); private set
    var initErrorDetail by mutableStateOf<String?>(null); private set
    private var inspectedUrl: String? = null
    private val reportFile get() = File(getApplication<Application>().filesDir, "last-report.json")

    init {
        viewModelScope.launch {
            delay(200)
            retryInit()
        }
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
        busy = true; problem = null; info = null; inspectedUrl = null
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val valid = LinkPolicy.validate(url)
                info = withContext(Dispatchers.IO) { engine.inspect(valid, forcePlaylist) }
                inspectedUrl = valid
                val detailMsg = if (info!!.isPlaylist) "Playlist inspected; entries=${info!!.playlistCount}" else "Metadata extracted; formats=${info!!.formats.size}"
                record(ProbeResult(ProbeAction.INSPECT, (System.nanoTime()-started)/1_000_000, 0, null, true, detailMsg))
            } catch (e: Exception) {
                problem = ProbeEngine.classify(e)
                record(ProbeResult(ProbeAction.INSPECT, (System.nanoTime()-started)/1_000_000, 0, null, false, problem!!.name))
            } finally { busy = false }
        }
    }

    fun run(action: ProbeAction, height: Int, bitrate: Int) {
        if (busy || !ready) return
        val media = info ?: return
        val url = inspectedUrl ?: return
        busy = true; problem = null; progress = 0f
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = withContext(Dispatchers.IO) { engine.download(url, media, action, height, bitrate) { p ->
                    viewModelScope.launch { progress = p }
                } }
                record(result)
            } catch (e: Exception) {
                problem = ProbeEngine.classify(e)
                record(ProbeResult(action, (System.nanoTime()-started)/1_000_000, 0, null, false, problem!!.name))
            } finally { busy = false }
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
        val clip = clipboardManager.getText()?.text
            ?: (context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()
        if (!clip.isNullOrBlank()) {
            url = clip.trim()
            model.inspect(url, playlistMode)
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
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = t("Save. Watch. Keep.", "احفظ. شاهِد. احتفظ."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            FilledTonalIconButton(onClick = onToggleLanguage) {
                Icon(Icons.Default.Language, contentDescription = t("Toggle Language", "تغيير اللغة"))
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
                    text = t("Paste Video Link", "لصق رابط الفيديو"),
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = !model.busy,
                    placeholder = { Text(t("Paste a video or playlist link...", "ألصق رابط فيديو أو قائمة تشغيل...")) },
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { if (model.progress > 0f) model.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = t("Analyzing / Downloading · ", "جاري الفحص أو التنزيل · ") + "${model.progress.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (media.isPlaylist) Icons.AutoMirrored.Filled.PlaylistPlay else Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = media.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2
                            )
                            Text(
                                text = if (media.isPlaylist) "${media.playlistCount} " + t("items in playlist", "عنصراً في القائمة")
                                else (media.duration?.let { "${it.toInt()} s" } ?: t("Ready to download", "جاهز للتنزيل")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    HorizontalDivider()

                    if (media.isPlaylist) {
                        Text(t("Video Quality for Playlist:", "دقة الفيديو لقائمة التشغيل:"))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1080, 720, 480, 360).forEach { h ->
                                FilterChip(selected = height == h, onClick = { height = h }, enabled = !model.busy, label = { Text("${h}p") })
                            }
                        }
                        Button(
                            onClick = {
                                model.run(ProbeAction.PLAYLIST_VIDEO, height, bitrate)
                                onNavigateDownloads()
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
                                model.run(ProbeAction.PLAYLIST_MP3, height, bitrate)
                                onNavigateDownloads()
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
                        Text(t("Select Resolution:", "اختر الدقة:"))
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
                        Button(
                            onClick = {
                                model.run(ProbeAction.VIDEO, height, bitrate)
                                onNavigateDownloads()
                            },
                            enabled = !model.busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Quick Download Video", "تنزيل سريع للفيديو"))
                        }
                        Button(
                            onClick = {
                                model.run(ProbeAction.MP3, height, bitrate)
                                onNavigateDownloads()
                            },
                            enabled = !model.busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Audiotrack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Extract Audio (MP3)", "استخراج الصوت (MP3)"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(model: ProbeModel, arabic: Boolean) {
    val context = LocalContext.current
    fun t(en: String, ar: String) = if (arabic) ar else en

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = t("Downloads & Results", "التنزيلات والنتائج"),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = t("Saved to Downloads/VideoPocket", "تم الحفظ في مجلد التنزيلات/VideoPocket"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        if (model.results.isEmpty()) {
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
        } else {
            model.results.reversed().forEach { result ->
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
    }
}

@Composable
fun LibraryScreen(model: ProbeModel, arabic: Boolean) {
    fun t(en: String, ar: String) = if (arabic) ar else en
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
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = t("Your downloaded media archive", "أرشيف الوسائط الذي قمت بتنزيله"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

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
                Text(t("Your Pocket is tidy", "جيبك مرتب ونظيف"), style = MaterialTheme.typography.titleMedium)
                Text(t("All completed downloads appear here and in your Downloads folder.", "جميع التنزيلات المكتملة تظهر هنا وفي مجلد التنزيلات."), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SettingsScreen(model: ProbeModel, arabic: Boolean, onToggleLanguage: () -> Unit) {
    fun t(en: String, ar: String) = if (arabic) ar else en
    var exportStatus by remember { mutableStateOf("") }
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
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Preferences", "التفضيلات"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
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
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Diagnostics & Reports", "التشخيص والتقارير"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
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
}

private fun problemText(p: Problem, ar: Boolean): String = when (p) {
    Problem.INVALID_URL -> if (ar) "أدخل رابط HTTP/HTTPS صالحًا." else "Enter a valid HTTP/HTTPS URL."
    Problem.UNSUPPORTED -> if (ar) "الرابط أو الصيغة المطلوبة غير مدعومة." else "Unsupported URL or format."
    Problem.LOGIN_REQUIRED -> if (ar) "المصدر يتطلب تسجيل دخول." else "The source requires login."
    Problem.REMOVED -> if (ar) "المحتوى محذوف أو غير موجود." else "Content removed or not found."
    Problem.RESTRICTED -> if (ar) "تم تقييد الوصول للمصدر." else "The source restricted access."
    Problem.LIVE_OR_PLAYLIST -> if (ar) "البث المباشر غير مدعوم؛ استخدم فيديو أو قائمة تشغيل مكتملة." else "Live streams are not supported; use completed videos or playlists."
    Problem.SPACE -> if (ar) "مساحة التخزين غير كافية." else "Insufficient storage space."
    Problem.NETWORK -> if (ar) "تعذر الاتصال بالشبكة." else "Network connection error."
    Problem.CONVERSION -> if (ar) "فشل التحويل أو معالجة الوسائط." else "Conversion or media processing failed."
    Problem.ENGINE -> if (ar) "خطأ في محرك yt-dlp أو توافق الجهاز." else "Engine error or device compatibility issue."
    Problem.CANCELED -> if (ar) "تم إلغاء العملية." else "Operation canceled."
}
