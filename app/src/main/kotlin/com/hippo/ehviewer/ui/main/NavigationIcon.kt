package com.hippo.ehviewer.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.TooltipIconButton
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@Composable
context(navigator: DestinationsNavigator)
fun NavigationIcon() = TooltipIconButton(label = stringResource(R.string.back), onClick = { navigator.popBackStack() }, shapes = IconButtonDefaults.shapes()) {
    Icon(imageVector = Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
}
