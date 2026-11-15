package com.ahcrazy.video2.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.app
import com.ahcrazy.video2.core.model.RefinerProvider
import com.ahcrazy.video2.core.model.STTProvider
import com.ahcrazy.video2.core.model.TranslatorProvider
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.data.AppSettings
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.components.PickerRow
import com.ahcrazy.video2.ui.components.SectionTitle
import com.ahcrazy.video2.ui.components.qualityOptions
import com.ahcrazy.video2.ui.theme.V2
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(ar: Boolean, onLangChange: (String) -> Unit, onLockNow: () -> Unit) {
    val ctx = LocalContext.current
    val app = (ctx as android.app.Activity).app()
    val scope = rememberCoroutineScope()
    var newPassword by remember { mutableStateOf("") }
    var pwMsg by remember { mutableStateOf<String?>(null) }
    var hasPw by remember { mutableStateOf(app.settings.hasAppPassword()) }
    val maxHeight by app.settings.downloadMaxHeight.collectAsState(0)
    val adOn by app.settings.adBlockEnabled.collectAsState(true)
    val adMode by app.settings.adBlockMode.collectAsState("balanced")
    val filterAds by app.settings.adBlockFilterVideoAds.collectAsState(true)
    val concurrency by app.settings.sttConcurrency.collectAsState(3)
    val azureRegion by app.settings.azureRegion.collectAsState("eastus")
    var region by remember(azureRegion) { mutableStateOf(azureRegion) }
    var sttPref by remember { mutableStateOf("auto") }
    var refPref by remember { mutableStateOf("auto") }
    var trPref by remember { mutableStateOf("auto") }
    var storageMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(V2.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text(if (ar) "الإعدادات" else "Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))

        SectionTitle("🔒 " + if (ar) "حماية التطبيق" else "App protection")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                OutlinedTextField(newPassword, { newPassword = it },
                    placeholder = { Text(if (ar) "كلمة سر جديدة (4 أحرف على الأقل)" else "New password (4+ chars)", fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val ok = app.settings.setAppPassword(newPassword)
                    pwMsg = if (ok) { hasPw = true; newPassword = ""; if (ar) "تم حفظ كلمة السر ✓ (هاش SHA-256 في Keystore)" else "Saved ✓" }
                            else if (ar) "كلمة السر قصيرة جدًا" else "Password too short"
                }, enabled = newPassword.length >= 4, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (hasPw) (if (ar) "تغيير كلمة السر" else "Change password") else (if (ar) "تفعيل كلمة السر" else "Enable password"))
                }
                if (hasPw) Row {
                    TextButton(onClick = onLockNow) { Text(if (ar) "قفل التطبيق الآن" else "Lock now") }
                    TextButton(onClick = { app.settings.removeAppPassword(); hasPw = false }) { Text(if (ar) "إلغاء كلمة السر" else "Remove password", color = V2.Accent) }
                }
                pwMsg?.let { Text(it, fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 6.dp)) }
            }
        }

        SectionTitle("🌐 " + if (ar) "لغة التطبيق" else "App language")
        GlassCard(Modifier.fillMaxWidth()) {
            Row {
                listOf("ar" to "العربية", "en" to "English").forEach { (code, label) ->
                    val active = (if (ar) "ar" else "en") == code
                    Button(onClick = { onLangChange(code) }, enabled = !active, modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { Text(label) }
                }
            }
        }

        SectionTitle("⬇️ " + if (ar) "التحميل والتشغيل" else "Downloads & playback")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                PickerRow(if (ar) "أقصى جودة افتراضية" else "Default max quality", maxHeight,
                    qualityOptions(ar).map { it.first }, { h -> qualityOptions(ar).first { it.first == h }.second }) {
                    scope.launch { app.settings.setDownloadMaxHeight(it) }
                }
                Text(if (ar) "بتتطبق على تحميلات HLS من المتصفح والبحث السحري." else "Applies to HLS downloads from Browser & Magic Search.", fontSize = 11.sp, color = V2.TextDim)
            }
        }

        SectionTitle("🛡️ " + if (ar) "مانع الإعلانات" else "AdBlock")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                ToggleRow(if (ar) "تفعيل مانع الإعلانات" else "Enable AdBlock", adOn) { scope.launch { app.settings.setAdBlockEnabled(it) } }
                PickerRow(if (ar) "الوضع" else "Mode", adMode, listOf("balanced", "strict"),
                    { if (it == "balanced") (if (ar) "متوازن" else "Balanced") else (if (ar) "صارم" else "Strict") }) {
                    scope.launch { app.settings.setAdBlockMode(it) }
                }
                ToggleRow(if (ar) "تصفية إعلانات الفيديو من الكشف" else "Filter video ads from detection", filterAds) {
                    scope.launch { app.settings.setAdBlockFilterVideoAds(it) }
                }
                Text(if (ar) "بيحجب ~60 نطاق إعلان وتتبع جوه المتصفح ويرشح المقاطع الإعلانية." else "Blocks ~60 ad/tracker hosts in the browser and filters ad clips.", fontSize = 11.sp, color = V2.TextDim)
            }
        }

        SectionTitle("🔑 " + if (ar) "مفاتيح مزودي الصوت والترجمة والفيديو" else "API keys")
        Text(if (ar) "بتتخزن مشفرة في Android Keystore على جهازك بس. MyMemory للترجمة شغال من غير مفتاح (5K كلمة/يوم)." else "Stored encrypted in the Android Keystore only. MyMemory translation needs no key.", fontSize = 11.sp, color = V2.TextDim)
        AppSettings.apiKeyRows.forEach { (id, title, url) ->
            ApiKeyRow(title, id, url, ar)
        }

        SectionTitle("🎛️ " + if (ar) "تفضيلات التفريغ والترجمة" else "Speech & translation preferences")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                PickerRow(if (ar) "مزود التفريغ الصوتي" else "Transcription", STTProvider.of(sttPref), STTProvider.entries, { if (ar) it.titleAr else it.titleEn }) { sttPref = it.id }
                PickerRow(if (ar) "مراجعة وتدقيق النصوص" else "Proofreading", RefinerProvider.of(refPref), RefinerProvider.entries, { if (ar) it.titleAr else it.titleEn }) { refPref = it.id }
                PickerRow(if (ar) "مزود الترجمة النصية" else "Translation", TranslatorProvider.of(trPref), TranslatorProvider.entries, { if (ar) it.titleAr else it.titleEn }) { trPref = it.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ar) "توازي التفريغ: $concurrency" else "Transcription concurrency: $concurrency", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { scope.launch { app.settings.setSttConcurrency((concurrency - 1).coerceAtLeast(1)) } }) { Text("−") }
                    TextButton(onClick = { scope.launch { app.settings.setSttConcurrency((concurrency + 1).coerceAtMost(4)) } }) { Text("+") }
                }
                Text(if (ar) "عدد أجزاء الصوت اللي تتفرغ مع بعض — قلله لو ظهرت أخطاء 429." else "Parallel audio chunks — lower it if you see HTTP 429.", fontSize = 11.sp, color = V2.TextDim)
            }
        }

        SectionTitle("☁️ " + if (ar) "إعداد مزودي الكلام (Azure / Deepgram)" else "Speech providers setup")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                OutlinedTextField(region, { region = it }, label = { Text(if (ar) "منطقة Speech (مثال: eastus)" else "Speech region (e.g. eastus)", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { scope.launch { app.settings.setAzureRegion(region) } }) { Text(if (ar) "حفظ المنطقة" else "Save region") }
                listOf(
                    "إنشاء مورد Speech ومفتاح Azure" to "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices",
                    "إنشاء حساب ومفتاح Deepgram" to "https://console.deepgram.com/signup",
                    "أسعار وحدود Azure Speech الرسمية" to "https://azure.microsoft.com/en-us/pricing/details/speech/"
                ).forEach { (label, u) ->
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }) { Text("↗ $label", fontSize = 12.sp) }
                }
                Text(if (ar) "طبقة Azure F0 الموثقة: 5 ساعات تفريغ + 500 ألف حرف نطق شهريًا." else "Azure F0 (documented): 5h STT + 500K TTS chars/month.", fontSize = 11.sp, color = V2.TextDim)
            }
        }

        SectionTitle("💾 " + if (ar) "التخزين" else "Storage")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                fun size(f: File) = if (f.exists()) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                val vids = size(app.library.videosDir); val th = size(app.library.thumbsDir)
                val subs = size(app.library.subtitlesDir); val dubs = size(app.library.dubsDir)
                StorageLine(if (ar) "الفيديوهات" else "Videos", vids); StorageLine(if (ar) "المصغرات" else "Thumbnails", th)
                StorageLine(if (ar) "الترجمات" else "Subtitles", subs); StorageLine(if (ar) "الدبلجات" else "Dubbing", dubs)
                StorageLine(if (ar) "الإجمالي" else "Total", vids + th + subs + dubs)
                Row {
                    OutlinedButton(onClick = {
                        var freed = 0L
                        ctx.cacheDir.listFiles()?.forEach { freed += size(it); it.deleteRecursively() }
                        storageMsg = (if (ar) "اتحرر " else "Freed ") + L10n.fmtBytes(freed)
                    }) { Text(if (ar) "تنظيف المؤقت" else "Purge temp", fontSize = 12.sp) }
                    TextButton(onClick = {
                        val known = app.library.videos.value.map { File(it.localPath).absolutePath }.toSet() +
                            app.library.videos.value.map { File(it.localPath).parentFile?.absolutePath }.filterNotNull()
                        var freed = 0L
                        app.library.videosDir.listFiles()?.forEach { f ->
                            if (f.absolutePath !in known) { freed += size(f); f.deleteRecursively() }
                        }
                        storageMsg = (if (ar) "اتحرر " else "Freed ") + L10n.fmtBytes(freed)
                    }) { Text(if (ar) "حذف اليتيم" else "Purge orphans", fontSize = 12.sp, color = V2.Accent) }
                }
                storageMsg?.let { Text(it, fontSize = 11.sp, color = V2.Mint, modifier = Modifier.padding(top = 6.dp)) }
            }
        }

        SectionTitle("ℹ️ " + if (ar) "عن التطبيق" else "About")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                StorageLine(if (ar) "التطبيق" else "App", -1, "فيديو ٢ · Video2")
                StorageLine("Bundle / Package", -1, "com.ahcrazy.video2")
                StorageLine(if (ar) "الهدف" else "Target", -1, "Android 8+ · Media3 ExoPlayer · Kotlin/Compose")
                Text(if (ar) "الاستخراج: HTML5 video/source · fetch/XHR · Performance log · HLS m3u8 · MP4/WebM/DASH" else "Extraction: HTML5 · fetch/XHR · Performance · HLS · DASH", fontSize = 11.sp, color = V2.TextDim, modifier = Modifier.padding(top = 8.dp))
                Text(if (ar) "سياسة DRM: التطبيق لا يكسر FairPlay/Widevine/PlayReady أبدًا — كشف وتحذير ومنع تحميل بس." else "DRM policy: never bypass FairPlay/Widevine/PlayReady — detect, warn, refuse.", fontSize = 11.sp, color = V2.Gold, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.padding(bottom = 90.dp))
    }
}

