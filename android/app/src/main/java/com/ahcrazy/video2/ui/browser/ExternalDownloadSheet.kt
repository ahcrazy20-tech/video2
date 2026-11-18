package com.ahcrazy.video2.ui.browser

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.media.safeHeader
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.DownloadAuth
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.IOException

internal data class ExternalVideoChoice(val video: YouTubeVideo?, val title: String? = null, val type: ResolverType = ResolverType.COBALT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExternalDownloadSheet(ar: Boolean, choice: ExternalVideoChoice, preferredHeight: Int,
                                   onClose: () -> Unit, onDownload: (DetectedMedia) -> Unit) {
    val app = (LocalContext.current as Activity).app()
    val endpoints by app.settings.resolverEndpoints.collectAsState(initial = emptyMap())
    ModalBottomSheet(onDismissRequest = onClose, containerColor = V2.Card, contentColor = V2.Text) {
        ExternalDownloadForm(ar, choice, endpoints, onClose,
            resolve = { provider, endpoint, video, key ->
                val response = app.externalResolver.resolve(provider, endpoint, video, preferredHeight,
                    choice.title?.takeIf { choice.video == video }, key)
                // Only the URL of the server is saved, never its API key or the consent state.
                try { app.settings.setResolverEndpoint(provider, endpoint.toString()) } catch (_: IOException) { /* Optional preference. */ }
                response
            },
            check = { media -> app.downloads.probe(media, DownloadAuth.external, preferredHeight.takeIf { it > 0 }) },
            onDownload = onDownload)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExternalDownloadForm(
    ar: Boolean, choice: ExternalVideoChoice, endpoints: Map<ResolverType, String>, onClose: () -> Unit,
    resolve: suspend (ResolverType, HttpUrl, YouTubeVideo, String?) -> List<DetectedMedia>,
    check: suspend (DetectedMedia) -> DetectedMedia, onDownload: (DetectedMedia) -> Unit
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    var type by remember { mutableStateOf(choice.type) }
    var typedServer by remember(type) { mutableStateOf<String?>(null) }
    val serverText = typedServer ?: endpoints[type].orEmpty()
    var sourceText by remember { mutableStateOf(choice.video?.watchUrl.orEmpty()) }
    // Changing the address, even if a stored setting arrives asynchronously, clears a previous key.
    var keyText by remember(type, serverText) { mutableStateOf("") }
    val keyValid = keyText.isBlank() || keyText.safeHeader(256) != null
    val video = YouTubeVideo.parse(sourceText)
    val server = resolverEndpoint(serverText)
    var consent by remember(type, server, video) { mutableStateOf(false) }
    var failure by remember(type, server, video) { mutableStateOf<ResolverProblem?>(null) }
    var results by remember(type, server, video) { mutableStateOf<List<DetectedMedia>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf<String?>(null) }
    val busy = loading || checking != null

    LaunchedEffect(results.isNotEmpty()) { if (results.isNotEmpty()) list.animateScrollToItem(4) }

    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.90f).testTag("browser.external"), state = list,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ar) "تحميل عبر خادم خارجي" else "Download via a server", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, if (ar) "إغلاق" else "Close") }
            }
            Text(if (ar) "اختار خادم موثوق عندك إذن تستخدمه. هيستقبل رابط المشاهدة فقط، مش رابط الوسائط الموقّع ولا كوكيز المتصفح."
                else "Choose a trusted server you have permission to use. It receives the watch link or ID—not the signed media URL or browser cookies.", color = V2.TextDim)
        }
        item {
            OutlinedTextField(sourceText, { sourceText = it }, modifier = Modifier.fillMaxWidth().testTag("external.watch"), enabled = !busy,
                label = { Text(if (ar) "رابط مشاهدة YouTube" else "YouTube watch link") },
                singleLine = true, isError = sourceText.isNotBlank() && video == null,
                supportingText = {
                    Text(if (ar) "الصق رابط الفيديو نفسه، مش بحث Google أو رابط googlevideo." else "Use the video’s watch link, not a Google search or googlevideo URL.")
                }, textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ResolverType.entries.forEach { provider ->
                    FilterChip(selected = type == provider, enabled = !busy, onClick = { type = provider }, label = { Text(provider.label) })
                }
            }
            val hint = when (type) {
                ResolverType.COBALT -> if (ar) "استخدم API لخادم خاص أو بإذن مالكه. الخادم ممكن يجهّز ملف كامل." else "Use your own API server or an owner-authorized instance. It can prepare a complete file."
                ResolverType.PIPED -> if (ar) "بيقرأ الملفات الكاملة المتاحة وقوائم HLS في API." else "Reads the complete files and HLS playlists exposed by its API."
                ResolverType.INVIDIOUS -> if (ar) "بيطلب صيغ الفيديو الكاملة من الخادم." else "Requests the complete video formats offered by the server."
            }
            Text(hint, color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uriHandler.openUri(type.docs) } }) {
                Text(if (ar) "دليل إعداد ${type.label}" else "${type.label} setup guide")
            }
            OutlinedTextField(serverText, { typedServer = it }, modifier = Modifier.fillMaxWidth().testTag("external.server"), enabled = !busy,
                label = { Text(if (ar) "عنوان API للخادم (HTTPS)" else "Server API address (HTTPS)") },
                isError = serverText.isNotBlank() && server == null, singleLine = true,
                placeholder = { Text("https://media.example.net") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (type == ResolverType.COBALT) {
                OutlinedTextField(keyText, { keyText = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("external.key"),
                    enabled = !busy && server != null, isError = !keyValid,
                    label = { Text(if (ar) "مفتاح API (اختياري، مش بيتحفظ)" else "API key (optional, not saved)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy && server != null && video != null,
                    modifier = Modifier.testTag("external.consent"))
                Text(if (ar) "عندي إذن أحفظ الفيديو وأشارك رابط المشاهدة مع الخادم ده." else "I’m allowed to save this video and share its watch link with this server.")
            }
            Button(onClick = {
                if (server != null && video != null && consent && keyValid && !busy) {
                    val provider = type
                    val key = keyText.safeHeader(256).takeIf { provider == ResolverType.COBALT }
                    focus.clearFocus()
                    loading = true
                    failure = null
                    results = emptyList()
                    scope.launch {
                        try { results = resolve(provider, server, video, key) }
                        catch (e: ResolverFailure) { failure = e.problem }
                        catch (_: IOException) { failure = ResolverProblem.NETWORK }
                        finally { loading = false }
                    }
                }
            }, enabled = video != null && server != null && consent && keyValid && !busy,
                modifier = Modifier.fillMaxWidth().testTag("external.resolve")) {
                Text(if (ar) "جلب المصادر من الخادم" else "Get sources from server")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 5.dp))
            failure?.let { Text(it.text(ar), color = V2.Gold,
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
        }
        if (results.isNotEmpty()) item {
            Text(if (ar) "المصادر اللي رجّعها الخادم" else "Sources returned by the server", style = MaterialTheme.typography.titleMedium)
            Text(if (ar) "ردّ الخادم مش ضمان للتحميل. اختار رابط عشان التطبيق يتحقق من ردّ الوسائط." else "A server response isn’t a download guarantee. Choose a link so Video2 can validate the media response.", color = V2.TextDim)
        }
        items(results, key = { it.url }) { result ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = V2.CardSoft)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    val format = result.kind.titleEn.takeUnless { result.kind == MediaKind.OTHER }
                    val quality = result.qualityLabel ?: result.height?.let { "${it}p" }
                    Text(listOfNotNull(quality, format, httpMediaUrl(result.url)?.host).joinToString(" · "),
                        color = V2.TextDim, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                    val problem = result.probeProblem
                    val message = when {
                        result.drm.isProtected -> if (ar) "المصدر محمي ومش قابل للحفظ." else "This source is protected and cannot be saved."
                        result.kind == MediaKind.DASH -> if (ar) "الصيغة دي للتشغيل فقط." else "This format is for playback only."
                        problem != null -> problem.text(ar) + result.probeHttpStatus?.let { " (HTTP $it)" }.orEmpty()
                        else -> null
                    }
                    message?.let { Text(it, color = V2.Gold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (checking == result.url) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (result.canDownload) Button(enabled = consent && !busy, onClick = {
                        // Opening a Cobalt tunnel can start remuxing. Validate the actual download GET once.
                        if (type == ResolverType.COBALT) onDownload(result)
                        else {
                            checking = result.url
                            scope.launch {
                                try {
                                    val checked = check(result)
                                    if (checked.canDownload && checked.kind.isCompleteVideo && checked.downloadChecked()) onDownload(checked)
                                    else results = results.map { if (it.url == result.url) checked else it }
                                } finally { checking = null }
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ar) "تحقّق ونزّل" else "Check and download")
                    }
                }
            }
        }
    }
}
