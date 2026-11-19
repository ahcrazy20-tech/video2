package com.ahcrazy.video2.ui.dubbing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.core.dubbing.DubProvider
import com.ahcrazy.video2.core.dubbing.DubVoice
import com.ahcrazy.video2.core.dubbing.DubVoices
import com.ahcrazy.video2.core.dubbing.azureDubVoices
import com.ahcrazy.video2.data.dubbing.DubSource
import com.ahcrazy.video2.ui.theme.V2
import java.util.Locale

internal enum class DubPreview { IDLE, PREPARING, PLAYING }
internal data class DubProgress(val done: Int, val total: Int)
internal data class DubNotice(val text: String, val failed: Boolean = false)
internal data class DubbingPanelState(
    val source: DubSource?, val catalog: DubVoices?, val loading: Boolean = false, val loadErrors: List<String> = emptyList(),
    val preview: DubPreview = DubPreview.IDLE, val progress: DubProgress? = null, val notice: DubNotice? = null,
    val azureConfigured: Boolean = false, val androidError: String? = null,
)

private enum class Picker { PROVIDER, LANGUAGE, DIALECT, VOICE }
private data class Choice(val id: String, val label: String, val detail: String? = null)
private const val AUTO_VOICE = ""

/** Presentation only: preview and save get the same concrete voice, not a request to re-run Auto. */
@Composable
internal fun DubbingPanel(
    title: String, ar: Boolean, state: DubbingPanelState,
    onPreview: (DubVoice) -> Unit, onStopPreview: () -> Unit, onSave: (DubVoice) -> Unit, onCancelSave: () -> Unit,
    onChoiceChanged: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, onInstall: () -> Unit,
    onAzureSettings: (() -> Unit)? = null,
) {
    var provider by remember { mutableStateOf(DubProvider.ANDROID) }
    val azure = provider == DubProvider.AZURE
    val catalog = if (azure) azureDubVoices else state.catalog
    val source = state.source
    val preferred = remember(source?.language) {
        Locale.forLanguageTag(source?.language.orEmpty().replace('_', '-')).takeUnless { it.language == "auto" } ?: Locale.ROOT
    }
    var languageOverride by remember { mutableStateOf<String?>(null) }
    var dialectOverride by remember { mutableStateOf<String?>(null) }
    var voiceOverride by remember { mutableStateOf<String?>(null) }
    var allowNetwork by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    val language = languageOverride ?: preferred.language
    val defaultDialect = remember(catalog, language, preferred) {
        if (language.isBlank()) "" else catalog?.initialDialect(if (language == preferred.language) preferred.toLanguageTag() else language).orEmpty()
    }
    val dialect = dialectOverride ?: defaultDialect
    val dialects = remember(catalog, language) { catalog?.dialects(language).orEmpty() }
    val voices = remember(catalog, dialect) { catalog?.forDialect(dialect).orEmpty() }
    val selected = voices.firstOrNull { it.id == voiceOverride }
    val resolved = remember(catalog, dialect, voiceOverride, allowNetwork) { catalog?.resolve(dialect, voiceOverride, allowNetwork) }
    val working = state.progress != null
    val ready = !state.loading && !working
    val enabled = ready && resolved != null && source != null && (!azure || state.azureConfigured)
    val providerTitle = if (ar) "نوع الصوت" else "Voice provider"
    val auto = if (ar) "اختيار تلقائي" else "Auto"
    val placeholder = if (ar) "اختار من المتاح" else "Choose from the engine"
    val languageTitle = if (ar) "لغة النص" else "Text language"
    val dialectTitle = if (ar) "اللهجة / المنطقة" else "Accent / region"
    val voiceTitle = if (ar) "الصوت" else "Voice"

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = V2.Accent)
            Column(Modifier.weight(1f)) {
                Text(if (ar) "دبلجة الفيديو" else "Dub video", style = MaterialTheme.typography.titleLarge)
                Text(title, style = MaterialTheme.typography.bodySmall, color = V2.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        source?.let {
            val label = if (it.translated) {
                if (ar) "الترجمة المحفوظة" else "Saved translation"
            } else {
                if (ar) "النص الأصلي المحفوظ" else "Saved original text"
            }
            Text(if (ar) "$label · ${it.plan.lines.size} سطر" else "$label · ${it.plan.lines.size} cues",
                style = MaterialTheme.typography.labelMedium, color = V2.Accent)
        }
        Text(if (ar) "اللهجة بتغيّر النطق، مش كلام الترجمة." else "An accent changes pronunciation, not the saved wording.",
            style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (ar) "بنشوف الأصوات اللي محرّك النطق على جهازك بيوفّرها…" else "Checking voices reported by your speech engine…",
                style = MaterialTheme.typography.bodySmall)
        }
        DubSelector("provider", providerTitle, providerLabel(provider, ar), !working) { picker = Picker.PROVIDER }
        if (azure) {
            Text(if (ar) "أصوات Microsoft Azure Speech مولّدة بنطق أقرب للطبيعي. مش استنساخ لصوت صاحب الفيديو." else
                "Microsoft Azure Speech generates more natural-sounding speech. It doesn't clone the original speaker.",
                style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
            if (!state.loading && !state.azureConfigured) {
                Text(if (ar) "أضف مفتاح Azure Speech من الإعدادات ← مفاتيح الـAPI، واحفظ منطقة مورد Speech في إعدادات مزوّدي الكلام." else
                    "Add an Azure Speech key under Settings → API keys, and set your Speech resource region under Speech providers.",
                    style = MaterialTheme.typography.bodySmall, color = V2.Gold)
                onAzureSettings?.let { open ->
                    OutlinedButton(onClick = open, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("dubbing.azure.settings")) {
                        Text(if (ar) "افتح إعدادات Azure" else "Open Azure settings")
                    }
                }
            }
        } else state.androidError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = V2.Danger) }
        state.loadErrors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = V2.Danger) }
        if (catalog != null && !state.loading) {
            DubSelector("language", languageTitle, language.takeIf { it.isNotBlank() }?.let { localeName(it, ar, false) } ?: placeholder,
                ready && catalog.languages.isNotEmpty()) { picker = Picker.LANGUAGE }
            DubSelector("dialect", dialectTitle, dialect.takeIf { it.isNotBlank() }?.let { localeName(it, ar, true) } ?: placeholder,
                ready && dialects.isNotEmpty()) { picker = Picker.DIALECT }
            DubSelector("voice", voiceTitle, selected?.let { voiceLabel(it, ar) } ?: voiceOverride?.let(::isolate) ?: auto,
                ready && voices.isNotEmpty()) { picker = Picker.VOICE }
            val availability = when {
                catalog.voices.isEmpty() -> if (ar) "المحرّك مش عارض أصوات. راجع إعدادات النطق ونزّل بيانات الصوت." else "The engine reports no voices. Check speech settings and download voice data."
                language.isBlank() -> if (ar) "اختار لغة النص الأول عشان تظهر اللهجات والأصوات." else "Choose the language of the saved text to see its accents and voices."
                dialect !in dialects -> if (ar) "اللغة أو اللهجة دي مش موجودة في قائمة المحرّك." else "The engine doesn't list this language or accent."
                voiceOverride != null && selected == null -> if (ar) "الصوت ده مش موجود دلوقتي. اختار صوت تاني أو ارجع للتلقائي." else "That voice is no longer listed. Choose another voice or Auto."
                selected?.needsDownload == true -> if (ar) "الصوت ده محتاج تنزيل بياناته الأول." else "This voice needs to be downloaded first."
                resolved == null && voices.any { !it.needsDownload && it.network } -> if (ar) "الصوت المتاح يحتاج إنترنت. اسمح بيه أو اختار صوت على الجهاز." else "The available voice needs internet. Allow it or choose an on-device voice."
                resolved == null -> if (ar) "مفيش صوت جاهز للهجة دي. نزّل صوت أو اختار لهجة متاحة." else "No voice for this accent is ready. Download one or choose an available accent."
                else -> "${voiceLabel(resolved, ar)} · ${voiceAvailability(resolved, ar)}"
            }
            Text(availability, style = MaterialTheme.typography.bodySmall, color = if (resolved != null) V2.Mint else V2.Gold,
                modifier = Modifier.testTag("dubbing.availability"))
            if (azure || voices.any { it.network && !it.needsDownload }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowNetwork, onCheckedChange = { allowNetwork = it; onChoiceChanged() },
                        enabled = ready && (!azure || state.azureConfigured), modifier = Modifier.testTag("dubbing.network"))
                    val consent = if (azure) {
                        if (ar) "أوافق على إرسال نص العيّنة أو السطور اللي هتتدبلج إلى Microsoft Azure لما أضغط معاينة أو ابدأ. مفيش رفع للفيديو أو صوته. الاستخدام بيتحسب على حساب Azure بتاعي."
                        else "Allow sending preview or dub subtitle text to Microsoft Azure when I tap Preview or Start. No video or original audio is uploaded. Usage counts against my Azure account."
                    } else if (ar) "اسمح بأصوات بتحتاج النت. المحرّك ممكن يرسل النص لخدمته."
                    else "Allow online voices. The engine may send the text to its service."
                    Text(consent, style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
                }
            }
        }
        state.notice?.let {
            Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.failed) V2.Danger else V2.Mint,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        state.progress?.let { p ->
            LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(if (ar) "جارٍ التوليد: ${p.done} / ${p.total}" else "Generating: ${p.done} / ${p.total}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancelSave, modifier = Modifier.fillMaxWidth().testTag("dubbing.cancel")) {
                Text(if (ar) "إيقاف التوليد" else "Cancel generation")
            }
        } ?: run {
            if (state.preview != DubPreview.IDLE) {
                if (state.preview == DubPreview.PREPARING) Text(if (ar) "جارٍ تجهيز عيّنة من النص المحفوظ…" else "Preparing a sample from the saved text…",
                    style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
                OutlinedButton(onClick = onStopPreview, modifier = Modifier.fillMaxWidth().testTag("dubbing.stopPreview")) {
                    Text(if (ar) "إيقاف المعاينة" else "Stop preview")
                }
            } else {
                OutlinedButton(onClick = { resolved?.let(onPreview) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("dubbing.preview")) {
                    Text(if (ar) "اسمع معاينة قصيرة" else "Listen to a short preview")
                }
            }
            Button(onClick = { resolved?.let(onSave) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("dubbing.save")) {
                Text(if (ar) "ابدأ الدبلجة" else "Start dubbing")
            }
        }
        if (!azure) {
            val engineName = catalog?.engineLabel ?: catalog?.preferredEngine
            Text(if (ar) "أصوات محرّك Android المفضّل" else "Android speech engine", style = MaterialTheme.typography.titleMedium)
            engineName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = V2.Accent) }
            Text(if (ar) "لو لهجة مش ظاهرة، ابحث في إعدادات الموبايل عن «تحويل النص إلى كلام»، وراجع بيانات الأصوات في المحرّك المفضّل. بعد التنزيل ارجع وحدّث القائمة."
                else "If an accent isn't listed, search phone settings for ‘Text-to-speech output’ and check the preferred engine's voice data. Refresh after downloading.",
                style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
            OutlinedButton(enabled = !working, onClick = onSettings, modifier = Modifier.fillMaxWidth().testTag("dubbing.settings")) {
                Text(if (ar) "إعدادات محرّك النطق" else "Speech engine settings")
            }
            if (catalog?.preferredEngine != null) {
                TextButton(enabled = !working, onClick = onInstall, modifier = Modifier.fillMaxWidth().testTag("dubbing.install")) {
                    Text(if (ar) "تنزيل بيانات الأصوات" else "Download voice data")
                }
            }
        }
        TextButton(enabled = ready, onClick = {
            allowNetwork = false
            onRefresh()
        }, modifier = Modifier.fillMaxWidth().testTag("dubbing.refresh")) {
            Text(if (azure) (if (ar) "حدّث النص والإعدادات" else "Refresh text and settings")
                else (if (ar) "حدّث قائمة الأصوات" else "Refresh voices"))
        }
    }
    when (picker) {
        Picker.PROVIDER -> DubChoiceDialog(ar, providerTitle, "provider", DubProvider.entries.map { item ->
            Choice(item.id, providerLabel(item, ar), if (item == DubProvider.AZURE) {
                if (ar) "سلمى وشاكر بالمصري، وأصوات تانية. يحتاج نت ومفتاح Speech." else "Egyptian Arabic and other voices. Needs internet and a Speech key."
            } else if (ar) "نفس الأصوات المثبّتة أو المتاحة في محرّك جهازك." else "Voices listed by your Android speech engine.")
        }, provider.id, { picker = null }) { id ->
            val next = DubProvider.entries.first { it.id == id }
            if (provider != next) {
                provider = next; dialectOverride = null; voiceOverride = null; allowNetwork = false
                onChoiceChanged()
            }
            picker = null
        }
        Picker.LANGUAGE -> DubChoiceDialog(ar, languageTitle, "language",
            catalog?.languages.orEmpty().map { Choice(it, localeName(it, ar, false)) }, language, { picker = null }) {
            languageOverride = it; dialectOverride = null; voiceOverride = null; allowNetwork = false
            picker = null; onChoiceChanged()
        }
        Picker.DIALECT -> DubChoiceDialog(ar, dialectTitle, "dialect",
            dialects.map { tag ->
                val options = catalog?.forDialect(tag).orEmpty()
                val detail = if (azure) options.joinToString(" · ") { voiceLabel(it, ar) } else {
                    val readyCount = options.count { !it.network && !it.needsDownload }
                    if (ar) "$readyCount صوت جاهز على الجهاز" else "$readyCount ready on-device voices"
                }
                Choice(tag, localeName(tag, ar, true), detail)
            }, dialect, { picker = null }) {
            dialectOverride = it; voiceOverride = null; allowNetwork = false
            picker = null; onChoiceChanged()
        }
        Picker.VOICE -> DubChoiceDialog(ar, voiceTitle, "voice",
            listOf(Choice(AUTO_VOICE, auto, if (azure) {
                if (ar) "بيختار صوت من Azure لنفس اللهجة." else "Chooses an Azure voice for this accent."
            } else if (ar) "بيفضّل صوت جاهز على الجهاز لنفس اللهجة." else "Prefers a ready on-device voice for this accent."))
                + voices.map { Choice(it.id, voiceLabel(it, ar), voiceAvailability(it, ar)) }, voiceOverride ?: AUTO_VOICE,
            { picker = null }) {
            voiceOverride = it.takeIf(String::isNotEmpty)
            picker = null; onChoiceChanged()
        }
        null -> Unit
    }
}

