@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ext.livetv.ui.LiveTvScreen
import com.nuvio.tv.ext.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.navigation.Screen

/**
 * The single entry point of the Live TV feature, and the only thing the upstream navigation host knows
 * about.
 *
 * Navigation stays behind this seam on purpose. The handoff to the main player goes through
 * `Screen.Player.createRoute(...)`, which is upstream code and the most fragile thing we couple to;
 * when upstream changes that signature, the fix lands here -- inside our own package -- and the one-line
 * hook in `NuvioNavHost.kt` does not change.
 *
 * [Screen.Player.createRoute] is driven by a **one-shot event**, not by state. A pending play request
 * living in the UI state would re-fire on every recomposition and re-open the player each time.
 */
@Composable
fun LiveTvRoute(navController: NavController) {
    val viewModel: LiveTvViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { request ->
            navController.navigate(
                Screen.Player.createRoute(
                    streamUrl = request.stream.url,
                    title = request.channel.name,
                    streamName = request.stream.name ?: request.channel.name,
                    headers = request.stream.headers,
                    // `channel` is what the player's live policy reads to know this is not on-demand.
                    contentType = "channel",
                    contentName = request.channel.name,
                    videoId = request.channel.id,
                    logo = request.channel.logoUrl,
                    addonName = request.channel.addonName,
                    // Back returns to the channel list, not to Home.
                    returnToHomeOnBack = false
                )
            )
        }
    }

    LiveTvScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onManageAddons = { navController.navigate(Screen.AddonManager.route) },
        onRetry = viewModel::refresh,
        onPlayChannel = viewModel::playChannel,
        onChannelFocused = viewModel::onChannelFocused,
        onSetAdultFilter = viewModel::setAdultFilter,
        onSetEpgSourceEnabled = viewModel::setEpgSourceEnabled
    )
}
