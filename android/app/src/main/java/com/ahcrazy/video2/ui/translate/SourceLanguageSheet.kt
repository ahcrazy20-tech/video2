package com.ahcrazy.video2.ui.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.core.model.SubLang
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.data.SavedSourcePreview
import com.ahcrazy.video2.data.TranslationManager
import com.ahcrazy.video2.data.offline.hymtSourceLanguages
import com.ahcrazy.video2.ui.components.PickerRow
import com.ahcrazy.video2.ui.theme.V2
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceLanguageSheet(job: TranslationJob, manager: TranslationManager, busy: Boolean, ar: Boolean, onDismiss: () -> Unit) {
    var sample by remember(job.id) { mutableStateOf<SavedSourcePreview?>(null) }
    var loading by remember(job.id) { mutableStateOf(true) }
    LaunchedEffect(job.id, manager) {
        sample = manager.savedSourcePreview(job)
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SourceLanguageForm(ar, sample, loading, busy) { source ->
            manager.translateWithCorrectedSource(job.id, source).also { if (it) onDismiss() }
        }
    }
}

@Composable
internal fun SourceLanguageForm(ar: Boolean, sample: SavedSourcePreview?, loading: Boolean, busy: Boolean,
                                onStart: (SubLang) -> Boolean) {
    var source by remember { mutableStateOf(SubLang.AUTO) }
    var verified by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ar) "تصحيح لغة المصدر" else "Correct source language", style = MaterialTheme.typography.titleLarge)
        when {
            loading -> Text(if (ar) "بنعرض عيّنة من التفريغ…" else "Loading a sample from the transcript…")
            sample == null -> Text(if (ar) "التفريغ مش متاح. ابدأ مهمة جديدة وحدد لغة الكلام يدويًا." else
                "The transcript is unavailable. Start a new job with the correct source language.", color = V2.Danger)
            else -> {
                val detected = sample.detectedCode?.let { code ->
                    val locale = Locale.forLanguageTag(code)
                    val name = locale.getDisplayLanguage(if (ar) Locale.forLanguageTag("ar") else Locale.ENGLISH).ifBlank { code }
                    "$name (\u2066$code\u2069)"
                } ?: if (ar) "غير محددة" else "Unknown"
                Text(if (ar) "اللغة اللي اتسجلت: $detected" else "Recorded source: $detected", color = V2.Gold)
                Text(if (ar) "عيّنة من التفريغ المحفوظ" else "Sample from the saved transcript", style = MaterialTheme.typography.labelMedium)
                sample.sample.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = V2.TextDim) }
                Text(if (ar) "لو النص غلط، اعمل مهمة جديدة باللغة الصحيحة. الخيار ده بيستخدم النص المحفوظ زي ما هو في مهمة جديدة، من غير تفريغ تاني أو حذف المهمة القديمة."
                    else "If this transcript is incorrect, transcribe a new job with the right language. This option reuses the original words and timings in a separate job.",
                    style = MaterialTheme.typography.bodySmall)
                PickerRow(if (ar) "لغة الكلام الصحيحة" else "Correct language", source,
                    SubLang.entries.filter { it.code in hymtSourceLanguages }, { lang ->
                        if (lang == SubLang.AUTO) { if (ar) "اختار اللغة" else "Choose language" } else if (ar) lang.nameAr else lang.englishName
                    }) { source = it; verified = false; failed = false }
                Row(Modifier.fillMaxWidth().toggleable(verified, enabled = source != SubLang.AUTO && !busy, role = Role.Checkbox) {
                    verified = it; failed = false
                }.testTag("translation.source.confirm"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(verified, onCheckedChange = null, modifier = Modifier.padding(12.dp), enabled = source != SubLang.AUTO && !busy)
                    Text(if (ar) "التفريغ صحيح ومكتوب باللغة دي" else "This transcript is correct and in this language",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { failed = !onStart(source) }, enabled = !busy && verified && source != SubLang.AUTO,
                    modifier = Modifier.fillMaxWidth().testTag("translation.source.retry")) {
                    Text(if (ar) "ترجم من التفريغ المحفوظ" else "Translate the saved transcript")
                }
                if (failed) Text(if (ar) "المهمة ما بدأتش. راجع إن موديل الترجمة الأصلي لسه مثبت وإن مفيش مهمة محلية شغّالة."
                    else "Couldn't start. Check that the original translator is installed and no other local task is running.", color = V2.Danger)
            }
        }
    }
}
