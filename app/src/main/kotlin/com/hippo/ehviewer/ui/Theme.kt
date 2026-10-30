package com.hippo.ehviewer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import com.ehviewer.core.ui.util.ProvideVectorPainterCache
import com.hippo.ehviewer.ui.theme.EhTheme
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.LocalGlobalDialogState
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.preferenceTheme
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance

inline fun ComponentActivity.setMD3Content(crossinline content: @Composable DialogState.() -> Unit) = setContent {
    EhTheme(useDarkTheme = isSystemInDarkTheme()) {
        val theme = preferenceTheme(
            iconColor = MaterialTheme.colorScheme.primary,
            titleTextStyle = MaterialTheme.typography.titleMedium,
        )
        ProvidePreferenceTheme(theme) {
            ProvideVectorPainterCache {
                val dialogState = remember { DialogState() }
                CompositionLocalProvider(LocalGlobalDialogState provides dialogState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(dialogState)
                        dialogState.Place()
                    }
                }
            }
        }
    }
}

private val primaryRoutes = setOf("home_page_screen", "whatshot_screen", "toplist_screen", "subscription_screen", "favourites_screen", "downloads_screen", "history_screen")

private fun Ty.isSiblingNavigation() = initialState.destination.route in primaryRoutes && targetState.destination.route in primaryRoutes

private typealias Ty = AnimatedContentTransitionScope<NavBackStackEntry>

@Composable
fun rememberEhNavAnim(): NavHostAnimatedDestinationStyle {
    val slideDistance = rememberSlideDistance()
    return remember(slideDistance) {
        object : NavHostAnimatedDestinationStyle() {
            override val enterTransition: Ty.() -> EnterTransition = { if (isSiblingNavigation()) fadeIn(tween(180, delayMillis = 60)) else materialSharedAxisXIn(true, slideDistance) }
            override val exitTransition: Ty.() -> ExitTransition = { if (isSiblingNavigation()) fadeOut(tween(90)) else materialSharedAxisXOut(true, slideDistance) }
            override val popEnterTransition: Ty.() -> EnterTransition = { if (isSiblingNavigation()) fadeIn(tween(180, delayMillis = 60)) else materialSharedAxisXIn(false, slideDistance) }
            override val popExitTransition: Ty.() -> ExitTransition = { if (isSiblingNavigation()) fadeOut(tween(90)) else materialSharedAxisXOut(false, slideDistance) }
        }
    }
}
