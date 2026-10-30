package com.ehviewer.core.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import arrow.resilience.Schedule
import arrow.resilience.retry
import com.ehviewer.core.ui.util.PredictiveBackEasing
import com.ehviewer.core.ui.util.asyncState
import com.ehviewer.core.ui.util.snackBarPadding
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.onEachLatest
import kotlin.time.Duration.Companion.microseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Stable
enum class FabLayoutValue { Hidden, Primary, Expand }

@Stable
@Composable
fun rememberFabLayoutState(initialValue: FabLayoutValue = FabLayoutValue.Primary) = remember {
    FabLayoutState(initialValue)
}

@Stable
class FabLayoutState(
    initialValue: FabLayoutValue,
    private val animationSpec: AnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 500f),
) {
    val mutatorMutex = MutatorMutex()

    val appearProgress = Animatable(if (initialValue != FabLayoutValue.Hidden) 1f else 0f)
        .apply { updateBounds(0f, 1f) }

    val expandProgress = Animatable(if (initialValue == FabLayoutValue.Expand) 1f else 0f)
        .apply { updateBounds(0f, 1f) }

    private suspend fun downTo(value: FabLayoutValue, priority: MutatePriority) = mutatorMutex.mutate(priority) {
        coroutineScope {
            val collapse = async { expandProgress.animateTo(0f, animationSpec) }
            if (value == FabLayoutValue.Hidden) appearProgress.animateTo(0f, animationSpec)
            collapse.await()
        }
    }

    private suspend fun upTo(value: FabLayoutValue, priority: MutatePriority) = mutatorMutex.mutate(priority) {
        coroutineScope {
            val appear = async { appearProgress.animateTo(1f, animationSpec) }
            if (value == FabLayoutValue.Expand) expandProgress.animateTo(1f, animationSpec)
            appear.await()
        }
    }

    suspend fun show(priority: MutatePriority = MutatePriority.Default) = upTo(FabLayoutValue.Primary, priority)
    suspend fun hide(priority: MutatePriority = MutatePriority.Default) = downTo(FabLayoutValue.Hidden, priority)
    suspend fun collapse(priority: MutatePriority = MutatePriority.Default) = downTo(FabLayoutValue.Primary, priority)
    suspend fun expand(priority: MutatePriority = MutatePriority.Default) = upTo(FabLayoutValue.Expand, priority)
}

fun interface FabBuilder {
    fun add(icon: ImageVector, label: String?, autoClose: Boolean, that: suspend () -> Unit)
    fun onClick(icon: ImageVector, autoClose: Boolean = true, label: String? = null, that: suspend () -> Unit) = add(icon, label, autoClose, that)
}

internal data class FabAction(val icon: ImageVector, val label: String?, val autoClose: Boolean, val action: suspend () -> Unit)

@Composable
context(_: CoroutineScope)
fun FabLayout(
    hidden: Boolean,
    label: String,
    closeLabel: String,
    singleAction: Boolean = false,
    expanded: Boolean,
    onExpandChanged: (Boolean) -> Unit,
    autoCancel: Boolean,
    fabBuilder: FabBuilder.() -> Unit,
) {
    val updatedHidden by rememberUpdatedState(hidden)
    val updatedExpanded by rememberUpdatedState(expanded)
    val newState = when {
        updatedHidden -> FabLayoutValue.Hidden
        updatedExpanded -> FabLayoutValue.Expand
        else -> FabLayoutValue.Primary
    }
    val state = rememberFabLayoutState(newState)
    LaunchedEffect(newState) {
        when (newState) {
            FabLayoutValue.Expand -> state.expand()
            FabLayoutValue.Hidden -> state.hide()
            FabLayoutValue.Primary -> {
                state.collapse()
                state.show()
            }
        }
    }
    LaunchedEffect(hidden) {
        if (hidden && expanded) onExpandChanged(false)
    }
    val secondaryFab = buildFab(fabBuilder)

    val density = LocalDensity.current
    val interval = remember(density) { with(density) { FabInterval.roundToPx() } }
    val padding = remember(density) { with(density) { FabPadding.roundToPx() } }

    if (updatedExpanded && !updatedHidden && autoCancel) {
        Spacer(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { onExpandChanged(false) }
            },
        )
    }

    val animatedProgress by state.expandProgress.asState()
    val appearState by state.appearProgress.asState()
    PredictiveBackHandler(updatedExpanded && !updatedHidden) { flow ->
        try {
            state.mutatorMutex.mutate(MutatePriority.UserInput) {
                flow.collect {
                    val eased = PredictiveBackEasing.transform(it.progress)
                    state.expandProgress.snapTo(1 - eased)
                }
            }
            onExpandChanged(false)
        } catch (_: CancellationException) {
            if (updatedExpanded && !updatedHidden) launch { state.expand() }
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().snackBarPadding(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (!singleAction && animatedProgress > 0f && appearState > 0f) {
            Box(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = animatedProgress },
                contentAlignment = Alignment.BottomEnd,
            ) {
                with(secondaryFab) {
                    forEachIndexed { index, (imageVector, actionLabel, autoClose, onClick) ->
                        SmallFloatingActionButton(
                            onClick = {
                                launch(Dispatchers.Default) {
                                    onClick()
                                    if (autoClose) onExpandChanged(false)
                                }
                            },
                            modifier = Modifier.semantics { actionLabel?.let { contentDescription = it } }.padding(20.dp).offset {
                                val distance = lerp(0, interval * (size - index) + padding, animatedProgress)
                                IntOffset(0, -distance)
                            },
                        ) {
                            Icon(imageVector = imageVector, contentDescription = null)
                        }
                    }
                }
            }
        }
        if (appearState > 0f) {
            val action = secondaryFab.singleOrNull().takeIf { singleAction }
            val tooltip = action?.label ?: if (updatedExpanded) closeLabel else label
            IconTooltip(tooltip) {
                FloatingActionButton(
                    onClick = {
                        if (action != null) launch { action.action() } else onExpandChanged(!updatedExpanded)
                    },
                    modifier = Modifier.padding(16.dp).graphicsLayer {
                        scaleX = appearState
                        scaleY = appearState
                        alpha = appearState
                    },
                ) {
                    AnimatedContent(targetState = updatedExpanded, label = "Quick actions") { open ->
                        Icon(
                            imageVector = action?.icon ?: if (open) Icons.Default.Close else Icons.Default.MoreHoriz,
                            contentDescription = tooltip,
                            modifier = Modifier.graphicsLayer { rotationZ = if (open) 90f * (1f - animatedProgress) else 0f },
                        )
                    }
                }
            }
        }
    }
}

internal fun buildFab(builder: FabBuilder.() -> Unit) = buildList {
    builder { icon, label, autoClose, action -> add(FabAction(icon, label, autoClose, action)) }
}

private val FabInterval = 56.dp // Small FAB height + 16 dp
private val FabPadding = 8.dp // (FAB height - small FAB height) / 2
