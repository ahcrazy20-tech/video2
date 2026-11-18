package com.ahcrazy.video2.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.core.model.SubCue
import com.ahcrazy.video2.core.util.MagicDuration
import com.ahcrazy.video2.ui.theme.V2

internal enum class SubMode(val ar: String, val en: String) {
    OFF("إيقاف", "Off"), ORIGINAL("الأصلي", "Original"), TRANSLATED("المترجم", "Translated"), BILINGUAL("ثنائي", "Bilingual")
}

internal data class PlayerTool(val icon: ImageVector, val title: String, val detail: String? = null,
                               val enabled: Boolean = true, val action: () -> Unit)

@Composable
internal fun PlayerTools(ar: Boolean, tools: List<PlayerTool>, dismiss: () -> Unit) {
    PlayerDialog(ar, if (ar) "أدوات المشغّل" else "Player tools", dismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (tool in tools) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(V2.CardSoft)
                    .clickable(enabled = tool.enabled, role = Role.Button, onClick = tool.action)
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (tool.enabled) V2.Text else V2.TextDim.copy(alpha = .6f)
                    Icon(tool.icon, null, Modifier.size(20.dp), tint = if (tool.enabled) V2.Mint else tint)
                    Column(Modifier.weight(1f)) {
                        Text(tool.title, color = tint, style = MaterialTheme.typography.bodyMedium)
                        tool.detail?.let { Text(it, color = V2.TextDim, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> PlayerChoices(ar: Boolean, title: String, options: List<T>, selected: T?, label: (T) -> String,
                               dismiss: () -> Unit, onChoose: (T) -> Unit) {
    PlayerDialog(ar, title, dismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) { ChoiceList(options, selected, label, onChoose) }
    }
}

@Composable
internal fun PlayerCaptionsSettings(ar: Boolean, modes: List<SubMode>, mode: SubMode, size: Int,
                                    onMode: (SubMode) -> Unit, onSize: (Int) -> Unit, dismiss: () -> Unit) {
    PlayerDialog(ar, if (ar) "الترجمة على الشاشة" else "On-screen captions", dismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ChoiceList(modes, mode, { if (ar) it.ar else it.en }, onMode)
            Spacer(Modifier.height(12.dp))
            Text(if (ar) "حجم الخط" else "Text size", style = MaterialTheme.typography.titleSmall)
            ChoiceList(listOf(14, 18, 23, 28), size, { "$it" }, onSize)
        }
    }
}

@Composable
internal fun PlayerCaptionSearch(ar: Boolean, cues: List<SubCue>, dismiss: () -> Unit, onJump: (SubCue) -> Unit) {
    var query by remember { mutableStateOf("") }
    val hits = remember(cues, query) {
        val q = query.trim()
        cues.asSequence().filter { q.isEmpty() || it.original.contains(q, true) || it.translated.contains(q, true) }.take(60).toList()
    }
    PlayerDialog(ar, if (ar) "بحث في الترجمة" else "Search captions", dismiss) {
        Column {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (ar) "اكتب كلمة" else "Find a word") })
            Spacer(Modifier.height(8.dp))
            if (hits.isEmpty()) Text(if (ar) "مفيش نتائج" else "No matches", color = V2.TextDim)
            else LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(hits) { cue ->
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onJump(cue) }.padding(vertical = 8.dp)) {
                        Text(MagicDuration.format(cue.startSec), color = V2.Mint, style = MaterialTheme.typography.labelMedium)
                        if (cue.original.isNotBlank()) Text(cue.original, style = MaterialTheme.typography.bodyMedium)
                        if (cue.translated.isNotBlank()) Text(cue.translated, color = V2.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = V2.Divider)
                }
            }
        }
    }
}

@Composable
internal fun PlayerCaptionLines(original: String?, translated: String?, size: Int) {
    if (original == null && translated == null) return
    Column(Modifier.widthIn(max = 700.dp).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        original?.let { PlayerCaptionLine(it, size, Color.White) }
        translated?.let { PlayerCaptionLine(it, size, V2.Gold) }
    }
}

@Composable
private fun PlayerCaptionLine(text: String, size: Int, color: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .82f))
        .padding(horizontal = 10.dp, vertical = 3.dp), color = color, fontSize = size.sp, lineHeight = (size + 4).sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}

@Composable
private fun <T> ChoiceList(options: List<T>, selected: T?, label: (T) -> String, onChoose: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            val checked = option == selected
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = checked, role = Role.RadioButton, onClick = { onChoose(option) }),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = checked, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = V2.Mint))
                Text(label(option), color = if (checked) V2.Mint else V2.Text)
            }
        }
    }
}

@Composable
private fun PlayerDialog(ar: Boolean, title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = content, containerColor = V2.Card,
        confirmButton = { TextButton(onClick = dismiss) { Text(if (ar) "تم" else "Done", color = V2.Mint) } })
}
