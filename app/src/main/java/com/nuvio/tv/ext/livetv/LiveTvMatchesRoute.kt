@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.ui.LiveTvMatchesScreen
import com.nuvio.tv.ext.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.navigation.Screen

/**
 * The Partidos destination: live sports matches as their OWN sidebar section, outside the Live TV
 * screen.
 *
 * The owner asked for exactly this -- matches are a section, not a mode inside TV en vivo -- so the
 * route composes the matches grid full-bleed, with the feature's own fullscreen surface for playback.
 * It shares the activity-scoped [LiveTvViewModel] with [LiveTvRoute] (see
 * [activityScopedLiveTvViewModel]): navigating here never re-reads catalogs or the guide, and the two
 * destinations can never disagree about what is playing.
 *
 * This is a normal navigation destination: Back from the grid leaves the section. Returning to
 * channels is the sidebar's TV en vivo entry, not a hidden mode switch.
 */
@Composable
fun LiveTvMatchesRoute(navController: NavController) {
    val viewModel: LiveTvViewModel = activityScopedLiveTvViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fullscreenRequest by remember { mutableStateOf<LiveTvPlayRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { request -> fullscreenRequest = request }
    }

    LiveTvMatchesScreen(
        state = state,
        fullscreenRequest = fullscreenRequest,
        onBack = { navController.popBackStack() },
        onRetry = viewModel::refresh,
        onPlayMatch = viewModel::onMatchClicked,
        onPickSource = viewModel::pickSource,
        onDismissSourcePicker = viewModel::dismissSourcePicker,
        onAdvanceSource = viewModel::advanceSource,
        onRetryChannel = viewModel::retryChannel,
        onExitFullscreen = {
            fullscreenRequest = null
            viewModel.exitFullscreen()
        }
    )
}
