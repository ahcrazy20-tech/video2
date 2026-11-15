package com.ahcrazy.video2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.ui.theme.V2

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = V2.Card)) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)) {
        if (icon != null) { Icon(icon, null, tint = V2.Accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)) }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(74.dp).clip(RoundedCornerShape(22.dp)).background(V2.Card), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = V2.Accent, modifier = Modifier.size(34.dp))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(hint, fontSize = 12.sp, color = V2.TextDim, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun BusyButton(text: String, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, modifier = modifier) {
        if (busy) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
        else Text(text, fontSize = 13.sp)
    }
}

/** Simple labeled dropdown used across Settings / pickers. */
@Composable
fun <T> PickerRow(label: String, current: T, options: List<T>, title: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(title(current), fontSize = 12.sp, color = V2.Gold, fontWeight = FontWeight.SemiBold)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(title(o), fontSize = 13.sp) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
fun ChipPreview(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

fun qualityOptions(ar: Boolean): List<Pair<Int, String>> = listOf(
    0 to if (ar) "تلقائي" else "Auto", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p"
)
