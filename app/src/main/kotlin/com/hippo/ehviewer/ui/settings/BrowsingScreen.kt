package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FilterScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.ui.tools.awaitSelectTime
import com.hippo.ehviewer.util.getAppLanguage
import com.hippo.ehviewer.util.getLanguages
import com.hippo.ehviewer.util.setAppLanguage
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.datetime.LocalTime
import me.zhanghai.compose.preference.DropdownListPreference
import moe.tarsin.navigate

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.BrowsingScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hasSignedIn by Settings.hasSignedIn.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_browsing)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(paddingValues)) {
            var defaultFavSlot by Settings.defaultFavSlot.asMutableState()
            val disabled = stringResource(id = R.string.disabled_nav)
            val localFav = stringResource(id = R.string.local_favorites)
            val summary = when (defaultFavSlot) {
                -1 -> localFav
                in 0..9 -> Settings.favCat[defaultFavSlot]
                else -> stringResource(id = R.string.default_favorites_warning)
            }
            Preference(
                title = stringResource(id = R.string.default_favorites_collection),
                summary = summary,
            ) {
                launch {
                    val items = buildList {
                        add(disabled)
                        add(localFav)
                        if (hasSignedIn) {
                            addAll(Settings.favCat)
                        }
                    }
                    defaultFavSlot = awaitSelectItem(
                        items = items,
                        title = R.string.default_favorites_collection,
                        selected = defaultFavSlot + 2,
                    ) - 2
                }
            }
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_eh_launch_page),
                entry = com.hippo.ehviewer.R.array.launch_page_entries,
                entryValueRes = com.hippo.ehviewer.R.array.launch_page_entry_values,
                state = Settings.launchPage.asMutableState(),
            )
            Preference(
                title = stringResource(id = R.string.settings_eh_filter),
                summary = stringResource(id = R.string.settings_eh_filter_summary),
            ) { navigate(FilterScreenDestination) }
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_metered_network_warning),
                state = Settings.meteredNetworkWarning.asMutableState(),
            )
            if (hasSignedIn) {
                val reqNews = Settings.requestNews.asMutableState()
                SwitchPreference(
                    title = stringResource(id = R.string.settings_eh_request_news),
                    state = reqNews,
                )
                DependentPreferences(visible = reqNews.value) {
                    val pickerTitle = stringResource(id = R.string.settings_eh_request_news_timepicker)
                    Preference(title = pickerTitle) {
                        launch {
                            val time = LocalTime.fromSecondOfDay(Settings.requestNewsTime)
                            val (hour, minute) = awaitSelectTime(pickerTitle, time.hour, time.minute)
                            Settings.requestNewsTime = LocalTime(hour, minute).toSecondOfDay()
                        }
                    }
                }
                SwitchPreference(
                    title = stringResource(id = R.string.settings_eh_hide_hv_events),
                    state = Settings.hideHvEvents.asMutableState(),
                )
            }
        }
    }
}
