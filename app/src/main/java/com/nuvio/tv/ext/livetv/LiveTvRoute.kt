@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.ui.LiveTvScreen
import com.nuvio.tv.ext.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.navigation.Screen

/**
 * The single entry point of the Live TV feature, and the only thing the upstream navigation host knows
 * about.
 *
 * Navigation stays behind this seam on purpose, but live channels no longer leave the screen: they play
 * in the feature's own fullscreen surface, fed by [LiveTvViewModel.playRequests]. That stays a one-shot
 * event rather than state precisely because opening a player is an action -- a pending request living in
 * the UI state would open the player again on every recomposition.
 *
 * The old handoff to `Screen.Player` is gone, and with it the parental-filter leak: it navigated with
 * the already-resolved stream and never asked whether the channel had survived the filter. There is now
 * no path to a live channel that does not go through the filtered list.
 */
@Composable
fun LiveTvRoute(navController: NavController) {
    val viewModel: LiveTvViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fullscreenRequest by remember { mutableStateOf<LiveTvPlayRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { request -> fullscreenRequest = request }
    }

    LiveTvScreen(
        state = state,
        fullscreenRequest = fullscreenRequest,
        onBack = { navController.popBackStack() },
        onManageAddons = { navController.navigate(Screen.AddonManager.route) },
        onRetry = viewModel::refresh,
        onPlayChannel = viewModel::playChannel,
        onChannelFocused = viewModel::onChannelFocused,
        onSetAdultFilter = viewModel::setAdultFilter,
        onSearchQuery = viewModel::setSearchQuery,
        onSetEpgSourceEnabled = viewModel::setEpgSourceEnabled,
        onSetCategoryVisible = viewModel::setCategoryVisible,
        onSelectCategory = viewModel::selectCategory,
        onNextChannel = viewModel::nextChannel,
        onPreviousChannel = viewModel::previousChannel,
        onExitFullscreen = {
            fullscreenRequest = null
            viewModel.exitFullscreen()
        }
    )
}
