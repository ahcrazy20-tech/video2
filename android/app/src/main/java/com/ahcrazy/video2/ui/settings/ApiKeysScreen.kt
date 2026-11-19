package com.ahcrazy.video2.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.data.AppSettings
import com.ahcrazy.video2.data.dubbing.GeminiDubModel
import com.ahcrazy.video2.ui.dubbing.dubMessage
import com.ahcrazy.video2.ui.theme.V2
import com.ahcrazy.video2.ui.theme.glassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class KeyProvider(val id: String, val name: String, val link: String)
private val providers = AppSettings.apiKeyRows.map { (id, title, link) -> KeyProvider(id, title.removePrefix("مفتاح "), link) }
internal class GeminiKeyChecks(val models: suspend () -> List<GeminiDubModel>, val speech: suspend () -> Unit)
private data class KeyTestNotice(val message: String, val ok: Boolean = false)

private fun keyTitle(ar: Boolean) = if (ar) "مفاتيح الـAPI" else "API keys"

@Composable
internal fun ApiKeysEntry(ar: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp).glassSurface(RoundedCornerShape(19.dp))
        .testTag("settings.apiKeys.open").clickable(role = Role.Button, onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.VpnKey, null, tint = V2.Accent)
        Column(Modifier.weight(1f)) {
            Text(keyTitle(ar), style = MaterialTheme.typography.titleMedium)
            Text(if (ar) "إدارة مفاتيح ${providers.size} مزوّد" else "Manage keys for ${providers.size} providers",
                color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Icon(if (ar) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight, null, tint = V2.TextDim)
    }
}

