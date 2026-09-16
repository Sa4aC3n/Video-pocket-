package org.videopocket.probe

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        setContent { MaterialTheme { ProbeScreen(shared) } }
    }
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
            delay(250)
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
    fun inspect(url: String) {
        if (busy || !ready) return
        busy = true; problem = null; info = null; inspectedUrl = null
        viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val valid = LinkPolicy.validate(url)
                info = withContext(Dispatchers.IO) { engine.inspect(valid) }; inspectedUrl = valid
                record(ProbeResult(ProbeAction.INSPECT, (System.nanoTime()-started)/1_000_000, 0, null, true, "Metadata extracted; formats=${info!!.formats.size}"))
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
    fun report(): String = JSONObject().put("app", "VideoPocket 0.1.0-probe")
        .put("generatedAt", Instant.now().toString()).put("runtime", runtime)
        .put("scope", "Foreground technical probe; no background/queue tests; URLs and titles excluded")
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

@Composable private fun ProbeScreen(shared: String, model: ProbeModel = viewModel()) {
    val context = LocalContext.current
    var arabic by rememberSaveableCompat { Locale.getDefault().language == "ar" }
    fun t(en: String, ar: String) = if (arabic) ar else en
    var url by remember { mutableStateOf(shared) }
    var height by remember { mutableIntStateOf(1080) }
    var bitrate by remember { mutableIntStateOf(192) }
    var exportStatus by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri) { ok -> exportStatus = if (ok) t("Report saved", "تم حفظ التقرير") else t("Could not save report", "تعذر حفظ التقرير") }
    }
    LaunchedEffect(model.info) { model.info?.let { height = it.defaultHeight } }
    DisposableEffect(model.busy) {
        val window = (context as? ComponentActivity)?.window
        if (model.busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("VideoPocket", style = MaterialTheme.typography.headlineLarge)
                Text(t("Engine test · 0.1.0", "اختبار المحرك · 0.1.0"), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { arabic = !arabic }) { Text(if (arabic) "English" else "العربية") }
                Text(t("Keep this screen open during tests. Use short public videos you may download. This is not the final app.", "خلي الشاشة مفتوحة أثناء الاختبار. استخدم مقاطع عامة قصيرة مسموح لك تنزيلها. دي نسخة اختبار وليست التطبيق النهائي."))
                Text(t("One operation at a time; 512 MB per stream. Files: Downloads/VideoPocket.", "عملية واحدة كل مرة؛ 512 ميجابايت لكل مسار. الملفات في Downloads/VideoPocket."))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (model.ready) t("Engine initialized", "تم تجهيز المحرك")
                        else t("Engine not ready", "المحرك غير جاهز"),
                        color = if (model.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!model.ready && !model.busy) {
                        OutlinedButton(onClick = { model.retryInit() }) {
                            Text(t("Retry", "إعادة المحاولة"))
                        }
                    }
                }
                model.initErrorDetail?.let { detail ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(value = url, onValueChange = { url = it }, enabled = !model.busy, label = { Text(t("Public HTTP/HTTPS URL", "رابط عام HTTP/HTTPS")) }, modifier = Modifier.fillMaxWidth())
                if (url.startsWith("http:")) Text(t("This source uses unencrypted HTTP.", "المصدر ده بيستخدم اتصال HTTP غير مشفّر."))
                Button(onClick = { model.inspect(url) }, enabled = model.ready && !model.busy && url.isNotBlank()) { Text(t("1. Inspect formats", "١. فحص الجودات")) }
                model.info?.let { media ->
                    Text(media.title, style = MaterialTheme.typography.titleLarge)
                    Text(t("Duration: ", "المدة: ") + (media.duration?.let { "${it.toInt()} s" } ?: t("Unknown", "غير معروفة")))
                    Text(t("Available resolutions", "الدقات المتاحة"))
                    if (media.heights.isEmpty()) Text(t("Resolution not reported by source", "المصدر لم يحدد الدقة"))
                    media.heights.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { h -> FilterChip(selected = height == h, onClick = { height = h }, enabled = !model.busy, label = { Text("${h}p") }) }
                    } }
                    val estimate = media.formats.filter { it.height == height }.mapNotNull { it.bytes }.maxOrNull()
                    Text(t("Approx. video stream size: ", "حجم مسار الفيديو التقريبي: ") + (estimate?.let { "${it / 1024 / 1024} MB" } ?: t("Unknown", "غير معروف")))
                    Button(onClick = { model.run(ProbeAction.VIDEO, height, bitrate) }, enabled = !model.busy) { Text(t("2. Download original video", "٢. تنزيل الفيديو بصيغته الأصلية")) }
                    Text(t("Merge explicitly requires separate video and audio streams; the result uses MKV without re-encoding.", "اختبار الدمج يحتاج مساري فيديو وصوت منفصلين؛ الناتج MKV بدون إعادة ترميز."))
                    Button(onClick = { model.run(ProbeAction.MERGE, height, bitrate) }, enabled = !model.busy) { Text(t("3. Test two-stream merge", "٣. اختبار دمج المسارين")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(128,192,320).forEach { b ->
                        FilterChip(selected = bitrate == b, onClick = { bitrate = b }, enabled = !model.busy, label = { Text("$b kbps") })
                    } }
                    Button(onClick = { model.run(ProbeAction.MP3, height, bitrate) }, enabled = !model.busy) { Text(t("4. Convert to MP3", "٤. تحويل إلى MP3")) }
                }
                if (model.busy) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text(t("Working · ", "جاري العمل · ") + "${model.progress.toInt()}%") }
                model.problem?.let { Text(problemText(it, arabic), color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                Text(t("Results", "النتائج"), style = MaterialTheme.typography.titleLarge)
                model.results.reversed().forEach { result ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text("${result.action.name}: " + if (result.passed) t("PASS", "نجح") else t("FAIL", "فشل"))
                        Text("${result.elapsedMs} ms · ${result.bytes / 1024} KB")
                        result.outputUri?.let { uri -> TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), if (result.action == ProbeAction.MP3) "audio/mpeg" else "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                .onFailure { exportStatus = t("No compatible player installed", "مفيش مشغّل مناسب مثبت") }
                        }) { Text(t("Open saved file", "فتح الملف المحفوظ")) } }
                    } }
                }
                OutlinedButton(onClick = { exporter.launch("VideoPocket-report.json") }, enabled = !model.busy && model.results.isNotEmpty()) { Text(t("Save test report (no URLs)", "حفظ تقرير الاختبار (بدون روابط)")) }
                Text(exportStatus)
                Text(t("Automatic checks verify file tracks, not audio/video sync. Watch each result before marking the device test complete.", "الفحص التلقائي بيتأكد من وجود المسارات، وليس تزامن الصوت والصورة. شغّل كل ملف قبل اعتماد نجاح الاختبار."))
            }
        }
    }
}

