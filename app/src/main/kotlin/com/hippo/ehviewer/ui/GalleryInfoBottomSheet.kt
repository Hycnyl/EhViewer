package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo.Companion.LOCAL_FAVORITED
import com.ehviewer.core.ui.component.TooltipIconButton
import com.ehviewer.core.util.isAtLeastT
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.ui.screen.navWithUrl
import com.hippo.ehviewer.util.addTextToClipboard
import com.hippo.ehviewer.util.copyTextToClipboard
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch

private const val INDEX_URL = 2
private const val INDEX_PARENT = 9

private fun GalleryDetail.content() = arrayOf(
    R.string.key_gid to "$gid",
    R.string.key_token to token,
    R.string.key_url to EhUrl.getGalleryDetailUrl(gid, token),
    R.string.key_title to title,
    R.string.key_title_jpn to titleJpn,
    R.string.key_thumb to thumbUrl,
    R.string.key_category to EhUtils.getCategory(category),
    R.string.key_uploader to uploader,
    R.string.key_posted to posted,
    R.string.key_parent to parent,
    R.string.key_visible to visible,
    R.string.key_language to language,
    R.string.key_pages to pages.toString(),
    R.string.key_size to size,
    R.string.key_favorite_count to favoriteCount.toString(),
    R.string.key_favorited to (favoriteSlot > LOCAL_FAVORITED).toString(),
    R.string.key_rating_count to ratingCount.toString(),
    R.string.key_rating to rating.toString(),
    R.string.key_torrents to torrentCount.toString(),
    R.string.key_torrent_url to EhUrl.getTorrentUrl(gid, token),
    R.string.favorite_name to favoriteName,
)

@Composable
context(_: Context, _: DestinationsNavigator)
fun GalleryInfoBottomSheet(detail: GalleryDetail, onClose: () -> Unit) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied_to_clipboard)
    val data = remember(detail) { detail.content() }
    val groups = remember {
        listOf(
            R.string.gallery_info_identity to listOf(0, 1, 2, 5),
            R.string.gallery_info_titles to listOf(3, 4),
            R.string.gallery_info_publication to listOf(6, 7, 8, 9, 10, 11),
            R.string.gallery_info_statistics to (12..20).toList(),
        )
    }
    Box(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gallery_info), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TooltipIconButton(label = stringResource(R.string.close), onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp) + WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (title, fields) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(title), Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    fields.forEach { index ->
                        item(key = index, span = { GridItemSpan(if (index in listOf(2, 3, 4, 5, 9, 19)) maxLineSpan else 1) }) {
                            val (key, content) = data[index]
                            val copyLabel = stringResource(R.string.copy_text)
                            val isLink = index == INDEX_PARENT && content != null
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                                Row(
                                    Modifier.fillMaxWidth().clickable(onClickLabel = if (isLink) stringResource(R.string.open_link) else copyLabel) {
                                        if (isLink) {
                                            navWithUrl(content)
                                        } else {
                                            copyTextToClipboard(content, false)
                                            if (!isAtLeastT) scope.launch { snackbarHost.showSnackbar(copied) }
                                            if (index == INDEX_URL) Settings.clipboardTextHashCode = data[index].hashCode()
                                        }
                                    }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(content.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Icon(
                                        if (isLink) Icons.AutoMirrored.Default.OpenInNew else Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp).padding(start = 4.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}
