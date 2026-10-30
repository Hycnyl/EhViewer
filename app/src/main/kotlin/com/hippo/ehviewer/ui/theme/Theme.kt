package com.hippo.ehviewer.ui.theme

import android.app.WallpaperManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.scrollbar.LocalScrollbarStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ehviewer.core.ui.component.scrollbarStyle
import com.ehviewer.core.util.isAtLeastOMR1
import com.ehviewer.core.util.isAtLeastS
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.materialkolor.dynamicColorScheme

fun ColorScheme.amoled(amoled: Boolean) = if (amoled) {
    copy(
        surface = Color.Black,
        background = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF24282B),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0C1012),
        surfaceContainer = Color(0xFF14191C),
        surfaceContainerHigh = Color(0xFF1C2225),
        surfaceContainerHighest = Color(0xFF242B2F),
    )
} else {
    this
}

@Composable
fun EhTheme(useDarkTheme: Boolean, content: @Composable () -> Unit) {
    val amoled by Settings.blackDarkTheme.collectAsState()
    val paletteMode by Settings.paletteMode.collectAsState()
    val colors = ehColorScheme(useDarkTheme, paletteMode, amoled)

    MaterialTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
        val scrollbarStyle = scrollbarStyle(color = MaterialTheme.colorScheme.primary)
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalScrollbarStyle provides scrollbarStyle,
            content = content,
        )
    }
}

@Composable
fun ehColorScheme(useDarkTheme: Boolean, paletteMode: Int, amoled: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (paletteMode == 1) {
        if (useDarkTheme) ComfortDarkColors.amoled(amoled) else ComfortLightColors
    } else if (isAtLeastS) {
        if (useDarkTheme) {
            dynamicDarkColorScheme(context).amoled(amoled)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        val color = if (isAtLeastOMR1) extractWallPaperPalette() else null
        if (color != null) {
            dynamicColorScheme(
                primary = color.first,
                isDark = useDarkTheme,
                isAmoled = useDarkTheme && amoled,
                secondary = color.second,
                tertiary = color.third,
            ).amoled(useDarkTheme && amoled)
        } else {
            if (useDarkTheme) {
                darkColorScheme().amoled(amoled)
            } else {
                expressiveLightColorScheme()
            }
        }
    }
}

@Composable
fun Color.scrim() = copy(alpha = if (isSystemInDarkTheme()) 0.5f else 0.9f)

typealias WallPaperPalette = Triple<Color, Color?, Color?>

@Composable
@RequiresApi(Build.VERSION_CODES.O_MR1)
fun extractWallPaperPalette(): WallPaperPalette? {
    val colors = WallpaperManager.getInstance(LocalContext.current)?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
    val primary = colors.primaryColor.toArgb().let { Color(it) }
    val secondary = colors.secondaryColor?.toArgb()?.let { Color(it) }
    val tertiary = colors.tertiaryColor?.toArgb()?.let { Color(it) }
    return WallPaperPalette(primary, secondary, tertiary)
}