@Composable
private fun DubSelector(id: String, title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(V2.CardSoft)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag("dubbing.$id").padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = V2.TextDim)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = if (enabled) V2.Accent else V2.TextDim)
    }
}

@Composable
private fun DubChoiceDialog(ar: Boolean, title: String, type: String, choices: List<Choice>, current: String,
                            onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, confirmButton = {
        TextButton(onClick = onDismiss) { Text(if (ar) "رجوع" else "Back") }
    }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 304.dp).selectableGroup()) {
            items(choices, key = { it.id }) { choice ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .selectable(choice.id == current, role = Role.RadioButton) { onSelect(choice.id) }
                    .testTag("dubbing.$type.${choice.id.ifEmpty { "auto" }}").heightIn(min = 48.dp).padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice.id == current, onClick = null, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(choice.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        choice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = V2.TextDim) }
                    }
                }
            }
        }
    })
}

private fun isolate(value: String) = "\u2066$value\u2069"

private fun providerLabel(provider: DubProvider, ar: Boolean) = when (provider) {
    DubProvider.ANDROID -> if (ar) "أصوات Android" else "Android voices"
    DubProvider.AZURE -> if (ar) "صوت طبيعي · Azure Neural" else "Natural voice · Azure Neural"
}

private fun voiceLabel(voice: DubVoice, ar: Boolean) = (if (ar) voice.labelAr else voice.label) ?: isolate(voice.id)

private fun localeName(tag: String, ar: Boolean, region: Boolean): String {
    val locale = Locale.forLanguageTag(tag)
    val uiLocale = if (ar) Locale.forLanguageTag("ar") else Locale.ENGLISH
    val name = locale.getDisplayName(uiLocale).ifBlank { tag }
    if (!region) return name
    val generic = if (tag == locale.language) (if (ar) " (عام)" else " (generic)") else ""
    return "$name$generic · ${isolate(tag)}"
}

private fun voiceAvailability(voice: DubVoice, ar: Boolean): String = when {
    voice.needsDownload -> if (ar) "محتاج تنزيل" else "Download needed"
    voice.network -> if (ar) "يحتاج إنترنت" else "Needs internet"
    else -> if (ar) "جاهز على الجهاز" else "Ready on device"
}
