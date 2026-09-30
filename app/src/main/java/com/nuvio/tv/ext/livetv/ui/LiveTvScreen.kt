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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.data.epg.EpgFailure
import com.nuvio.tv.ext.livetv.data.epg.EpgFailureReason
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.LiveTvRows
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPreview
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
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
    fullscreenRequest: LiveTvPlayRequest?,
    onBack: () -> Unit,
    onManageAddons: () -> Unit,
    onRetry: () -> Unit,
    onPlayChannel: (String) -> Unit,
    onChannelFocused: (String) -> Unit,
    onSetAdultFilter: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSetEpgSourceEnabled: (String, Boolean) -> Unit,
    onSetCategoryVisible: (LiveTvCategoryId, Boolean) -> Unit,
    onSelectCategory: (LiveTvCategoryId) -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    onExitFullscreen: () -> Unit
) {
    var showGrid by remember { mutableStateOf(false) }

    // Live playback is edge to edge, so the app's sidebar rail has to get out of the way. Verified on
    // device that it stayed drawn over the picture, which makes it "the content area enlarged" rather
    // than fullscreen. Cleared on dispose so the flag can never outlive this screen -- and cleared
    // again for the error case, where fullscreen stops being immersive in spirit.
    DisposableEffect(state.isFullscreen) {
        LiveTvImmersive.set(state.isFullscreen)
        onDispose { LiveTvImmersive.set(false) }
    }

    // One player for the whole screen. The preview and the fullscreen surface share it, so opening a
    // channel does not build a second ExoPlayer and zapping does not build one per channel.
    val liveTvPlayer = rememberLiveTvPlayer()

    // Where to send focus when the list comes back. It lives above the fullscreen branch, keyed by
    // stableKey, so switching surfaces does not throw it away -- and it follows the channel that is
    // playing, so leaving fullscreen lands on the channel the user zapped to, not on the one they left.
    var rememberedKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(fullscreenRequest?.channel?.stableKey) {
        fullscreenRequest?.let { rememberedKey = it.channel.stableKey }
    }

    // Back closes whichever panel is open and stays on the screen; that is what a panel owes the user.
    // The fullscreen surface registers its own Back handler for as long as it is shown.
    BackHandler {
        when {
            showGrid -> showGrid = false
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val request = fullscreenRequest
        if (state.isFullscreen && request != null) {
            // The programme on air comes from the same rows the list shows, so the HUD and the list
            // never disagree about what a channel is broadcasting.
            val playingRow = state.channels.firstOrNull {
                it.channel.stableKey == request.channel.stableKey
            }
            // A player of its own, and this closes the third surface bug in a row.
            //
            // Sharing one ExoPlayer between the preview and the fullscreen gave us a black screen, then a
            // ghost frame under the list, and now video that does not fill the screen. All three are the
            // same fact: an ExoPlayer renders into ONE surface, and reassigning it between two views
            // leaves the old geometry behind. A dedicated player means the fullscreen surface is the only
            // one its player ever had, so there is nothing to reassign.
            //
            // It costs nothing in decoders: the screen composes the list or the fullscreen, never both,
            // so only one of the two players is alive at any moment.
            val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val fullscreenPlayer = remember { LiveTvPlayer(appContext) }
            DisposableEffect(fullscreenPlayer) {
                onDispose { fullscreenPlayer.release() }
            }
            LiveTvFullscreenSurface(
                liveTvPlayer = fullscreenPlayer,
                request = request,
                programmeTitle = playingRow?.now?.title,
                onPrevious = onPreviousChannel,
                onNext = onNextChannel,
                onExit = onExitFullscreen
            )
        } else if (showGrid) {
            LiveTvGrid(
                rows = state.channels,
                nowEpochMs = System.currentTimeMillis(),
                onPlayChannel = onPlayChannel
            )
        } else {
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
                liveTvPlayer = liveTvPlayer,
                rememberedKey = rememberedKey,
                onRememberKey = { rememberedKey = it },
                onPlayChannel = onPlayChannel,
                onChannelFocused = onChannelFocused,
                onSelectCategory = onSelectCategory,
                onOpenGrid = { showGrid = true },
                onSearchQuery = onSearchQuery
            )
        }
        }
    }
}

