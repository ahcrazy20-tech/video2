package com.ahcrazy.video2.ui.browser

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.media.YouTubeVideo
import com.ahcrazy.video2.core.media.httpMediaUrl
import com.ahcrazy.video2.core.media.origin
import com.ahcrazy.video2.core.media.resolverEndpoint
import com.ahcrazy.video2.core.model.DetectedMedia
import com.ahcrazy.video2.core.model.MediaKind
import com.ahcrazy.video2.data.ResolverFailure
import com.ahcrazy.video2.data.ResolverProblem
import com.ahcrazy.video2.data.ResolverType
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

internal data class ExternalVideoChoice(val video: YouTubeVideo?, val title: String? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ExternalDownloadSheet(ar: Boolean, choice: ExternalVideoChoice, preferredHeight: Int,
                                  onClose: () -> Unit, onDownload: (DetectedMedia) -> Unit) {
    val app = (LocalContext.current as Activity).app()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val endpoints by app.settings.resolverEndpoints.collectAsState(initial = emptyMap())
    var type by remember { mutableStateOf(ResolverType.COBALT) }
    val stored = endpoints[type].orEmpty()
    var serverText by remember(type, stored) { mutableStateOf(stored) }
    var sourceText by remember { mutableStateOf(choice.video?.watchUrl.orEmpty()) }
    // Optional provider API key is only used in this sheet; never stored or attached to media requests.
    var keyText by remember(type) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val video = YouTubeVideo.parse(sourceText)
    val server = resolverEndpoint(serverText)
    var consent by remember(type, server, video) { mutableStateOf(false) }
    var failure by remember(type, server, video) { mutableStateOf<ResolverProblem?>(null) }
    var results by remember(type, server, video) { mutableStateOf<List<DetectedMedia>>(emptyList()) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = V2.Card, contentColor = V2.Text) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.90f).testTag("browser.external"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ar) "تحميل عبر خادم خارجي" else "Download via a server", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, if (ar) "إغلاق" else "Close") }
                }
                Text(if (ar) "اختار خادم موثوق عندك إذن تستخدمه. الخادم هيستقبل رابط المشاهدة، مش رابط الوسائط الموقّع ولا كوكيز المتصفح."
                    else "Choose a trusted server you have permission to use. It receives only the watch link—not the signed media URL or browser cookies.", color = V2.TextDim)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ResolverType.entries.forEach { provider ->
                        FilterChip(selected = type == provider, enabled = !loading, onClick = { type = provider }, label = { Text(provider.label) })
                    }
                }
                val hint = when (type) {
                    ResolverType.COBALT -> if (ar) "API لخادم خاص أو بإذن مالكه. الملف الكامل ممكن يجهزه الخادم." else "Use your own API server or an owner-authorized instance. It can return a complete file."
                    ResolverType.PIPED -> if (ar) "بيقرأ الملفات الكاملة المتاحة وقوائم HLS المنشورة في API." else "Reads complete files and HLS playlists exposed by its API."
                    ResolverType.INVIDIOUS -> if (ar) "بيستخدم صيغ الفيديو الكاملة اللي الخادم بيوفرها." else "Uses the complete video formats the server offers."
                }
                Text(hint, color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { runCatching { uriHandler.openUri(type.docs) } }) {
                    Text(if (ar) "دليل إعداد ${type.label}" else "${type.label} setup guide")
                }
            }
            item {
                OutlinedTextField(serverText, { serverText = it; keyText = "" }, modifier = Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(if (ar) "عنوان API للخادم (HTTPS)" else "Server API address (HTTPS)") },
                    isError = serverText.isNotBlank() && server == null, singleLine = true,
                    placeholder = { Text("https://media.example.net") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            if (type == ResolverType.COBALT) item {
                OutlinedTextField(keyText, { keyText = it }, modifier = Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(if (ar) "مفتاح الخادم لو مطلوب (مش بيتحفظ)" else "Server API key, if needed (not saved)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
            item {
                OutlinedTextField(sourceText, { sourceText = it }, modifier = Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(if (ar) "رابط مشاهدة YouTube" else "YouTube watch link") },
                    singleLine = true, isError = sourceText.isNotBlank() && video == null,
                    supportingText = {
                        Text(if (ar) "الصق صفحة الفيديو نفسها، مش بحث Google أو رابط googlevideo." else "Use the video’s watch link, not a Google search or googlevideo URL.")
                    }, textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                server?.let { endpoint ->
                    Text((if (ar) "الخادم المختار: " else "Send to: ") + endpoint.origin(), color = V2.TextDim,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !loading)
                    Text(if (ar) "عندي إذن أحفظ الفيديو وأشارك رابط المشاهدة مع الخادم ده." else "I’m allowed to save this video and share its watch link with this server.")
                }
                Button(onClick = {
                    val selected = type
                    if (server != null && video != null) {
                        loading = true
                        failure = null
                        results = emptyList()
                        scope.launch {
                            try {
                                val response = app.externalResolver.resolve(selected, server, video, preferredHeight,
                                    choice.title?.takeIf { choice.video == video }, keyText.takeIf { selected == ResolverType.COBALT })
                                // Config contains just the server endpoint. Never persist the API key.
                                try { app.settings.setResolverEndpoint(selected, server.toString()) } catch (_: IOException) { /* Continue with this response. */ }
                                results = response
                            } catch (e: CancellationException) { throw e }
                            catch (e: ResolverFailure) { failure = e.problem }
                            catch (_: IOException) { failure = ResolverProblem.NETWORK }
                            finally { loading = false }
                        }
                    }
                }, enabled = video != null && server != null && consent && !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ar) "جلب المصادر من الخادم" else "Get sources from server")
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 5.dp))
                failure?.let { Text(it.text(ar), color = V2.Gold, modifier = Modifier.padding(top = 6.dp)) }
            }
            if (results.isNotEmpty()) item {
                Text(if (ar) "اختار مصدر من ردّ الخادم" else "Choose a source returned by the server", style = MaterialTheme.typography.titleMedium)
                Text(if (ar) "التطبيق بيتأكد من الملف لما يبدأ التنزيل." else "Video2 validates the file when the download starts.", color = V2.TextDim)
            }
            items(results, key = { it.url }) { result ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = V2.CardSoft)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(result.title, maxLines = 2)
                            val format = if (result.kind != MediaKind.OTHER) result.kind.titleEn else ""
                            Text(listOfNotNull(result.qualityLabel, format.takeIf { it.isNotEmpty() }, httpMediaUrl(result.url)?.host).joinToString(" · "),
                                color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onDownload(result) }, enabled = consent && !loading) {
                            Icon(Icons.Filled.Download, if (ar) "تنزيل المصدر" else "Download source", tint = V2.Mint)
                        }
                    }
                }
            }
        }
    }
}
