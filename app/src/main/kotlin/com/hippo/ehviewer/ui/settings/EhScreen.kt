package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.ui.component.TooltipIconButton
import com.ehviewer.core.util.isAtLeastT
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FilterScreenDestination
import com.hippo.ehviewer.ui.destinations.MyTagsScreenDestination
import com.hippo.ehviewer.ui.destinations.UConfigScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.ui.tools.awaitSelectTime
import com.hippo.ehviewer.util.copyTextToClipboard
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.datetime.LocalTime
import moe.tarsin.navigate
import moe.tarsin.snackbar

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.EhScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackBar(content: String) = launch { snackbar(content) }
    val hasSignedIn by Settings.hasSignedIn.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_account_site)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val copiedToClipboard = stringResource(id = R.string.copied_to_clipboard)
        Column(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(paddingValues),
        ) {
            if (hasSignedIn) {
                val displayName by Settings.displayName.collectAsState()
                Preference(
                    title = stringResource(id = R.string.account_name),
                    summary = displayName,
                ) {
                    launch {
                        val cookies = EhCookieStore.getIdentityCookies()
                        awaitConfirmationOrCancel(
                            confirmText = R.string.settings_eh_sign_out,
                            dismissText = R.string.settings_eh_clear_igneous,
                            showCancelButton = cookies.last().second != null,
                            onCancelButtonClick = { EhCookieStore.clearIgneous() },
                            secure = true,
                        ) {
                            Column {
                                val warning = stringResource(id = R.string.settings_eh_identity_cookies_signed)
                                val state = rememberTextFieldState(cookies.joinToString("\n") { (k, v) -> "$k: $v" })
                                Text(text = AnnotatedString.fromHtml(warning))
                                Spacer(modifier = Modifier.size(dimensionResource(id = com.hippo.ehviewer.R.dimen.keyline_margin)))
                                OutlinedTextField(
                                    state = state,
                                    readOnly = true,
                                    trailingIcon = {
                                        TooltipIconButton(
                                            label = stringResource(R.string.copy_text),
                                            onClick = {
                                                copyTextToClipboard(state.text, true)
                                                // Avoid double notify user since system have done that on Tiramisu above
                                                if (!isAtLeastT) launchSnackBar(copiedToClipboard)
                                            },
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                        EhUtils.signOut()
                    }
                }
                val gallerySite = Settings.gallerySite.asMutableState()
                SimpleMenuPreferenceInt(
                    title = stringResource(id = R.string.settings_eh_gallery_site),
                    entry = com.hippo.ehviewer.R.array.gallery_site_entries,
                    entryValueRes = com.hippo.ehviewer.R.array.gallery_site_entry_values,
                    state = gallerySite,
                )
                Preference(
                    title = stringResource(id = R.string.settings_u_config),
                    summary = stringResource(id = R.string.settings_u_config_summary),
                ) { navigate(UConfigScreenDestination) }
                Preference(
                    title = stringResource(id = R.string.settings_my_tags),
                    summary = stringResource(id = R.string.settings_my_tags_summary),
                ) { navigate(MyTagsScreenDestination) }
            } else {
                Preference(title = stringResource(R.string.sign_in)) { EhUtils.signOut() }
            }
        }
    }
}