@Composable private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable private fun StorageLine(label: String, bytes: Long, override: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = V2.TextDim, modifier = Modifier.weight(1f))
        Text(override ?: L10n.fmtBytes(bytes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ApiKeyRow(title: String, keyId: String, getKeyUrl: String, ar: Boolean) {
    val ctx = LocalContext.current
    val app = (ctx as android.app.Activity).app()
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(app.settings.hasKey(keyId)) }
    var msg by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (saved) Text("✓ " + if (ar) "محفوظ" else "Saved", fontSize = 11.sp, color = V2.Mint, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value, { value = it },
                    placeholder = { Text(if (saved) "••••••••" else if (ar) "الصق المفتاح هنا" else "Paste key here", fontSize = 11.sp) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (value.isNotBlank()) { app.settings.setKey(keyId, value); saved = true; value = ""; msg = if (ar) "تم الحفظ في Keystore ✓" else "Saved to Keystore ✓" }
                }) { Text(if (ar) "حفظ" else "Save", fontSize = 12.sp) }
                if (saved) TextButton(onClick = { app.settings.deleteKey(keyId); saved = false }) { Text("✕", color = V2.Accent) }
            }
            Row {
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getKeyUrl))) }) {
                    Text("↗ " + if (ar) "احصل على المفتاح" else "Get key", fontSize = 11.sp)
                }
                msg?.let { Text(it, fontSize = 11.sp, color = V2.Mint, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
    }
}
