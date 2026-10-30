package com.hippo.ehviewer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.theme.ehColorScheme

@Composable
fun PreferenceSection(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun DependentPreferences(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible) {
        Column(Modifier.padding(start = 16.dp)) { content() }
    }
}

@Composable
fun PalettePreference() {
    var mode by Settings.paletteMode.asMutableState()
    val dark = isSystemInDarkTheme()
    Text(
        text = stringResource(R.string.palette_mode),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(R.string.palette_dynamic, R.string.palette_comfort).forEachIndexed { index, label ->
            val colors = ehColorScheme(dark, index, Settings.blackDarkTheme.value)
            Surface(
                modifier = Modifier.weight(1f).selectable(selected = mode == index, role = Role.RadioButton, onClick = { mode = index }),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = androidx.compose.foundation.BorderStroke(
                    if (mode == index) 2.dp else 1.dp,
                    if (mode == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                        drawRoundRect(colors.surface, cornerRadius = CornerRadius(12.dp.toPx()))
                        drawRoundRect(colors.surfaceContainerHighest, Offset(8.dp.toPx(), 8.dp.toPx()), Size(size.width - 16.dp.toPx(), size.height - 16.dp.toPx()), CornerRadius(8.dp.toPx()))
                        drawRoundRect(colors.onSurface, Offset(16.dp.toPx(), 20.dp.toPx()), Size(size.width * .35f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                        drawRoundRect(colors.onSurfaceVariant, Offset(16.dp.toPx(), 32.dp.toPx()), Size(size.width * .5f, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                        drawCircle(colors.primary, 12.dp.toPx(), Offset(size.width - 24.dp.toPx(), size.height - 24.dp.toPx()))
                        drawCircle(colors.onPrimary, 3.dp.toPx(), Offset(size.width - 24.dp.toPx(), size.height - 24.dp.toPx()))
                    }
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
