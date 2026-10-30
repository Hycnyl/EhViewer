package com.ehviewer.core.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ehviewer.core.util.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@Composable
context(_: CoroutineScope)
fun SelectionBar(count: Int, countLabel: String, closeLabel: String, moreLabel: String, onClose: () -> Unit, builder: FabBuilder.() -> Unit) {
    PredictiveBackHandler {
        it.collect { }
        onClose()
    }
    val actions = buildFab(builder)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        BottomAppBar {
            TooltipIconButton(label = closeLabel, onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
            Text(count.toString(), modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = countLabel }, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    actions.take(3).forEach { action ->
                        TooltipIconButton(label = action.label.orEmpty(), onClick = { launch(Dispatchers.Default) { action.action() } }) {
                            Icon(action.icon, contentDescription = null)
                        }
                    }
                    if (actions.size > 3) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TooltipIconButton(label = moreLabel, onClick = { expanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                actions.drop(3).forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label.orEmpty()) },
                                        leadingIcon = { Icon(action.icon, contentDescription = null) },
                                        onClick = {
                                            expanded = false
                                            launch(Dispatchers.Default) { action.action() }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
