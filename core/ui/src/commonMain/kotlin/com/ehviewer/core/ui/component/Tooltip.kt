package com.ehviewer.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun IconTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

// Use only for standalone buttons without an existing long-press action.
@Composable
fun TooltipIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    content: @Composable () -> Unit,
) {
    IconTooltip(label) {
        IconButton(
            onClick = onClick,
            modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label },
            enabled = enabled,
            shapes = shapes,
        ) {
            Box(Modifier.clearAndSetSemantics { }) { content() }
        }
    }
}
