@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.LiveTvKickoff
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Partidos screen: the matches grid as a section of its own, plus this feature's fullscreen
 * playback surface on top of it.
 *
 * ### Structure
 *
 * Full-bleed like playback, not padded like a panel: the grid IS the content of the section. When a
 * match resolves, [LiveTvFullscreenSurface] takes over the whole box -- the same surface the channels
 * screen uses, fed by the SAME activity-scoped view model, with an EMPTY channel list because a match
 * is not in the channel partition. The zapping keys and the channel drawer are gated behind
 * `channels.isNotEmpty()` inside the surface for exactly that reason.
 *
 * [LiveTvImmersive] is only for fullscreen playback -- the grid itself is a normal destination, whose
 * full-bleed look comes from being the whole content of the route. While the section is composed the
 * display is pinned on with `keepScreenOn`, the same idle-timer argument the channels screen documents.
 */
@Composable
internal fun LiveTvMatchesScreen(
    state: LiveTvUiState,
    fullscreenRequest: LiveTvPlayRequest?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlayMatch: (String) -> Unit,
    onPickSource: (Int) -> Unit,
    onDismissSourcePicker: () -> Unit,
    onAdvanceSource: (LiveTvPlayRequest) -> Unit,
    onRetryChannel: (String) -> Unit,
    onExitFullscreen: () -> Unit
) {
    // Immersive only while the fullscreen surface plays; the grid keeps the system chrome as-is.
    DisposableEffect(state.isFullscreen) {
        LiveTvImmersive.set(state.isFullscreen)
        onDispose { LiveTvImmersive.set(false) }
    }

    // The display stays on while the section is composed, for the same reason the channels screen
    // documents: nothing about a live broadcast generates input events, so the OS idle timer would
    // dim the screen on an event that is playing perfectly.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Back leaves the section -- a normal destination's normal contract. The fullscreen surface and
    // the source picker register their own handlers while composed, and being composed after this
    // one, they win: Back exits playback or dismisses the picker before it ever leaves the section.
    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        val request = fullscreenRequest
        if (state.isFullscreen && request != null) {
            // A player of its own, built and released with the fullscreen branch -- the same decision
            // the channels screen documents: an ExoPlayer renders into ONE surface, so the fullscreen
            // surface must be the only one its player ever had.
            val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val fullscreenPlayer = remember { LiveTvPlayer(appContext) }
            DisposableEffect(fullscreenPlayer) {
                onDispose { fullscreenPlayer.release() }
            }
            LiveTvFullscreenSurface(
                liveTvPlayer = fullscreenPlayer,
                request = request,
                // A match has no guide, so there is no on-air programme to name in the HUD.
                programmeTitle = null,
                resolveFailed = state.playFailure != null,
                // EMPTY ON PURPOSE: the match is not in the channel partition, so there is no list to
                // zap through or drawer to open. The surface keys its zapping and drawer off this.
                channels = emptyList(),
                onZapTo = {},
                onRetry = { onRetryChannel(request.channel.stableKey) },
                onAdvanceSource = onAdvanceSource,
                onPrevious = {},
                onNext = {},
                onExit = onExitFullscreen
            )
        } else {
            when (state.status) {
                LiveTvStatus.LOADING -> CenteredMessage(stringResource(R.string.live_tv_loading))

                LiveTvStatus.ERROR -> MatchesErrorState(
                    description = state.errorMessage ?: stringResource(R.string.live_tv_empty_desc),
                    onRetry = onRetry
                )

                // EMPTY and READY both reach the grid: EMPTY means the catalogs answered and the
                // partition produced no matches, which the grid's own empty state says better than a
                // second message would.
                else -> LiveTvMatchesGrid(
                    matches = state.matches,
                    resolvingKey = state.resolvingChannelKey,
                    onPlayMatch = onPlayMatch,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // A failed resolve, surfaced over the grid the way the channel list surfaces it: information,
        // not a dead end -- the user can press another card or retry the same one.
        state.playFailure?.takeIf { !state.isFullscreen }?.let { failure ->
            Text(
                text = when (failure) {
                    LiveTvPlayFailure.NO_STREAMS -> stringResource(R.string.live_tv_play_failed_no_streams)
                    LiveTvPlayFailure.RESOLVE_FAILED -> stringResource(R.string.live_tv_play_failed_resolve)
                },
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(NuvioTheme.spacing.lg)
            )
        }

        // The source picker floats over the grid and owns Back while it is up, via its own handler.
        state.sourcePicker?.let { picker ->
            LiveTvSourcePicker(
                picker = picker,
                onPick = onPickSource,
                onDismiss = onDismissSourcePicker
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
private fun MatchesErrorState(
    description: String,
    onRetry: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.live_tv_error_title),
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
            androidx.tv.material3.Button(onClick = onRetry) {
                Text(text = stringResource(R.string.live_tv_retry))
            }
        }
    }
}

/**
 * The matches view: the live sports events the addon ships inside its channel catalogs, now that the
 * partition keeps them out of the channel list.
 *
 * ### Why a grid, not a couple of LazyRows
 *
 * A match has no grouping to row it by -- no "by league", no sections, just a flat list in the addon's
 * own live/priority order -- so rows would impose an arbitrary chunking on it and waste the left
 * third of the poster. The card's identity is its 16:9 poster, and a grid shows the most events per
 * screen at a size a living room can actually read, with focus traversal that stays a simple 2D walk
 * of the same shapes. If a future grouping arrives, this is the composable to revisit -- not the
 * card.
 *
 * Focus: the house row pattern (white 2dp border, `clickable` makes it D-pad reachable). No focus
 * memory across mode toggles: the list is short, and remembering a card across a mode round-trip is
 * machinery for a walk the user can redo with two D-pad presses.
 */
@Composable
internal fun LiveTvMatchesGrid(
    matches: List<LiveTvChannel>,
    resolvingKey: String?,
    onPlayMatch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (matches.isEmpty()) {
        // An empty matches view is a normal state (no event on air), not an error: say so and stop.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.live_tv_matches_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    // Which sport the grid is filtered to. Null means "Todos": every sport the addon currently
    // publishes (already narrowed by the settings' disabled-sports filter, which runs upstream in
    // the ViewModel). The selection is screen-local on purpose: the bar reflects what is on air
    // NOW, and a remembered chip for a sport the addon stopped publishing would be a filter over
    // an empty list.
    var selectedSport by remember { mutableStateOf<String?>(null) }
    val sports = remember(matches) { LiveTvSports.byCount(matches) }
    val visibleMatches = remember(matches, selectedSport) {
        selectedSport?.let { LiveTvSports.ofSport(matches, it) } ?: matches
    }

    // The screen MUST open with a focused control. A television screen that opens with nothing
    // focusable is a dead end -- the remote's every key lands nowhere, which is the reference fork's
    // audit blocker A4 verbatim, reproduced here the day this section shipped without its own
    // requestFocus. With matches on air the entry point is the FIRST CHIP (Todos), and DOWN walks
    // into the grid below; the card no longer plants focus itself, so exactly one element claims it.
    val firstChipFocus = remember { FocusRequester() }
    LaunchedEffect(matches.size) {
        repeat(5) {
            kotlinx.coroutines.yield()
            runCatching { firstChipFocus.requestFocus() }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SportChipBar(
            sports = sports,
            selectedSport = selectedSport,
            onSelectSport = { selectedSport = it },
            firstChipFocus = firstChipFocus,
            modifier = Modifier.fillMaxWidth()
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(MATCH_GRID_COLUMNS),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // Horizontal padding here, not on the screen: the section is full-bleed, and the grid owns
            // the margin so its cards never touch the display edge while the background still reads as
            // one continuous surface.
            contentPadding = PaddingValues(
                horizontal = NuvioTheme.spacing.lg,
                vertical = NuvioTheme.spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            items(items = visibleMatches, key = { it.stableKey }) { match ->
                MatchCard(
                    match = match,
                    resolving = resolvingKey == match.stableKey,
                    onClick = { onPlayMatch(match.stableKey) }
                )
            }
        }
    }
}

/**
 * The sports chip bar above the grid: one "Todos" chip, then every sport the addon currently
 * publishes, ordered by [LiveTvSports.byCount] (count descending, then name).
 *
 * A `Row` with a shared scroll, NOT a `LazyRow` -- the same decision the category slider documents:
 * the chip count is bounded by the addon's sport list, not by the match count, so every chip
 * composes and the row scrolls as one piece.
 */
@Composable
private fun SportChipBar(
    sports: List<LiveTvSports.SportCount>,
    selectedSport: String?,
    onSelectSport: (String?) -> Unit,
    firstChipFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            // Padding here rather than on the grid keeps the chip bar and the cards on one shared
            // left margin while the section stays full-bleed.
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SportChip(
            label = stringResource(R.string.live_tv_sport_all),
            selected = selectedSport == null,
            onClick = { onSelectSport(null) },
            modifier = Modifier.focusRequester(firstChipFocus)
        )
        sports.forEach { sport ->
            SportChip(
                label = sportLabel(sport.key),
                selected = selectedSport == sport.key,
                onClick = { onSelectSport(sport.key) },
                modifier = Modifier
            )
        }
    }
}

/** A sport chip's display text. The stored key stays stable; only the "other" bucket is localised. */
@Composable
private fun sportLabel(sportKey: String): String =
    if (sportKey == LiveTvSports.OTHER_KEY) {
        stringResource(R.string.live_tv_sport_other)
    } else {
        sportKey
    }

/** The slider's chip, reused verbatim: one house look for every chip row on the screen. */
@Composable
private fun SportChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CategoryChip(label = label, selected = selected, onClick = onClick, modifier = modifier)
}

@Composable
private fun MatchCard(
    match: LiveTvChannel,
    resolving: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.Surface)
            .border(
                width = if (focused) 2.dp else 0.dp,
                // White, like the drawer's focused row: on a dark catalogue, focus has to read at a
                // glance from the sofa, and the house settled on white for exactly that.
                color = if (focused) Color.White else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(NuvioTheme.colors.BackgroundElevated)
        ) {
            match.posterUrl?.let { poster ->
                AsyncImage(
                    model = poster,
                    contentDescription = match.name,
                    // Crop, not Fit: the poster slot is a fixed 16:9 broadcast card, and a Fit image
                    // would letterbox it to a size that no longer reads as a match.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (isLiveBadge(match)) {
                BadgeChip(
                    text = "EN VIVO",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(NuvioTheme.spacing.xs)
                )
            } else {
                // Not on air: the kickoff time is the badge, in the DEVICE's timezone, or nothing at
                // all when the addon has not started shipping the instant yet.
                LiveTvKickoff.label(match.kickoffIso)?.let { kickoff ->
                    BadgeChip(
                        text = stringResource(R.string.live_tv_match_starts_at, kickoff),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(NuvioTheme.spacing.xs)
                    )
                }
            }
        }
        Text(
            // "Opening…" while the resolve runs, so a slow addon reads as progress, not as a dead card.
            text = if (resolving) {
                stringResource(R.string.live_tv_resolving)
            } else {
                match.displayTitle()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
        )
    }
}

/**
 * The badge pinned on a broadcast card. Hardcoded "EN VIVO" like the zapping drawer's badge, which
 * set the house precedent: the string is a broadcast convention in every locale this app ships, and
 * translating it would make the badge the only localised thing on an otherwise localized poster.
 * (The kickoff badge reuses the exact same container -- one badge look, two messages.)
 */
@Composable
private fun BadgeChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = NuvioTheme.spacing.xs, vertical = 2.dp)
    )
}

/**
 * Cheap and reliable: the addon stamps every on-air match's name with an "EN VIVO" prefix, so the
 * badge rides on that shape rather than on a field the addon does not publish. Case-insensitive so a
 * future casing tweak upstream cannot silently kill the badge.
 */
private fun isLiveBadge(match: LiveTvChannel): Boolean =
    match.name.startsWith(LIVE_PREFIX, ignoreCase = true)

private const val LIVE_PREFIX = "EN VIVO"

/**
 * The card's title, without a leading "EN VIVO" stamp: the badge already says it, and repeating the
 * prefix under the poster is the same information twice in two pixels of row. Falls back to the raw
 * name when stripping would leave nothing to show.
 */
private fun LiveTvChannel.displayTitle(): String {
    if (!isLiveBadge(this)) return name
    return name.substring(LIVE_PREFIX.length)
        .trimStart(':', ' ', '-', '·')
        .ifBlank { name }
}

private const val MATCH_GRID_COLUMNS = 4