/** Lists only provider names and whether a key is stored; a key is never read into a text field. */
@Composable
internal fun ApiKeysScreen(
    ar: Boolean, hasKey: (String) -> Boolean, saveKey: (String, String) -> Unit, deleteKey: (String) -> Unit,
    initialProvider: String? = null, geminiChecks: GeminiKeyChecks? = null, onBack: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var provider by remember(initialProvider) { mutableStateOf(providers.firstOrNull { it.id == initialProvider }) }
    var saved by remember { mutableStateOf<Set<String>?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var writeFailed by remember { mutableStateOf(false) }

    LaunchedEffect(retry) {
        readFailed = false
        try {
            saved = withContext(Dispatchers.IO) { providers.mapNotNull { it.id.takeIf(hasKey) }.toSet() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { readFailed = true }
    }

    fun back() {
        if (busy) return
        focus.clearFocus()
        if (provider == null) onBack() else { provider = null; status = null }
    }
    BackHandler { back() }

    val active = provider
    val stored = saved
    val term = query.trim()
    LaunchedEffect(term) { scroll.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp).testTag("settings.apiKeys.screen")) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back, enabled = !busy, modifier = Modifier.testTag("settings.apiKeys.back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, if (ar) "رجوع" else "Back")
            }
            Text(active?.name ?: keyTitle(ar), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        when {
            readFailed -> {
                Text(if (ar) "ما قدرناش نفتح تخزين المفاتيح المشفّر. ما اتغيّرش أي مفتاح." else
                    "Couldn't open encrypted key storage. No key was changed.", color = V2.Danger)
                TextButton(onClick = { retry++ }) { Text(if (ar) "جرّب تاني" else "Retry") }
            }
            stored == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp).size(28.dp))
            active != null -> key(active.id) {
                KeyEditor(ar, active, active.id in stored, busy, status, writeFailed,
                    checks = geminiChecks.takeIf { active.id == "gemini" }, onChange = { newKey ->
                    if (!busy) {
                        busy = true
                        status = null
                        writeFailed = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (newKey == null) deleteKey(active.id) else saveKey(active.id, newKey)
                                }
                                saved = if (newKey == null) stored - active.id else stored + active.id
                                status = if (newKey == null) {
                                    if (ar) "المفتاح اتمسح من الجهاز" else "Key removed from this device"
                                } else if (ar) "المفتاح اتحفظ على الجهاز" else "Key saved on this device"
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                writeFailed = true
                                status = if (ar) "ما قدرناش نحفظ التغيير" else "Couldn't save the change"
                            } finally { busy = false }
                        }
                    }
                })
            }
            else -> {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("settings.apiKeys.search"),
                    label = { Text(if (ar) "ابحث باسم المزود" else "Find a provider") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                val matches = remember(term) { providers.filter { it.name.contains(term, true) || it.id.contains(term, true) } }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, contentPadding = PaddingValues(vertical = 10.dp)) {
                    if (term.isEmpty()) item("privacy") {
                        Text(if (ar) "المفاتيح محفوظة مشفّرة على الجهاز. «محفوظ» مش معناه إن المفتاح اتفحص." else
                            "Keys are encrypted on this device. “Saved” doesn't mean a key was validated.",
                            Modifier.padding(bottom = 10.dp), color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    if (matches.isEmpty()) item("no-match") {
                        Text(if (ar) "مفيش مزود بالاسم ده" else "No matching provider", Modifier.padding(vertical = 14.dp), color = V2.TextDim)
                    }
                    items(matches, key = { it.id }) { selected ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("settings.apiKeys.provider.${selected.id}")
                            .clickable(role = Role.Button) { focus.clearFocus(); provider = selected; status = null }
                            .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selected.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (selected.id in stored) Text(if (ar) "محفوظ" else "Saved", color = V2.Mint, style = MaterialTheme.typography.labelMedium)
                            Icon(if (ar) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight, null, tint = V2.TextDim)
                        }
                        HorizontalDivider(color = V2.Divider)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyEditor(ar: Boolean, provider: KeyProvider, saved: Boolean, busy: Boolean, status: String?, writeFailed: Boolean,
                      checks: GeminiKeyChecks?, onChange: (String?) -> Unit) {
    val focus = LocalFocusManager.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // Keep drafts in memory only, and never prefill with the key from encrypted storage.
    var draft by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var testNotice by remember { mutableStateOf<KeyTestNotice?>(null) }
    var confirmSpeech by remember { mutableStateOf(false) }
    val locked = busy || checking
    fun save() {
        if (draft.isBlank() || locked) return
        val key = draft.trim()
        draft = ""
        testNotice = null
        focus.clearFocus()
        onChange(key)
    }
    fun runCheck(speech: Boolean) {
        val client = checks ?: return
        if (!saved || busy || checking || draft.isNotBlank()) return
        focus.clearFocus()
        checking = true
        testNotice = null
        scope.launch {
            try {
                testNotice = if (speech) {
                    client.speech()
                    KeyTestNotice(if (ar) "اختبار الصوت نجح: Gemini ولّد العيّنة. ده مش ضمان لحصة الفيديو بالكامل."
                        else "Speech test passed. Gemini generated the sample; this doesn't guarantee quota for the whole video.", true)
                } else {
                    val missing = client.models()
                    if (missing.isEmpty()) KeyTestNotice(if (ar) "المفتاح صالح: Google قبلته وموديلات الدبلجة ظاهرة. حصة التوليد لسه ما اتفحصتش."
                        else "Key valid: Google accepted it and the dubbing models are visible. Generation quota hasn't been tested.", true)
                    else {
                        val names = missing.joinToString(", ") { "\u2066${it.id}\u2069" }
                        KeyTestNotice(if (ar) "موديلات مش متاحة للمشروع: $names" else "Models unavailable to this project: $names")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { testNotice = KeyTestNotice(e.dubMessage(ar)) }
            finally { checking = false }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (saved) (if (ar) "فيه مفتاح محفوظ. لو عايز تستبدله، الصق المفتاح الجديد." else "A key is saved. Paste a new one to replace it.")
            else if (ar) "الصق المفتاح بتاعك من صفحة المزود." else "Paste your key from the provider's site.",
            color = V2.TextDim, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("settings.apiKeys.input"),
            enabled = !locked, label = { Text(if (ar) "المفتاح الجديد" else "New key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }))
        Button(onClick = ::save, enabled = draft.isNotBlank() && !locked,
            modifier = Modifier.fillMaxWidth().testTag("settings.apiKeys.save")) { Text(if (ar) "حفظ المفتاح" else "Save key") }
        if (saved && checks != null) {
            Text(if (ar) "اختبار المفتاح المحفوظ بيسأل Google عن الموديلات، من غير توليد أو إرسال فيديو أو ترجمة."
                else "The saved-key test checks model access at Google without generating audio or sending media or subtitles.",
                color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { runCheck(false) }, enabled = !locked && draft.isBlank(),
                modifier = Modifier.fillMaxWidth().testTag("settings.apiKeys.testGemini")) {
                Text(if (ar) "اختبر المفتاح المحفوظ" else "Test saved key")
            }
            TextButton(onClick = { confirmSpeech = true }, enabled = !locked && draft.isBlank(),
                modifier = Modifier.fillMaxWidth().testTag("settings.apiKeys.testGeminiSpeech")) {
                Text(if (ar) "اختبر توليد الصوت (اختياري)" else "Test speech generation (optional)")
            }
            if (checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (ar) "بنتحقق مع Google…" else "Checking with Google…", style = MaterialTheme.typography.bodySmall)
            }
            testNotice?.let {
                Text(it.message, color = if (it.ok) V2.Mint else V2.Danger, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("settings.apiKeys.testResult").semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (saved) OutlinedButton(onClick = { focus.clearFocus(); confirmRemove = true }, enabled = !locked,
            modifier = Modifier.fillMaxWidth()) { Text(if (ar) "إزالة المفتاح المحفوظ" else "Remove saved key") }
        TextButton(onClick = { linkFailed = runCatching { uri.openUri(provider.link) }.isFailure }) {
            Text(if (ar) "احصل على المفتاح ↗" else "Get a key ↗")
        }
        if (linkFailed) Text(if (ar) "ما قدرناش نفتح الصفحة" else "Couldn't open the provider's page", color = V2.Danger)
        status?.let { Text(it, color = if (writeFailed) V2.Danger else V2.Mint, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmSpeech) AlertDialog(onDismissRequest = { confirmSpeech = false },
        title = { Text(if (ar) "تجربة توليد الصوت؟" else "Test speech generation?") },
        text = { Text(if (ar) "كادر هيبعت جملة اختبار ثابتة بس. ده طلب توليد واحد، ممكن يتحسب من الحصة أو يتفوْتر حسب مشروعك. مفيش فيديو أو ترجمة بتتبعت."
            else "Kadr sends one built-in test sentence, not your video or subtitles. This generation request may use quota or incur a charge on your project.") },
        confirmButton = { TextButton(onClick = { confirmSpeech = false; runCheck(true) }) { Text(if (ar) "اختبر الصوت" else "Test speech") } },
        dismissButton = { TextButton(onClick = { confirmSpeech = false }) { Text(if (ar) "إلغاء" else "Cancel") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(if (ar) "تمسح المفتاح؟" else "Remove key?") },
        text = { Text(if (ar) "هيتمسح من كادر بس، مش من حسابك عند المزود." else "This removes it from Kadr, not from your provider account.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; testNotice = null; onChange(null) }) { Text(if (ar) "إزالة" else "Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(if (ar) "إلغاء" else "Cancel") } })
}
