package com.ahcrazy.video2.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.ui.components.GlassCard
import com.ahcrazy.video2.ui.components.SectionTitle
import com.ahcrazy.video2.ui.theme.V2
import com.ahcrazy.video2.ui.theme.Video2Appearance

@Composable
internal fun AppearancePicker(ar: Boolean, selected: Video2Appearance, onSelect: (Video2Appearance) -> Unit) {
    SectionTitle(if (ar) "المظهر" else "Appearance")
    GlassCard(Modifier.fillMaxWidth(), emphasized = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (ar) "زجاج المجرة" else "Galaxy glass", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(if (ar) "تدرجات هادية وحواف زجاجية من غير تمويه متحرّك." else "Soft gradients and glass highlights, without moving blur.",
                color = V2.TextDim, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Video2Appearance.entries.forEach { theme ->
                    val active = theme == selected
                    val shape = RoundedCornerShape(13.dp)
                    Column(Modifier.weight(1f).clip(shape).background(theme.ink)
                        .border(if (active) 2.dp else 1.dp, if (active) Color.White else Color.White.copy(alpha = .19f), shape)
                        .selectable(active, role = Role.RadioButton) { onSelect(theme) }.padding(7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(listOf(theme.ink, theme.swatch))), Alignment.Center) {
                            Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White.copy(alpha = if (active) .9f else .4f)))
                        }
                        Text(if (ar) theme.ar else theme.en, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
