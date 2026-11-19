package com.ahcrazy.video2.ui.dubbing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahcrazy.video2.core.dubbing.DubPlan
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_CUES
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_SECONDS
import com.ahcrazy.video2.core.dubbing.GEMINI_MAX_SPEAKERS
import com.ahcrazy.video2.core.dubbing.GeminiDirection
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.geminiPresets
import com.ahcrazy.video2.data.dubbing.GeminiStage
import com.ahcrazy.video2.data.dubbing.GeminiStep
import com.ahcrazy.video2.ui.theme.V2

internal data class GeminiPanelState(val configured: Boolean = false, val inputError: String? = null,
                                    val direction: GeminiDirection? = null, val stage: GeminiStage? = null)

@Composable
internal fun GeminiDubbingControls(ar: Boolean, state: GeminiPanelState, plan: DubPlan?, loading: Boolean, busy: Boolean,
                                    options: GeminiOptions, onOptions: (GeminiOptions) -> Unit,
                                    onDirection: (GeminiDirection) -> Unit, onSettings: (() -> Unit)?) {
    var review by remember { mutableStateOf(false) }
    Text(if (ar) "كادر بيختار صوت ثابت لكل متحدث وبيحاكي طريقة كلامه وضحكه أو تنهيدته لما تكون واضحة. دي أصوات جاهزة، مش استنساخ."
        else "Kadr casts a consistent stock voice per speaker and follows audible delivery and reactions. It doesn't clone voices.",
        style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
    Text(if (ar) "تجريبي: ملف محلي لحد ${GEMINI_MAX_SECONDS / 60} دقايق و$GEMINI_MAX_CUES سطر. لحد $GEMINI_MAX_SPEAKERS متحدثين؛ التداخل وتشابه الأصوات ممكن يلخبطوا الاختيار."
        else "Experimental: local files up to ${GEMINI_MAX_SECONDS / 60} minutes and $GEMINI_MAX_CUES cues. Up to $GEMINI_MAX_SPEAKERS speakers; similar or overlapping voices may be mixed up.",
        style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
    if (!loading && !state.configured) {
        Text(if (ar) "حط مفتاح Gemini من Google AI Studio في الإعدادات ← مفاتيح الـAPI ← Gemini. اشتراك Gemini Pro لوحده مش مفتاح."
            else "Add your Google AI Studio key in Settings → API keys → Gemini. A Gemini Pro subscription alone isn't an API key.",
            style = MaterialTheme.typography.bodySmall, color = V2.Gold)
        if (onSettings != null) OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().testTag("dubbing.gemini.settings")) {
            Text(if (ar) "افتح الإعدادات" else "Open Settings")
        }
    }
    state.inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = V2.Danger) }
    if (state.configured && state.inputError == null && plan != null) {
        val enabled = !loading && !busy
        GeminiToggle("accent", if (ar) "نطق مصري للنص العربي" else "Egyptian pronunciation for Arabic text",
            options.egyptianArabic, enabled) { onOptions(options.copy(egyptianArabic = it)) }
        GeminiToggle("reactions", if (ar) "ردود الفعل المسموعة (ضحك، تنهيدة…)" else "Audible vocal reactions (laughs, sighs…)",
            options.reactions, enabled) { onOptions(options.copy(reactions = it)) }
        Text(if (ar) "حتى المعاينة بتحتاج تحليل صوت المقطع كله؛ التوليد نفسه لعيّنة قصيرة. الاستخدام حسب حصة وفوترة مشروع المفتاح. في الحصة المجانية، جوجل ممكن تستخدم المدخلات لتحسين خدماتها."
            else "Even a preview analyzes the clip's full audio, then synthesizes a short sample. Usage follows the key's project quota and billing. Google may use free-tier inputs to improve its services.",
            style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
        GeminiToggle("audio", if (ar) "أسمح بإرسال الصوت الأصلي إلى Gemini للتحليل (من غير صورة الفيديو)."
            else "Allow Gemini to analyze the original audio (no video frames).", options.allowAudio, enabled) {
            onOptions(options.copy(allowAudio = it))
        }
        GeminiToggle("text", if (ar) "أسمح بإرسال نص العيّنة أو الترجمة إلى Gemini لتوليد الأصوات."
            else "Allow sending preview or subtitle text to Gemini to generate speech.", options.allowText, enabled) {
            onOptions(options.copy(allowText = it))
        }
        state.direction?.let { direction ->
            val roles = direction.cast.size
            Text(if (ar) "اتوزّع الصوت تلقائيًا على $roles متحدث. الاختيار مبني على نبرة الصوت، مش تحديد هوية الشخص."
                else "Automatically cast $roles speakers by vocal sound, not personal identity.",
                style = MaterialTheme.typography.bodySmall, color = V2.Mint)
            if (direction.uncertainCount > 0) Text(if (ar) "${direction.uncertainCount} سطر تحديد متحدثه مش واضح. تقدر تراجعه."
                else "${direction.uncertainCount} cues have uncertain speaker assignments. You can review them.",
                style = MaterialTheme.typography.bodySmall, color = V2.Gold)
            OutlinedButton(onClick = { review = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("dubbing.gemini.review")) {
                Text(if (ar) "راجع توزيع الأصوات" else "Review automatic casting")
            }
            if (review) GeminiCastDialog(ar, plan, direction, onDirection) { review = false }
        }
    }
}

@Composable
private fun GeminiToggle(id: String, title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChange).testTag("dubbing.gemini.$id"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (enabled) V2.Text else V2.TextDim)
    }
}