@Composable
private fun ChannelList(
    state: LiveTvUiState,
    liveTvPlayer: LiveTvPlayer,
    rememberedKey: String?,
    onRememberKey: (String) -> Unit,
    onPlayChannel: (String) -> Unit,
    onChannelFocused: (String) -> Unit,
    onSelectCategory: (LiveTvCategoryId) -> Unit,
    onSearchQuery: (String) -> Unit,
    onOpenGrid: () -> Unit
) {
    val listState = rememberLazyListState()
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    // The chip requesters are keyed by category key, like the row requesters are keyed by stableKey:
    // position would attach the active chip's requester to a different category after a settings change.
    val categoryRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val activeCategoryRequester = categoryRequesters.getOrPut(state.selectedCategory.preferenceKey) {
        FocusRequester()
    }
    val firstKey = state.channels.firstOrNull()?.channel?.stableKey
    // UP from a category chip lands here. Without it the search field was rendered but unreachable,
    // because the chips had no UP and focus search had nowhere to go.
    val searchRequester = remember { FocusRequester() }
    // Whether the category slider is the thing that holds focus right now.
    var chipFocused by remember { mutableStateOf(false) }
    var focusedKey by remember { mutableStateOf<String?>(null) }

    // Focus goes back to WHERE THE USER WAS, not to the top.
    //
    // Two details make this work, and both are the difference between "looks right" and "is right":
    //   1. Requesters are keyed by stableKey, never by list position. The reference fork keyed them by
    //      index while items were keyed by channel, so after any filter a surviving row reused the
    //      requester of a channel that had already been disposed.
    //   2. The list scrolls to the remembered row BEFORE asking for focus, because a row that is not
    //      composed has no focus node to receive it.
    LaunchedEffect(Unit) {
        val target = rememberedKey?.takeIf { key -> state.channels.any { it.channel.stableKey == key } }
            ?: firstKey
        focusedKey = target
        val index = state.channels.indexOfFirst { it.channel.stableKey == target }
        if (index > 0) runCatching { listState.scrollToItem(index) }
        runCatching { requesters[target]?.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NuvioTheme.spacing.xl)
            // UP is handled at the CONTAINER, the way upstream's search does it, because nothing applied
            // to the chips themselves reaches their focus node -- FilterChip owns it. A root preview sees
            // every key before any child can consume it, so this does not depend on FilterChip's internals.
            .onPreviewKeyEvent { event ->
                val isUp = event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                if (isUp) {
                    android.util.Log.i("LiveTvSearch", "UP at container: chipFocused=$chipFocused")
                }
                if (!isUp || !chipFocused) {
                    false
                } else {
                    // Consume only if focus actually moved; swallowing the key after a failed request
                    // traps the D-pad, which is the shape of the fork's unpressable buttons.
                    val moved = runCatching { searchRequester.requestFocus() }
                    android.util.Log.i(
                        "LiveTvSearch",
                        "requestFocus on search field: moved=${moved.isSuccess} err=${moved.exceptionOrNull()}"
                    )
                    moved.isSuccess
                }
            }
    ) {
        // No settings button here any more: the feature's settings are a category of the app's own
        // Settings screen, which is where a user looks for settings. Two entries to the same toggles is
        // the duplication the owner reported -- a "Settings" button next to "Guide" and the rail's gear.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.live_tv_channels_count, state.channels.size),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            ChannelSearchField(
                query = state.searchQuery,
                onQueryChange = onSearchQuery,
                focusRequester = searchRequester
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = onOpenGrid,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Surface,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(
                    text = stringResource(R.string.live_tv_view_grid),
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Say the filter is on, and say what it actually is. A household that believes a keyword list is
        // a guarantee is worse off than one that was told.
        if (state.adultFilterActive) {
            Text(
                text = stringResource(R.string.live_tv_adult_filter_on, state.hiddenChannelCount),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }

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

        // The category slider sits directly above the list, so UP from the first channel row reaches it
        // and DOWN from a chip goes back into the list. It is never given entry focus: the list keeps
        // that, because watching a channel is what the user came for.
        LiveTvCategorySlider(
            categories = LiveTvRows.visibleCategoriesFor(state.categories, state.hiddenCategoryIds),
            selectedCategory = state.selectedCategory,
            onSelectCategory = onSelectCategory,
            requesters = categoryRequesters,
            upTarget = searchRequester,
            onChipFocused = { chipFocused = true; android.util.Log.i("LiveTvSearch", "chip focused") }
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                // "No results" is NOT "no channels": an empty set after a search is a different
                // situation from an addon with nothing to show, and the reference fork rendered both as
                // the same blank screen. The field stays visible either way, or the user could not clear
                // the query that emptied the list.
                if (state.channels.isEmpty() && state.searchQuery.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.live_tv_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(NuvioTheme.spacing.md)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                    ) {
                        items(items = state.channels, key = { it.channel.stableKey }) { row ->
                            val requester = requesters.getOrPut(row.channel.stableKey) { FocusRequester() }
                            ChannelRow(
                                row = row,
                                resolving = state.resolvingChannelKey == row.channel.stableKey,
                                onClick = { onPlayChannel(row.channel.stableKey) },
                                onFocused = {
                                    chipFocused = false
                                    focusedKey = row.channel.stableKey
                                    onRememberKey(row.channel.stableKey)
                                    onChannelFocused(row.channel.stableKey)
                                },
                                // UP from the first row goes to the chip that is actually selected, not to
                                // whichever chip happens to sit above the list's left edge.
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .focusProperties {
                                        if (row.channel.stableKey == firstKey) up = activeCategoryRequester
                                    }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))

            ChannelDetails(
                row = state.channels.firstOrNull { it.channel.stableKey == focusedKey },
                preview = state.preview,
                previewFailure = state.previewFailure,
                liveTvPlayer = liveTvPlayer,
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
    liveTvPlayer: LiveTvPlayer,
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
                liveTvPlayer = liveTvPlayer,
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

/**
 * The channel search, in two states, and that is the whole design.
 *
 * **Not editing**: a navigable box that looks like a field and does **not** open the keyboard when it
 * gains focus. Opening it on focus would make D-pad navigation unusable -- every pass through the header
 * would summon the IME. The keyboard appears only when the user asks for it, with OK.
 *
 * **Editing**: the real field, focused, IME up. Back leaves editing without leaving the screen.
 *
 * Copied from the reference fork, which solved this exact problem on this exact platform. The retries on
 * focus and on `show()` are not superstition: the IME takes a moment to exist, and single-shot versions
 * of this are why on-screen keyboards "sometimes" do not appear.
 */
@Composable
private fun ChannelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester
) {
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(query) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }

    // Leaving the screen must not leave the keyboard behind.
    DisposableEffect(Unit) { onDispose { keyboardController?.hide() } }

    BackHandler(enabled = isEditing) {
        isEditing = false
        keyboardController?.hide()
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            repeat(5) {
                kotlinx.coroutines.yield()
                runCatching { fieldFocus.requestFocus() }
                keyboardController?.show()
            }
        } else {
            keyboardController?.hide()
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            onQueryChange(updated)
        },
        // ONE field, always composed, with readOnly as the switch, and that replaces a two-state design
        // that could not work.
        //
        // The old version swapped a clickable Box for this field when the user pressed OK. Swapping
        // DESTROYS the focused node, so Compose hands focus to the nearest focusable -- the category chip
        // -- and the field's retries never win that race. The log proved it: requestFocus succeeded, and
        // one frame later the chip had focus again and the keyboard never opened.
        //
        // readOnly gets both halves: the node is never destroyed, so focus cannot be orphaned, and a
        // read-only field does not summon the IME, so passing through the header with the D-pad stays
        // quiet. Pressing OK flips it and the keyboard comes up.
        //
        // Upstream's search always composes its field for the same reason; the swap was my addition.
        readOnly = !isEditing,
        singleLine = true,
        // The field's own text style, which the placeholder inherits. The material3 default here rendered
        // the hint so large that its first and last letters were clipped by the pill's bezel -- it looked
        // like a heading that had escaped its box, not a search hint.
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
        // Pill shaped, like the Guide button next to it. The default OutlinedTextField shape is almost
        // square, and next to a pill it read as a different kind of control from a different app.
        shape = RoundedCornerShape(50),
        // Explicit colours, because the material3 defaults are dark-on-dark here: the text was rendering
        // in a colour meant for a light surface, and the search box is the one field whose contents the
        // user has to read back.
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedTextColor = NuvioTheme.colors.TextPrimary,
            unfocusedTextColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
            unfocusedContainerColor = NuvioTheme.colors.BackgroundElevated,
            cursorColor = NuvioTheme.colors.Secondary,
            focusedIndicatorColor = NuvioTheme.colors.Secondary,
            unfocusedIndicatorColor = NuvioTheme.colors.Border,
            focusedPlaceholderColor = NuvioTheme.colors.TextSecondary,
            unfocusedPlaceholderColor = NuvioTheme.colors.TextSecondary
        ),
        // The style is set on the placeholder Text itself, not only on the field: a bare Text inside the
        // placeholder lambda does not inherit the field's textStyle, which is why the hint stayed huge and
        // clipped after the first attempt.
        placeholder = {
            Text(
                text = stringResource(R.string.live_tv_search_hint),
                style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
        },
        keyboardOptions = KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search
        ),
        modifier = Modifier
            .width(SEARCH_FIELD_WIDTH)
            .height(HEADER_CONTROL_HEIGHT)
            .focusRequester(focusRequester)
            .onFocusChanged { focus ->
                if (!focus.isFocused && isEditing) {
                    isEditing = false
                    keyboardController?.hide()
                }
            }
            .onPreviewKeyEvent { event ->
                val isConfirm = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                if (!isEditing && isConfirm) {
                    isEditing = true
                    true
                } else {
                    false
                }
            }
    )

}

private val SEARCH_FIELD_WIDTH = 260.dp

/**
 * The search field is shortened to the height Guide already had, not the other way round: the buttons
 * are the house control and were fine, so the field is what matches them. An OutlinedTextField defaults
 * to 56dp, which stood noticeably taller than the button beside it.
 */
private val HEADER_CONTROL_HEIGHT = 44.dp
private val SEARCH_FIELD_HEIGHT = 40.dp
