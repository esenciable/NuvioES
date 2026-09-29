@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Live TV: the channels your addons already publish.
 *
 * The guide is not wired in yet, so a row says "sin guía" rather than showing an empty programme slot
 * that looks like a bug. Everything else is real data.
 *
 * Focus is requested explicitly whenever the channel list changes, and every focusable element has an
 * action: the empty state opens the addon manager, a channel row shows that channel's details. An empty
 * screen with nothing focusable is a dead end on a remote-controlled device -- that is blocker A4 from
 * the audit of the reference fork, and this screen does not repeat it.
 */
@Composable
fun LiveTvScreen(
    state: LiveTvUiState,
    onBack: () -> Unit,
    onManageAddons: () -> Unit,
    onRetry: () -> Unit,
    onSelectChannel: (String) -> Unit,
    onSelectCategory: (com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId) -> Unit
) {
    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state.status) {
            LiveTvStatus.LOADING -> CenteredMessage(stringResource(R.string.live_tv_loading))

            LiveTvStatus.EMPTY -> EmptyState(
                title = stringResource(R.string.live_tv_empty_title),
                description = stringResource(R.string.live_tv_empty_desc),
                actionLabel = stringResource(R.string.live_tv_empty_action),
                onAction = onManageAddons
            )

            LiveTvStatus.ERROR -> EmptyState(
                title = stringResource(R.string.live_tv_error_title),
                description = state.errorMessage ?: stringResource(R.string.live_tv_empty_desc),
                actionLabel = stringResource(R.string.live_tv_retry),
                onAction = onRetry
            )

            LiveTvStatus.READY -> ChannelList(
                state = state,
                onSelectChannel = onSelectChannel,
                onSelectCategory = onSelectCategory
            )
        }
    }
}

@Composable
private fun ChannelList(
    state: LiveTvUiState,
    onSelectChannel: (String) -> Unit,
    onSelectCategory: (com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId) -> Unit
) {
    val listFocusRequester = remember { FocusRequester() }
    val firstKey = state.channels.firstOrNull()?.channel?.stableKey

    // Focus follows the list: entering the screen, and after a filter change leaves the selection
    // pointing at nothing, focus has to land somewhere declared rather than nowhere.
    LaunchedEffect(firstKey) {
        runCatching { listFocusRequester.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl)) {
        Text(
            text = stringResource(R.string.live_tv_channels_count, state.totalChannelCount),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                items(items = state.channels, key = { it.channel.stableKey }) { row ->
                    ChannelRow(
                        row = row,
                        selected = row.channel.stableKey == state.selectedChannelKey,
                        onClick = { onSelectChannel(row.channel.stableKey) },
                        modifier = if (row.channel.stableKey == firstKey) {
                            Modifier.focusRequester(listFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            ChannelDetails(
                row = state.channels.firstOrNull { it.channel.stableKey == state.selectedChannelKey },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ChannelRow(
    row: LiveTvChannelRow,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(12.dp)
    val background = when {
        focused -> NuvioTheme.colors.SecondaryVariant
        selected -> NuvioTheme.colors.BackgroundElevated
        else -> NuvioTheme.colors.Surface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) NuvioTheme.colors.Secondary else NuvioTheme.colors.Surface,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = row.channel.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (row.hasGuide) {
                row.now?.title ?: row.next?.title.orEmpty()
            } else {
                stringResource(R.string.live_tv_no_guide)
            },
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChannelDetails(row: LiveTvChannelRow?, modifier: Modifier = Modifier) {
    if (row == null) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.live_tv_select_channel),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Column(modifier = modifier) {
        Text(
            text = row.channel.name,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = row.channel.addonName,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        if (row.channel.genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = row.channel.genres.joinToString(separator = " · "),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        row.channel.description?.let { description ->
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!row.hasGuide) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.live_tv_no_guide),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    val actionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { actionFocusRequester.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(
                    color = NuvioTheme.colors.BackgroundElevated,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(actionFocusRequester)
            ) {
                Text(
                    text = actionLabel,
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
