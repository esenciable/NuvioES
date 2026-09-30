package com.nuvio.tv.ext.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The split-screen preview: what the currently focused channel is broadcasting.
 *
 * It is a thin wrapper over the shared [LiveTvVideoSurface], muted, and clipped by the caller's parent so
 * the video respects the panel's rounded corners.
 *
 * ### Muted, on purpose
 *
 * The reference fork played its preview at full volume and started after a 450 ms pause on a focused row,
 * so moving around a list made the television shout at every stop. Nobody asked for audio while browsing;
 * the sound belongs to a channel the user deliberately opened. Anyone who wants sound presses OK.
 *
 * ### Released with the screen
 *
 * The player is created by the screen's composition and released on dispose, so leaving the screen frees
 * it, and a screen with no preview never builds one at all.
 */
@Composable
internal fun LiveTvPreviewSurface(
    liveTvPlayer: LiveTvPlayer,
    url: String,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier
) {
    LiveTvVideoSurface(
        liveTvPlayer = liveTvPlayer,
        url = url,
        headers = headers,
        muted = true,
        modifier = modifier
    )
}
