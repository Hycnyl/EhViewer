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
fun AnimatedVisibilityScope.AppearanceScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val hasSignedIn by Settings.hasSignedIn.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_appearance)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(paddingValues)) {
            PreferenceSection(R.string.settings_theme)
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.dark_theme),
                entry = com.hippo.ehviewer.R.array.night_mode_entries,
                entryValueRes = com.hippo.ehviewer.R.array.night_mode_values,
                state = Settings.theme.asMutableState(),
            )
            PalettePreference()
            SwitchPreference(
                title = stringResource(id = R.string.black_dark_theme),
                state = Settings.blackDarkTheme.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.harmonize_category_color),
                state = Settings.harmonizeCategoryColor.asMutableState(),
            )
            PreferenceSection(R.string.settings_display)
            val listMode = Settings.listMode.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_eh_list_mode),
                entry = com.hippo.ehviewer.R.array.list_mode_entries,
                entryValueRes = com.hippo.ehviewer.R.array.list_mode_entry_values,
                state = listMode,
            )
            DependentPreferences(visible = listMode.value == 0) {
                Column {
                    IntSliderPreference(
                        maxValue = 60,
                        minValue = 20,
                        step = 7,
                        title = stringResource(id = R.string.list_tile_thumb_size),
                        state = Settings.listThumbSize.asMutableState(),
                    )
                    SimpleMenuPreferenceInt(
                        title = stringResource(id = R.string.settings_eh_detail_size),
                        entry = com.hippo.ehviewer.R.array.detail_size_entries,
                        entryValueRes = com.hippo.ehviewer.R.array.detail_size_entry_values,
                        state = Settings.detailSize.asMutableState(),
                    )
                }
            }
            IntSliderPreference(
                maxValue = 10,
                minValue = 1,
                title = stringResource(id = R.string.settings_eh_thumb_columns),
                state = Settings.thumbColumns.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_show_gallery_pages),
                summary = stringResource(id = R.string.settings_eh_show_gallery_pages_summary),
                state = Settings.showGalleryPages.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_show_reading_progress),
                summary = stringResource(id = R.string.settings_eh_show_reading_progress_summary),
                state = Settings.showReadingProgress.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_show_vote_status),
                state = Settings.showVoteStatus.asMutableState(),
            )
            val showComments = Settings.showComments.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_eh_show_gallery_comments),
                summary = stringResource(id = R.string.settings_eh_show_gallery_comments_summary),
                state = showComments,
            )
            DependentPreferences(visible = showComments.value) {
                IntSliderPreference(
                    maxValue = 100,
                    minValue = -101,
                    title = stringResource(id = R.string.settings_eh_show_gallery_comment_threshold),
                    summary = stringResource(id = R.string.settings_eh_show_gallery_comment_threshold_summary),
                    state = Settings.commentThreshold.asMutableState(),
                )
            }
            if (EhTagDatabase.translatable) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_eh_show_tag_translations),
                    summary = stringResource(id = R.string.settings_eh_show_tag_translations_summary),
                    state = Settings.showTagTranslations.asMutableState(),
                )
                UrlPreference(
                    title = stringResource(id = R.string.settings_eh_tag_translations_source),
                    url = stringResource(id = R.string.settings_eh_tag_translations_source_url),
                )
            }
            if (hasSignedIn) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_eh_show_jpn_title),
                    summary = stringResource(id = R.string.settings_eh_show_jpn_title_summary),
                    state = Settings.showJpnTitle.asMutableState(),
                )
            }
            PreferenceSection(R.string.settings_interaction)
            SwitchPreference(
                title = stringResource(id = R.string.animate_items),
                summary = stringResource(id = R.string.animate_items_summary),
                state = Settings.animateItems.asMutableState(),
            )
            PreferenceSection(R.string.settings_language)
            var currentLanguage by remember { mutableStateOf(getAppLanguage()) }
            val languages = remember { getLanguages() }
            DropdownListPreference(
                value = currentLanguage,
                onValueChange = {
                    setAppLanguage(it)
                    currentLanguage = it
                },
                items = languages,
                title = { Text(stringResource(id = R.string.settings_advanced_app_language_title)) },
                summary = { Text(languages[currentLanguage].orEmpty()) },
            )
        }
    }
}
