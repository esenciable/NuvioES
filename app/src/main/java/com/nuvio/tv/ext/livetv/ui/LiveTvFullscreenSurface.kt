@file:OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
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
    val surfaceFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(hudTouch, request) {
        hudVisible = true
        delay(HUD_TIMEOUT_MS)
        hudVisible = false
    }

    // Back leaves the surface and returns to the list. Registered here rather than in the screen so
    // it is only active while this surface is on screen.
    BackHandler(onBack = onExit)

    // One listener for the screen's player. Removed with the composition so a disposed surface never
    // writes into a state nobody is reading.
    DisposableEffect(liveTvPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_IDLE) playbackError = null
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

    // On failure, hand the D-pad to the retry button instead of eating it.
    LaunchedEffect(playbackError) {
        if (playbackError != null) runCatching { retryFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocus)
            .focusable()
            .onKeyEvent { event ->
                // The trap: in error, do not consume directions -- let focus reach the button and Back.
                if (playbackError != null) return@onKeyEvent false
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

                    // Anything else wakes the HUD back up without consuming the key.
                    else -> {
                        hudTouch++
                        false
                    }
                }
            }
    ) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                // No controller: UP/DOWN zapping is the whole interaction, and the controller would
                // consume the very directions we need. The HUD is the interface instead.
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                this.player = liveTvPlayer.player
            }
        },
        // DETACH ON DISPOSE, or the surface keeps the last frame it rendered.
        //
        // The player is shared with the preview on purpose, but an ExoPlayer renders into ONE surface,
        // so the surface that no longer receives frames holds its last buffer frozen. Leaving the
        // fullscreen therefore drew the old channel UNDER the list -- two stacked videos -- because the
        // destroyed PlayerView was still attached to the player. Verified on device, screenshot at
        // 2026-09-29 21:19.
        onRelease = { view -> view.player = null }
    )

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

        playbackError?.let {
            PlaybackErrorOverlay(
                onRetry = {
                    playbackError = null
                    liveTvPlayer.play(
                        url = request.stream.url,
                        headers = request.stream.headers,
                        force = true
                    )
                    runCatching { surfaceFocus.requestFocus() }
                },
                retryFocus = retryFocus
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
    onRetry: () -> Unit,
    retryFocus: FocusRequester
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
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(retryFocus)
            ) {
                Text(
                    text = stringResource(R.string.live_tv_retry),
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