@Composable private fun rememberSaveableCompat(initial: () -> Boolean) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial()) }

private fun problemText(p: Problem, ar: Boolean): String = when (p) {
    Problem.INVALID_URL -> if (ar) "أدخل رابط HTTP/HTTPS واحدًا بدون بيانات دخول." else "Enter one HTTP/HTTPS URL without credentials."
    Problem.UNSUPPORTED -> if (ar) "الرابط أو الصيغة المطلوبة غير متاحة؛ الدمج يحتاج مسارين منفصلين." else "Unsupported URL or format; merge requires separate streams."
    Problem.LOGIN_REQUIRED -> if (ar) "المصدر يحتاج تسجيل دخول؛ غير مدعوم في هذه النسخة." else "The source requires login, which this version does not support."
    Problem.REMOVED -> if (ar) "المحتوى محذوف أو غير موجود." else "Content removed or not found."
    Problem.RESTRICTED -> if (ar) "المصدر قيّد الوصول. جرّب لاحقًا." else "The source restricted access. Try later."
    Problem.LIVE_OR_PLAYLIST -> if (ar) "استخدم رابط فيديو فردي مكتمل، وليس بثًا مباشرًا أو قائمة." else "Use a completed single video, not a live stream or playlist."
    Problem.SPACE -> if (ar) "المساحة غير كافية لحفظ ومعالجة الملف." else "Insufficient space to process and save the file."
    Problem.NETWORK -> if (ar) "تعذر الاتصال بالمصدر؛ تحقق من الشبكة." else "Could not reach the source. Check your connection."
    Problem.CONVERSION -> if (ar) "فشل التحويل أو التحقق من مسارات الملف." else "Conversion or output track validation failed."
    Problem.ENGINE -> if (ar) "فشل المحرك. احفظ التقرير؛ قد يحتاج إصدارًا أحدث أو إصلاح توافق الجهاز." else "Engine failed. Save the report; an engine update or device compatibility fix may be required."
    Problem.CANCELED -> if (ar) "تم الإلغاء." else "Canceled."
}

