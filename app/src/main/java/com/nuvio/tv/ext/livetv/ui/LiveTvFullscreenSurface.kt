@file:OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * How long the overlay stays after the last interaction. Long enough to read the channel and what is on,
 * short enough that it is not a permanent band over the picture.
 */
private const val HUD_TIMEOUT_MS = 4_000L

/**
 * Fullscreen live playback, inside our own screen.
 *
 * ### Why this surface has to exist at all
 *
 * The reference fork rendered its fullscreen inside the main player, where UP and DOWN belong to the
 * player's own controls. It could only make them zap by consuming all four directions on the root
 * `Box` -- and that same consumption is what made "Reintentar" and "Atrás" unreachable with the D-pad
 * when playback failed, an audit P0. Owning the surface lets the keys mean what we say they mean without
 * stealing them from anything else.
 *
 * ### The error trap, stated as code
 *
 * The handler consumes UP/DOWN **only while playback is healthy**. The moment the player reports an
 * error it returns `false`, and focus moves to the retry button: the remote can then reach Reintentar,
 * and Back always leaves. A surface that keeps eating the directions after a failure is a dead end.
 *
 * The player instance is owned by the screen and shared with the preview -- zapping swaps the media
 * source, never the player.
 */
@Composable
internal fun LiveTvFullscreenSurface(
    liveTvPlayer: LiveTvPlayer,
    request: LiveTvPlayRequest,
    programmeTitle: String?,
    resolveFailed: Boolean,
    channels: List<LiveTvChannelRow>,
    onZapTo: (String) -> Unit,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var playbackError by remember { mutableStateOf<PlaybackException?>(null) }
    // The HUD is transient, like every player overlay on a television. It used to sit there forever,
    // which is a permanent banner covering the bottom of the picture -- reported from the device with a
    // screenshot. The reference fork hid its own after 3.5s, and I recorded that in the audit and then
    // failed to carry it over.
    var hudVisible by remember { mutableStateOf(true) }
    // Bumped by any interaction so the countdown restarts instead of the HUD blinking away mid-use.
    var hudTouch by remember { mutableStateOf(0) }
    // The fork's zapping drawer: LEFT/RIGHT open a channel list over the playing video and the D-pad
    // jumps directly. LEFT/RIGHT are not used for anything else on this surface, so owning them costs
    // nothing -- and the toggle is handled HERE, not inside the drawer, because the key event from a
    // row bubbles up after the drawer's own state change and a drawer-side handler would close and
    // re-open on the same key press.
    var drawerOpen by remember { mutableStateOf(false) }
    val surfaceFocus = remember { FocusRequester() }

    LaunchedEffect(hudTouch, request) {
        hudVisible = true
        delay(HUD_TIMEOUT_MS)
        hudVisible = false
    }

    // Back closes the drawer before it leaves the surface: the drawer is a layer of this surface,
    // and the drawer's Atrás must not be an exit from playback.
    BackHandler {
        if (drawerOpen) drawerOpen = false else onExit()
    }

    // Whether the stream that is on screen has actually drawn something.
    //
    // The cover below is the fork's own mechanism, applied to the surface that was missing it. Comparing
    // the two trees settled it: the fork uses ONE composable with this cover for BOTH the preview and the
    // fullscreen, while ours had the cover on the preview only -- which is exactly why the preview stopped
    // ghosting and the fullscreen did not.
    var firstFrameRendered by remember(request.stream.url) { mutableStateOf(false) }

    // One listener for the screen's player. Removed with the composition so a disposed surface never
    // writes into a state nobody is reading.
    DisposableEffect(liveTvPlayer, request.stream.url) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
                // The error overlay owns the screen from here; a cover underneath it would only fight it.
                firstFrameRendered = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_IDLE) playbackError = null
                // STATE_READY as well as the first frame, and this is not belt and braces. A stream with
                // no video track -- radio-style IPTV, which is common in these catalogues -- never fires
                // onRenderedFirstFrame, so the cover would stay up forever on a channel that is playing
                // perfectly. The fork flips the same flag on both events for the same reason.
                if (playbackState == Player.STATE_READY) firstFrameRendered = true
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }
        }
        liveTvPlayer.player.addListener(listener)
        onDispose { liveTvPlayer.player.removeListener(listener) }
    }

    // Playing is keyed on the channel, so zapping swaps the stream on the same player and the surface
    // takes focus back for the next key press. Same stream as the preview -> the player ignores it.
    LaunchedEffect(liveTvPlayer, request) {
        liveTvPlayer.player.volume = 1f
        liveTvPlayer.play(request.stream.url, request.stream.headers)
        runCatching { surfaceFocus.requestFocus() }
    }

    // On failure the D-pad STAYS with the zapping. This is the deliberate reversal of an earlier
    // design that handed focus to the retry button on error: the owner reported zapping dying
    // whenever the next channel failed, and that is not how a television behaves. The failure is
    // information -- an overlay saying what happened -- and the keys keep doing what they always do.
    // Reintentar is the OK key on this surface; Back always leaves. Nothing is unreachable.

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.PageUp -> {
                        hudTouch++
                        onPrevious()
                        true
                    }

                    Key.DirectionDown, Key.PageDown -> {
                        hudTouch++
                        onNext()
                        true
                    }

                    // The zapping drawer, like the fork. While it is open these keys bubble up from
                    // the list rows (nothing focusable sits beside the drawer), so the same handler
                    // closes it again -- one code path for open and close.
                    Key.DirectionLeft, Key.DirectionRight -> {
                        drawerOpen = !drawerOpen
                        if (drawerOpen) hudVisible = false else hudTouch++
                        true
                    }

                    // OK is Reintentar while a failure is on screen. Consumed there so the request
                    // cannot leak into whatever else is composed; outside a failure it stays free.
                    Key.DirectionCenter, Key.Enter -> {
                        if (playbackError != null || resolveFailed) {
                            onRetry()
                            true
                        } else {
                            hudTouch++
                            false
                        }
                    }

                    // Anything else wakes the HUD back up without consuming the key.
                    else -> {
                        hudTouch++
                        false
                    }
                }
            }
    ) {
    // The ONE video view for the whole feature. The cover and the volume policy live in it, so this
    // surface can no longer drift from the preview the way it did when each surface had its own.
    LiveTvVideoSurface(
        liveTvPlayer = liveTvPlayer,
        url = request.stream.url,
        headers = request.stream.headers,
        muted = false,
        modifier = Modifier.fillMaxSize()
    )


        // The cover, and it is drawn as a SIBLING AFTER the AndroidView.
        //
        // That ordering is the whole reason it works: an opaque Compose sibling painted after a
        // PlayerView does cover its SurfaceView, which is not true of a Modifier.clip and not true of
        // alpha on the view. The house player already relies on this -- an opaque LoadingOverlay is drawn
        // over its own PlayerView -- so it is proven on these devices rather than assumed.
        //
        // Never alpha on the view itself. That was tried first and deadlocked: a view Compose does not
        // draw never renders a frame, so the signal that lifts the cover never arrives.
        if (!firstFrameRendered) {
            Box(modifier = Modifier.matchParentSize().background(Color.Black))
        }

        // The zapping drawer: over the video, playback untouched. It renders the VISIBLE channel set,
        // so the parental filter and the selected category hold here too -- the drawer cannot reach a
        // channel the list does not show.
        if (drawerOpen) {
            LiveTvZappingDrawer(
                channels = channels,
                currentKey = request.channel.stableKey,
                onSelect = { stableKey ->
                    drawerOpen = false
                    onZapTo(stableKey)
                },
                onDismiss = { drawerOpen = false },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (hudVisible) {
            FullscreenHud(
                channelName = request.channel.name,
                programmeTitle = programmeTitle,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(NuvioTheme.spacing.xl)
            )
        }

        // The failure overlay, for a player error and for a channel that never resolved. It informs;
        // it does not capture: zapping keys are handled by the surface regardless of this overlay.
        if (playbackError != null || resolveFailed) {
            PlaybackErrorOverlay(
                // Retry re-resolves through the addon (the view model owns that). Replaying the stored URL
                // reproduces a stale-URL failure by construction -- a 404 on a live segment usually means
                // the URL in hand is stale, so pressing Retry has to fetch a fresh one.
                onRetry = {
                    playbackError = null
                    onRetry()
                }
            )
        }
    }
}

@Composable
private fun FullscreenHud(
    channelName: String,
    programmeTitle: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        Text(
            text = channelName,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        programmeTitle?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1
            )
        }
        Text(
            text = stringResource(R.string.live_tv_fullscreen_hint),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PlaybackErrorOverlay(
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(20.dp))
                .padding(NuvioTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.live_tv_fullscreen_error),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            // Not a focusable Button on purpose: focus belongs to the surface so the D-pad keeps
            // zapping. This is the OK key's target, and the hint says so.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(NuvioTheme.colors.Secondary)
                    .clickable(onClick = onRetry)
                    .focusProperties { canFocus = false }
                    .padding(vertical = NuvioTheme.spacing.xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.live_tv_retry),
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    color = NuvioTheme.colors.OnSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.live_tv_fullscreen_error_hint),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
