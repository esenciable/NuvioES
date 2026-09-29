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
import androidx.compose.foundation.layout.aspectRatio
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
import com.nuvio.tv.ext.livetv.data.epg.EpgFailure
import com.nuvio.tv.ext.livetv.data.epg.EpgFailureReason
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPreview
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Live TV: the channels your addons already publish, with their programming.
 *
 * The details side follows **focus** rather than a confirmed selection, which is what makes browsing a
 * list on a remote pleasant: moving down a row tells you who that channel is. OK is reserved for the one
 * thing that costs something -- resolving a stream and opening the player.
 *
 * Focus is requested explicitly whenever the channel list changes, and every focusable element has an
 * action. An empty screen with nothing focusable is a dead end on a remote-controlled device; that is
 * blocker A4 from the audit of the reference fork, and this screen does not repeat it.
 */
@Composable
fun LiveTvScreen(
    state: LiveTvUiState,
    onBack: () -> Unit,
    onManageAddons: () -> Unit,
    onRetry: () -> Unit,
    onPlayChannel: (String) -> Unit,
    onChannelFocused: (String) -> Unit
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
                onPlayChannel = onPlayChannel,
                onChannelFocused = onChannelFocused
            )
        }
    }
}

@Composable
private fun ChannelList(
    state: LiveTvUiState,
    onPlayChannel: (String) -> Unit,
    onChannelFocused: (String) -> Unit
) {
    val listFocusRequester = remember { FocusRequester() }
    val firstKey = state.channels.firstOrNull()?.channel?.stableKey
    var focusedKey by remember { mutableStateOf<String?>(null) }

    // Focus follows the list: entering the screen, and after a filter change leaves the selection
    // pointing at nothing, focus has to land somewhere declared rather than nowhere.
    LaunchedEffect(firstKey) {
        focusedKey = firstKey
        runCatching { listFocusRequester.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl)) {
        Text(
            text = stringResource(R.string.live_tv_channels_count, state.totalChannelCount),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        // The guide's state is stated rather than implied. "Downloaded but carries no programming for
        // these channels" and "the download failed" look identical on a row, and the difference is the
        // whole diagnosis.
        val guideNote = when {
            state.guideFailure != null -> guideFailureText(state.guideFailure)
            state.guideLoaded && state.guideProgrammeCount == 0 ->
                stringResource(R.string.live_tv_guide_no_programmes)
            state.guideProgrammeCount > 0 ->
                stringResource(R.string.live_tv_guide_programmes, state.guideProgrammeCount)
            else -> null
        }
        guideNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        state.playFailure?.let { failure ->
            Text(
                text = when (failure) {
                    LiveTvPlayFailure.NO_STREAMS -> stringResource(R.string.live_tv_play_failed_no_streams)
                    LiveTvPlayFailure.RESOLVE_FAILED -> stringResource(R.string.live_tv_play_failed_resolve)
                },
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                items(items = state.channels, key = { it.channel.stableKey }) { row ->
                    val isFirst = row.channel.stableKey == firstKey
                    ChannelRow(
                        row = row,
                        resolving = state.resolvingChannelKey == row.channel.stableKey,
                        onClick = { onPlayChannel(row.channel.stableKey) },
                        onFocused = {
                            focusedKey = row.channel.stableKey
                            onChannelFocused(row.channel.stableKey)
                        },
                        modifier = if (isFirst) Modifier.focusRequester(listFocusRequester) else Modifier
                    )
                }
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            ChannelDetails(
                row = state.channels.firstOrNull { it.channel.stableKey == focusedKey },
                preview = state.preview,
                previewFailure = state.previewFailure,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ChannelRow(
    row: LiveTvChannelRow,
    resolving: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(12.dp)
    val background = when {
        focused -> NuvioTheme.colors.SecondaryVariant
        else -> NuvioTheme.colors.Surface
    }
    val subtitle = when {
        resolving -> stringResource(R.string.live_tv_resolving)
        row.hasGuide -> row.now?.title ?: row.next?.title.orEmpty()
        else -> stringResource(R.string.live_tv_no_guide)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
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
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChannelDetails(
    row: LiveTvChannelRow?,
    preview: LiveTvPreview?,
    previewFailure: LiveTvPlayFailure?,
    modifier: Modifier = Modifier
) {
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

        // The split-screen preview: what this channel is broadcasting right now. Muted, and built by
        // the composition, so it only exists while a channel with a resolved stream is on screen.
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        if (preview != null && preview.channelKey == row.channel.stableKey) {
            LiveTvPreviewSurface(
                url = preview.url,
                headers = preview.headers,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NuvioTheme.colors.BackgroundElevated)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NuvioTheme.colors.BackgroundElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (previewFailure) {
                        LiveTvPlayFailure.NO_STREAMS -> stringResource(R.string.live_tv_play_failed_no_streams)
                        LiveTvPlayFailure.RESOLVE_FAILED -> stringResource(R.string.live_tv_play_failed_resolve)
                        null -> stringResource(R.string.live_tv_preview_loading)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        // The programming is the point of the guide being there at all, so it gets the prominent slot.
        row.now?.let { programme ->
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = programme.title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            programme.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        row.next?.let { next ->
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = stringResource(R.string.live_tv_up_next, next.title),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
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

        row.channel.description?.let { description ->
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun guideFailureText(failure: EpgFailure): String {
    val reason = stringResource(
        when (failure.reason) {
            EpgFailureReason.DOWNLOAD_FAILED -> R.string.live_tv_guide_reason_download
            EpgFailureReason.TOO_LARGE -> R.string.live_tv_guide_reason_too_large
            EpgFailureReason.MALFORMED -> R.string.live_tv_guide_reason_malformed
            EpgFailureReason.NOTHING_PARSED -> R.string.live_tv_guide_reason_empty
        }
    )
    return if (failure.sourceName.isBlank()) {
        reason
    } else {
        stringResource(R.string.live_tv_guide_failed, failure.sourceName, reason)
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
