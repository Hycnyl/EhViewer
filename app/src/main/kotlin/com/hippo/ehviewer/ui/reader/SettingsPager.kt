package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.TooltipIconButton
import kotlinx.coroutines.launch

private val tabs = intArrayOf(
    R.string.pref_category_reading_mode,
    R.string.pref_category_general,
    R.string.custom_filter,
)

@Composable
fun SettingsPager(isWebtoon: Boolean, modifier: Modifier = Modifier, onClose: () -> Unit, onPageSelected: (Int) -> Unit) {
    val pagerState = rememberPagerState { tabs.size }
    LaunchedEffect(onPageSelected) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            onPageSelected(page)
        }
    }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TooltipIconButton(label = stringResource(R.string.close), onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
    PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = BottomSheetDefaults.ContainerColor,
    ) {
        tabs.forEachIndexed { index, res ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(text = stringResource(id = res)) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    HorizontalPager(
        modifier = modifier,
        state = pagerState,
        verticalAlignment = Alignment.Top,
    ) { page ->
        ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
            when (page) {
                0 -> ReaderModeSetting(isWebtoon)
                1 -> ReaderGeneralSetting()
                2 -> ColorFilterSetting()
            }
        }
    }
}
