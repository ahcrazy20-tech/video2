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
import com.ahcrazy.video2.core.dubbing.MAX_DUB_SECONDS
import com.ahcrazy.video2.core.dubbing.GeminiDirection
import com.ahcrazy.video2.core.dubbing.GeminiOptions
import com.ahcrazy.video2.core.dubbing.GeminiRegister
import com.ahcrazy.video2.core.dubbing.geminiPresets
import com.ahcrazy.video2.data.dubbing.GeminiDubModel
import com.ahcrazy.video2.data.dubbing.GeminiRoutingEvent
import com.ahcrazy.video2.data.dubbing.GeminiStage
import com.ahcrazy.video2.data.dubbing.GeminiStep
import com.ahcrazy.video2.data.dubbing.GeminiTask
import com.ahcrazy.video2.ui.theme.V2

internal data class GeminiPanelState(val configured: Boolean = false, val inputError: String? = null,
                                    val direction: GeminiDirection? = null, val stage: GeminiStage? = null,
                                    val keyError: String? = null,
                                    val alternates: Map<GeminiTask, GeminiDubModel> = emptyMap(),
                                    val waiting: GeminiRoutingEvent.Waiting? = null)

@Composable
internal fun GeminiDubbingControls(ar: Boolean, state: GeminiPanelState, plan: DubPlan?, loading: Boolean, busy: Boolean,
                                    options: GeminiOptions, onOptions: (GeminiOptions) -> Unit,
                                    onDirection: (GeminiDirection) -> Unit, onSettings: (() -> Unit)?) {
    var review by remember { mutableStateOf(false) }
    Text(if (ar) "كادر بيختار صوت ثابت لكل متحدث وبيحاكي طريقة كلامه وضحكه أو تنهيدته لما تكون واضحة. دي أصوات جاهزة، مش استنساخ."
        else "Kadr casts a consistent stock voice per speaker and follows audible delivery and reactions. It doesn't clone voices.",
        style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
    Text(if (ar) "الفيديو الطويل بيتحلّل على أجزاء. حد الدبلجة على الجهاز ${MAX_DUB_SECONDS / 3600} ساعة، حسب مساحة التخزين وحصة Gemini."
        else "Long videos are analyzed in parts. The on-device dubbing limit is ${MAX_DUB_SECONDS / 3600} hours, subject to storage and Gemini quota.",
        style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
    if (!loading && !state.configured) {
        Text(state.keyError ?: if (ar) "أضف مفتاحك من Google AI Studio. اشتراك Gemini Pro لوحده مش مفتاح."
            else "Add your Google AI Studio key. A Gemini Pro subscription alone isn't an API key.",
            style = MaterialTheme.typography.bodySmall, color = V2.Gold)
    }
    if (!loading && !busy && onSettings != null) {
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().testTag("dubbing.gemini.settings")) {
            Text(if (state.configured) {
                if (ar) "اختبر مفتاح Gemini في الإعدادات" else "Test Gemini key in Settings"
            } else if (ar) "افتح إعدادات مفتاح Gemini" else "Open Gemini key settings")
        }
    }
    state.inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = V2.Danger) }
    if (state.configured && state.inputError == null && plan != null) {
        val enabled = !loading && !busy
        GeminiToggle("accent", if (ar) "نطق مصري للنص العربي" else "Egyptian pronunciation for Arabic text",
            options.egyptianArabic, enabled) { onOptions(options.copy(egyptianArabic = it)) }
        GeminiToggle("reactions", if (ar) "ردود الفعل المسموعة (ضحك، تنهيدة…)" else "Audible vocal reactions (laughs, sighs…)",
            options.reactions, enabled) { onOptions(options.copy(reactions = it)) }
        Text(if (ar) "المعاينة الأولى بتحلّل أجزاء الصوت حوالين السطور، وبتولّد عيّنة قصيرة بس. الدبلجة الكاملة ممكن تحتاج لحد ${plan.lines.size} طلب توليد، غير التحليل وإعادة المحاولة. الاستخدام حسب حصة وفوترة مشروعك، وجوجل ممكن تستخدم المدخلات لتحسين خدماتها في الحصة المجانية."
            else "The first preview analyzes audio around the subtitles, then generates only a short sample. A full dub may need up to ${plan.lines.size} speech requests before analysis and retries. Usage follows your project's quota and billing; Google may use free-tier inputs to improve its services.",
            style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
        GeminiToggle("audio", if (ar) "أسمح بإرسال الصوت الأصلي إلى Gemini للتحليل (من غير صورة الفيديو)."
            else "Allow Gemini to analyze the original audio (no video frames).", options.allowAudio, enabled) {
            onOptions(options.copy(allowAudio = it))
        }
        GeminiToggle("text", if (ar) "أسمح بإرسال نص العيّنة أو الترجمة إلى Gemini لتوليد الأصوات."
            else "Allow sending preview or subtitle text to Gemini to generate speech.", options.allowText, enabled) {
            onOptions(options.copy(allowText = it))
        }
        GeminiToggle("modelFallback", if (ar) "جرّب بدائل Gemini عند بلوغ الحد" else "Try alternate Gemini models at the limit",
            options.allowModelFallback, enabled) { onOptions(options.copy(allowModelFallback = it)) }
        if (options.allowModelFallback) Text(if (ar) "Flash-Lite للتحليل وFlash-Lite TTS للصوت. لو Transcribe وقف، تحديد المتحدثين يبقى تقديري. البدائل مدرجة في Free Tier، لكن الاستخدام على نفس مشروع مفتاحك وحصته وفوترته."
            else "Flash-Lite for analysis and Flash-Lite TTS for speech. Speaker assignments are estimated if Transcribe stops. Alternatives are listed on the Free Tier but use the same project's quotas and billing.",
            style = MaterialTheme.typography.bodySmall, color = V2.TextDim)
        state.direction?.let { direction ->
            val roles = direction.cast.size
            Text(if (ar) "اتوزّعت أصوات جاهزة على $roles مجموعة كلام، من غير تحديد هوية الأشخاص."
                else "Assigned stock voices to $roles speech groups, without identifying anyone.",
                style = MaterialTheme.typography.bodySmall, color = V2.Mint)
            if (direction.uncertainCount > 0) Text(if (ar) "${direction.uncertainCount} سطر تحديد متحدثه مش واضح. تقدر تراجعه."
                else "${direction.uncertainCount} cues have uncertain speaker assignments. You can review them.",
                style = MaterialTheme.typography.bodySmall, color = V2.Gold)
            if (direction.estimatedCount > 0) Text(if (ar) "${direction.estimatedCount} سطر متحدثه تقديري من الموديل البديل. راجع توزيع الأصوات لو محتاج تصحيح."
                else "The alternate model estimated the speaker for ${direction.estimatedCount} cues. Review the cast if needed.",
                style = MaterialTheme.typography.bodySmall, color = V2.Gold)
            val unlinked = direction.cast.count { it.unlinked }
            if (unlinked > 0) Text(if (ar) "$unlinked مجموعة ما قدرناش نربطها بالأجزاء اللي قبلها. لو نفس الشخص ظهر تاني تقدر تدمج مجموعته من توزيع الأصوات."
                else "$unlinked groups couldn't be linked to earlier parts. If someone returns, you can merge their groups in voice review.",
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
    GeminiStep.ASSIGN_SPEAKERS -> if (ar) "تمييز المتحدثين · جزء $part من $total" else "Identifying speakers · part $part of $total"
    GeminiStep.DIRECT_SCENES -> if (ar) "تحليل النبرة وردود الفعل · مشهد $part من $total" else "Analyzing delivery · scene $part of $total"
}

internal val GeminiStage.task: GeminiTask? get() = when (step) {
    GeminiStep.EXTRACT_AUDIO -> null
    GeminiStep.ASSIGN_SPEAKERS -> GeminiTask.SPEAKERS
    GeminiStep.DIRECT_SCENES -> GeminiTask.SCENE
}

internal fun GeminiTask.label(ar: Boolean) = when (this) {
    GeminiTask.SPEAKERS -> if (ar) "تمييز المتحدثين" else "Speaker analysis"
    GeminiTask.SCENE -> if (ar) "تحليل النبرة" else "Scene direction"
    GeminiTask.SPEECH -> if (ar) "توليد الصوت" else "Speech generation"
}

private sealed interface GeminiEdit {
    data object Cast : GeminiEdit
    data object Lines : GeminiEdit
    data class Voice(val speaker: Int) : GeminiEdit
    data class Merge(val speaker: Int) : GeminiEdit
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
    val examples = remember(plan, direction.matches) {
        buildMap {
            for (line in plan.lines) putIfAbsent(direction.matches.getValue(line.number).speaker, line.text)
        }
    }
    fun back() { when (page) {
        GeminiEdit.Cast -> onDismiss()
        GeminiEdit.Lines, is GeminiEdit.Voice -> edit = GeminiEdit.Cast
        is GeminiEdit.Line -> edit = GeminiEdit.Lines
        is GeminiEdit.Merge -> edit = GeminiEdit.Voice(page.speaker)
    } }
    val title = when (page) {
        GeminiEdit.Cast -> if (ar) "توزيع الأصوات" else "Voice cast"
        GeminiEdit.Lines -> if (ar) "المتحدث لكل سطر" else "Speaker for each cue"
        is GeminiEdit.Voice -> speakerLabel(page.speaker, ar)
        is GeminiEdit.Line -> if (ar) "متحدث السطر ${page.cue}" else "Speaker for cue ${page.cue}"
        is GeminiEdit.Merge -> if (ar) "ادمج المتحدث ${page.speaker} مع…" else "Merge speaker ${page.speaker} into…"
    }
    AlertDialog(onDismissRequest = ::back, title = { Text(title) }, confirmButton = {
        TextButton(onClick = ::back) { Text(if (ar) "رجوع" else "Back") }
    }, text = {
        key(page) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (page) {
                    GeminiEdit.Cast -> {
                        item { TextButton(onClick = { edit = GeminiEdit.Lines }, modifier = Modifier.testTag("dubbing.gemini.lines")) {
                            Text(if (ar) "صحّح متحدث في سطر" else "Correct a cue's speaker")
                        } }
                        items(direction.cast, key = { it.id }) { character ->
                            val choice = if (character.overridden) (if (ar) "اختيار يدوي" else "Manual choice")
                            else registerLabel(character.register, ar)
                            val link = if (character.unlinked) (if (ar) " · ربط غير مؤكد" else " · link unconfirmed") else ""
                            ReviewRow("cast.${character.id}", speakerLabel(character.id, ar) + link,
                                "\u2066${character.preset.name}\u2069 · $choice") {
                                edit = GeminiEdit.Voice(character.id)
                            }
                        }
                    }
                    GeminiEdit.Lines -> items(plan.lines.sortedByDescending {
                        direction.matches[it.number]?.let { match -> match.uncertain || match.estimated } == true
                    }, key = { it.number }) { line ->
                        val match = direction.matches.getValue(line.number)
                        val uncertain = when {
                            match.estimated -> if (ar) " · تقديري" else " · estimated"
                            match.uncertain -> if (ar) " · مش مؤكد" else " · uncertain"
                            else -> ""
                        }
                        ReviewRow("cue.${line.number}", "${line.number} · ${speakerLabel(match.speaker, ar)}$uncertain", line.text) {
                            edit = GeminiEdit.Line(line.number)
                        }
                    }
                    is GeminiEdit.Voice -> {
                        val character = direction.cast.first { it.id == page.speaker }
                        if (direction.cast.size > 1) item {
                            ReviewRow("merge", if (ar) "نفس متحدث تاني؟ ادمج المجموعتين" else "Same person as another group? Merge",
                                examples[page.speaker].orEmpty()) { edit = GeminiEdit.Merge(page.speaker) }
                        }
                        val ordered = geminiPresets.sortedWith(compareBy({ it != character.preset }, { it.register != character.register }))
                        items(ordered, key = { it.name }) { preset ->
                            ReviewRow("voice.${preset.name}", preset.name, registerLabel(preset.register, ar)) {
                                onChange(direction.withVoice(page.speaker, preset)); edit = GeminiEdit.Cast
                            }
                        }
                    }
                    is GeminiEdit.Merge -> items(direction.cast.filter { it.id != page.speaker }, key = { it.id }) { character ->
                        ReviewRow("merge.${character.id}", "${speakerLabel(character.id, ar)} · ${character.preset.name}",
                            examples[character.id].orEmpty()) {
                            onChange(direction.mergeSpeaker(page.speaker, character.id)); edit = GeminiEdit.Cast
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