internal fun GeminiStage.label(ar: Boolean) = when (step) {
    GeminiStep.EXTRACT_AUDIO -> if (ar) "بنجهّز الصوت الأصلي على الجهاز…" else "Preparing original audio on your device…"
    GeminiStep.ASSIGN_SPEAKERS -> if (ar) "Gemini بيفرّق بين المتحدثين…" else "Gemini is identifying speaker turns…"
    GeminiStep.DIRECT_SCENES -> if (ar) "تحليل النبرة وردود الفعل: $scene / $total" else "Analyzing tone and reactions: $scene / $total"
}

private sealed interface GeminiEdit {
    data object Cast : GeminiEdit
    data object Lines : GeminiEdit
    data class Voice(val speaker: Int) : GeminiEdit
    data class Line(val cue: Int) : GeminiEdit
}

private fun speakerLabel(id: Int, ar: Boolean) = if (ar) "متحدث $id" else "Speaker $id"
private fun registerLabel(register: GeminiRegister, ar: Boolean) = when (register) {
    GeminiRegister.MASCULINE -> if (ar) "صوت رجالي" else "Masculine voice"
    GeminiRegister.FEMININE -> if (ar) "صوت نسائي" else "Feminine voice"
    GeminiRegister.UNKNOWN -> if (ar) "مش واضح · اختيار احتياطي" else "Unclear · fallback voice"
}

@Composable
private fun GeminiCastDialog(ar: Boolean, plan: DubPlan, direction: GeminiDirection, onChange: (GeminiDirection) -> Unit,
                             onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf<GeminiEdit>(GeminiEdit.Cast) }
    val page = edit
    fun back() { when (page) {
        GeminiEdit.Cast -> onDismiss()
        GeminiEdit.Lines, is GeminiEdit.Voice -> edit = GeminiEdit.Cast
        is GeminiEdit.Line -> edit = GeminiEdit.Lines
    } }
    val title = when (page) {
        GeminiEdit.Cast -> if (ar) "توزيع الأصوات" else "Voice cast"
        GeminiEdit.Lines -> if (ar) "المتحدث لكل سطر" else "Speaker for each cue"
        is GeminiEdit.Voice -> speakerLabel(page.speaker, ar)
        is GeminiEdit.Line -> if (ar) "متحدث السطر ${page.cue}" else "Speaker for cue ${page.cue}"
    }
    AlertDialog(onDismissRequest = ::back, title = { Text(title) }, confirmButton = {
        TextButton(onClick = ::back) { Text(if (ar) "رجوع" else "Back") }
    }, text = {
        key(page) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (page) {
                    GeminiEdit.Cast -> {
                        items(direction.cast, key = { it.id }) { character ->
                            val choice = if (character.overridden) (if (ar) "اختيار يدوي" else "Manual choice")
                            else registerLabel(character.register, ar)
                            ReviewRow("cast.${character.id}", speakerLabel(character.id, ar),
                                "\u2066${character.preset.name}\u2069 · $choice") {
                                edit = GeminiEdit.Voice(character.id)
                            }
                        }
                        item { TextButton(onClick = { edit = GeminiEdit.Lines }, modifier = Modifier.testTag("dubbing.gemini.lines")) {
                            Text(if (ar) "صحّح متحدث في سطر" else "Correct a cue's speaker")
                        } }
                    }
                    GeminiEdit.Lines -> items(plan.lines.sortedByDescending { direction.matches[it.number]?.uncertain == true }, key = { it.number }) { line ->
                        val match = direction.matches.getValue(line.number)
                        val uncertain = if (match.uncertain) (if (ar) " · مش مؤكد" else " · uncertain") else ""
                        ReviewRow("cue.${line.number}", "${line.number} · ${speakerLabel(match.speaker, ar)}$uncertain", line.text) {
                            edit = GeminiEdit.Line(line.number)
                        }
                    }
                    is GeminiEdit.Voice -> {
                        val character = direction.cast.first { it.id == page.speaker }
                        val ordered = geminiPresets.sortedWith(compareBy({ it != character.preset }, { it.register != character.register }))
                        items(ordered, key = { it.name }) { preset ->
                            ReviewRow("voice.${preset.name}", preset.name, registerLabel(preset.register, ar)) {
                                onChange(direction.withVoice(page.speaker, preset)); edit = GeminiEdit.Cast
                            }
                        }
                    }
                    is GeminiEdit.Line -> items(direction.cast, key = { it.id }) { character ->
                        ReviewRow("assign.${character.id}", speakerLabel(character.id, ar), character.preset.name) {
                            onChange(direction.withSpeaker(page.cue, character.id)); edit = GeminiEdit.Lines
                        }
                    }
                }
            }
        }
    })
}

@Composable
private fun ReviewRow(id: String, title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .testTag("dubbing.gemini.$id").padding(horizontal = 6.dp, vertical = 5.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = V2.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
