package com.ahcrazy.video2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahcrazy.video2.core.util.L10n
import com.ahcrazy.video2.ui.theme.V2

internal enum class AppTab(val label: String, val icon: ImageVector) {
    BROWSER("tab.browser", Icons.Filled.Explore),
    LIBRARY("tab.library", Icons.Filled.PlayCircle),
    TRANSLATE("tab.translate", Icons.Filled.ClosedCaption),
    DOWNLOADS("tab.downloads", Icons.Filled.CloudDownload),
    SETTINGS("tab.settings", Icons.Filled.Settings),
    MAGIC("tab.magic", Icons.Filled.AutoAwesome)
}

@Composable
internal fun AppTabs(
    selected: AppTab,
    lang: String,
    savedState: SaveableStateHolder,
    onSelect: (AppTab) -> Unit,
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
        Column(Modifier.fillMaxWidth().background(V2.Card)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
            Row(Modifier.fillMaxWidth().height(62.dp).selectableGroup().padding(horizontal = 2.dp, vertical = 4.dp)) {
                AppTab.entries.forEach { tab ->
                    val active = tab == selected
                    val tint = if (active) V2.Accent else V2.TextDim
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                            .testTag(tab.label).selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                        Text(L10n.t(lang, tab.label), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
                        Box(Modifier.padding(top = 2.dp).size(5.dp).clip(RoundedCornerShape(50))
                            .then(if (active) Modifier.background(V2.Accent) else Modifier))
                    }
                }
            }
        }
    }
}
