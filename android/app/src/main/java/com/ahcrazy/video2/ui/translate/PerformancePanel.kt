package com.ahcrazy.video2.ui.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.core.model.GpuFallback
import com.ahcrazy.video2.core.model.OfflinePreset
import com.ahcrazy.video2.core.model.TranslationJob
import com.ahcrazy.video2.data.offline.OfflineModel
import com.ahcrazy.video2.ui.components.ChipPreview
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.theme.V2
import java.util.Locale

internal fun OfflinePreset.title(ar: Boolean) = when (this) {
    OfflinePreset.CPU -> if (ar) "CPU · بالتتابع" else "CPU · sequential"
    OfflinePreset.VULKAN -> if (ar) "GPU · بالتتابع" else "GPU · sequential"
    OfflinePreset.OVERLAP -> if (ar) "GPU + CPU · تداخل" else "GPU + CPU · overlap"
}

internal fun runDuration(ms: Long): String {
    val t = ms / 100
    return if (t < 600) String.format(Locale.ROOT, "%d.%d s", t / 10, t % 10)
    else String.format(Locale.ROOT, "%d:%02d.%d", t / 600, (t / 10) % 60, t % 10)
}

@Composable
internal fun PerformanceButton(job: TranslationJob, ar: Boolean, onClick: () -> Unit) {
    val stats = job.offlinePerformance ?: return
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Speed, null, tint = V2.Accent)
        Text(if (ar) "الأداء · ${runDuration(stats.elapsedMs)}" else "Performance · ${runDuration(stats.elapsedMs)}",
            modifier = Modifier.padding(start = 7.dp), fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PerformanceSheet(job: TranslationJob, ar: Boolean, onDismiss: () -> Unit) {
    val p = job.offlinePerformance ?: return
    val asr = OfflineModel.speechForJob(job.offlineSpeechModel)?.label.orEmpty()
    val mt = OfflineModel.translatorForJob(job.offlineModel)?.label.orEmpty()
    val speechMode = when {
        job.offlineTranscriptFrom != null -> if (ar) "تفريغ من مهمة تانية" else "Transcript from another job"
        p.reusedTranscript -> if (ar) "من تفريغ محفوظ" else "Reused transcript"
        p.gpuWindows > 0 && p.cpuWindows > 0 -> "GPU + CPU"
        p.gpuWindows > 0 -> "GPU · Vulkan"
        p.cpuWindows > 0 -> "CPU"
        else -> if (ar) "مفيش نوافذ جديدة" else "No new speech windows"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V2.Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (ar) "تقرير الأداء" else "Performance report", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(job.videoTitle, fontSize = 13.sp, color = V2.TextDim, maxLines = 2)
            GlassCard(Modifier.fillMaxWidth(), emphasized = true) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ChipPreview(job.offlinePreset.title(ar), V2.Accent)
                    Text(runDuration(p.elapsedMs), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(if (ar) "الوقت الكلي للمحاولة دي، لحد حفظ الـSRT" else "This attempt’s wall time, including SRT export",
                        color = V2.TextDim, fontSize = 12.sp)
                    if (p.audioMs > 0) Metric(if (ar) "مدة الصوت" else "Audio duration", runDuration(p.audioMs))
                    p.firstBatchMs?.let { Metric(if (ar) "أول دفعة ترجمة جديدة" else "First new translated batch", runDuration(it)) }
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (ar) "١ · التفريغ الصوتي" else "01 · Transcription", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(asr, color = V2.TextDim, fontSize = 13.sp)
                    Metric(if (ar) "التشغيل الفعلي" else "Actual path", speechMode)
                    if (job.offlineFastSpeech) ChipPreview(if (ar) "تفريغ سريع" else "Fast decoding", V2.Sky)
                    if (p.gpuWindows > 0 || p.speechGpuMs > 0) Metric(
                        if (p.gpuWindows > 0) "GPU / Vulkan" else if (ar) "محاولة GPU" else "GPU attempt", runDuration(p.speechGpuMs))
                    if (p.cpuWindows > 0 || p.speechCpuMs > 0) Metric("CPU", runDuration(p.speechCpuMs))
                    if (p.reusedSpeechWindows > 0) Metric(if (ar) "أجزاء تفريغ محفوظة" else "Reused speech windows", p.reusedSpeechWindows.toString())
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (ar) "٢ · الترجمة النصية" else "02 · Translation", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(mt, color = V2.TextDim, fontSize = 13.sp)
                    when {
                        p.sameLanguage -> Text(if (ar) "نفس اللغة؛ اتنسخ النص الأصلي من غير تشغيل المترجم." else "Same language; no translation engine ran.", fontSize = 12.sp)
                        p.generationCalls == 0 -> Text(if (ar) "اتستخدمت النتيجة المحفوظة." else "Saved translation reused.", fontSize = 12.sp)
                        else -> {
                            Metric("CPU", runDuration(p.translateMs))
                            Metric(if (ar) "دفعات جديدة / طلبات توليد" else "New batches / generation calls", "${p.generatedBatches} / ${p.generationCalls}")
                        }
                    }
                    if (p.reusedBatches > 0) Metric(if (ar) "دفعات محفوظة اتستخدمت" else "Reused batches", "${p.reusedBatches}")
                }
            }
            Metric(if (ar) "تجهيز الصوت" else "Audio extraction", runDuration(p.extractMs))
            Metric(if (ar) "التصدير والحفظ" else "Export", runDuration(p.exportMs))
            p.gpuFallback?.let { Note(when (it) {
                GpuFallback.UNAVAILABLE -> if (ar) "Vulkan مش متاح؛ التفريغ رجع للـCPU." else "Vulkan unavailable; speech used the CPU."
                GpuFallback.HEADROOM -> if (ar) "الرام أو الحرارة ما سمحتش بالـGPU؛ التفريغ اشتغل على CPU." else "Memory or thermal headroom was too low; speech used the CPU."
                GpuFallback.INTERRUPTED -> if (ar) "محاولة GPU سابقة اتقطعت؛ الاستئناف كمل على CPU." else "A previous GPU attempt was interrupted; resumed on CPU."
                GpuFallback.ENGINE -> if (ar) "محرك GPU ماكملش؛ اتعاد الجزء غير المحفوظ على CPU." else "GPU engine stopped; the uncommitted window was retried on CPU."
            }) }
            if (p.overlapped) Note(if (ar) "الترجمة بدأت قبل ما التفريغ يخلص؛ أوقات المرحلتين بتتداخل ومش بتتجمع." else
                "Translation started before transcription finished. Stage times overlap and are not additive.")
            if (p.overlapDeferred) Note(if (ar) "التداخل ماكملش؛ الدفعات المحفوظة فضلت موجودة والتشغيل كمل بالتتابع." else
                "Overlap was deferred; saved batches were kept and work continued sequentially.")
            if (job.offlinePreset == OfflinePreset.OVERLAP && !p.overlapped && !p.overlapDeferred && p.gpuFallback == null && !p.sameLanguage && !p.reusedTranscript) {
                Note(if (ar) "ماكانش فيه دفعة جاهزة للترجمة قبل انتهاء التفريغ." else "No translation batch ran before transcription finished.")
            }
            if (p.resumed || p.reusedTranscript || p.reusedBatches > 0) Note(if (ar) "الوقت ده مش شامل الشغل اللي اتعمل قبل المحاولة دي. للمقارنة، ابدأ مهام جديدة بنفس الفيديو والموديلات." else
                "Time excludes any work done in previous attempts. Compare fresh jobs with the same clip and models.")
            Note(if (ar) "وقت الموديل يشمل التحميل والاستدلال، مش انتظار دفعة جديدة. مسار Vulkan ممكن يستخدم CPU لبعض العمليات." else
                "Model times include loading and inference, not waiting for input. Vulkan may still use CPU for some operations.")
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun Metric(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f), color = V2.TextDim, fontSize = 12.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable private fun Note(text: String) { Text(text, color = V2.TextDim, fontSize = 12.sp, lineHeight = 17.sp) }
