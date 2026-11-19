package com.ahcrazy.video2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.theme.V2
import com.ahcrazy.video2.ui.theme.glassSurface

internal enum class AppTab(val label: String, val icon: ImageVector) {
    BROWSER("tab.browser", Icons.Outlined.Explore),
    LIBRARY("tab.library", Icons.Outlined.VideoLibrary),
    TRANSLATE("tab.translate", Icons.Outlined.ClosedCaption),
    DOWNLOADS("tab.downloads", Icons.Outlined.FileDownload),
    SETTINGS("tab.settings", Icons.Outlined.Settings),
    MAGIC("tab.magic", Icons.Outlined.Search)
}

@Composable
internal fun AppTabs(
    selected: AppTab,
    lang: String,
    savedState: SaveableStateHolder,
    onSelect: (AppTab) -> Unit,
    immersive: Boolean = false,
    content: @Composable (AppTab) -> Unit
) {
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .imePadding()) {
        // Alpha only affects drawing. A hidden full-screen tab can still intercept input and
        // run effects. Keep its saved state, not its layout or Android views, in the hierarchy.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            savedState.SaveableStateProvider(selected.name) { content(selected) }
        }
        if (immersive) Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
        else Column(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
            val labelHeight = with(LocalDensity.current) { 13.sp.toDp() }.coerceAtMost(22.dp)
            Row(Modifier.fillMaxWidth().height(64.dp).selectableGroup().padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AppTab.entries.forEach { tab ->
                    val active = tab == selected
                    val tint = if (active) V2.Accent else V2.TextDim
                    val label = L10n.t(lang, tab.label)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).testTag(tab.label)
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                            .semantics { if (!active) contentDescription = label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(Modifier.size(34.dp).background(if (active) tint.copy(alpha = .18f) else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Box(Modifier.height(labelHeight), contentAlignment = Alignment.Center) {
                            if (active) Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
