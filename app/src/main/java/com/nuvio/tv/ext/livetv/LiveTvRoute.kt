@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import android.content.Context
import android.content.ContextWrapper
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
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
    val viewModel: LiveTvViewModel = activityScopedLiveTvViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fullscreenRequest by remember { mutableStateOf<LiveTvPlayRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.playRequests.collect { request -> fullscreenRequest = request }
    }

    // The channel list loads on its own schedule now: Partidos may have loaded first (or the addon
    // read may not have happened at all), so entering TV en vivo over an unloaded list runs the full
    // refresh here. The condition reads "the full list was never loaded": status stays LOADING until
    // a full refresh publishes, and the matches fast path's contract is to never touch it. (Gating on
    // "status is not LOADING" instead would dead-end after a Partidos-only load and reload-loop after
    // a full load that ended EMPTY.) The view model drops concurrent refresh calls, so re-entering
    // while one is running costs nothing.
    LaunchedEffect(state.channels.isEmpty(), state.status) {
        if (state.channels.isEmpty() && state.status == LiveTvStatus.LOADING) {
            viewModel.refresh()
        }
    }

    LiveTvScreen(
        state = state,
        fullscreenRequest = fullscreenRequest,
        onBack = { navController.popBackStack() },
        onManageAddons = { navController.navigate(Screen.AddonManager.route) },
        onRetry = viewModel::refresh,
        onPlayChannel = viewModel::playChannel,
        onPickSource = viewModel::pickSource,
        onDismissSourcePicker = viewModel::dismissSourcePicker,
        onAdvanceSource = viewModel::advanceSource,
        onChannelFocused = viewModel::onChannelFocused,
        onSetAdultFilter = viewModel::setAdultFilter,
        onSearchQuery = viewModel::setSearchQuery,
        onSetEpgSourceEnabled = viewModel::setEpgSourceEnabled,
        onSetCategoryVisible = viewModel::setCategoryVisible,
        onSelectCategory = viewModel::selectCategory,
        onNextChannel = viewModel::nextChannel,
        onPreviousChannel = viewModel::previousChannel,
        onRetryChannel = viewModel::retryChannel,
        onExitFullscreen = {
            fullscreenRequest = null
            viewModel.exitFullscreen()
        }
    )
}

/**
 * The ONE [LiveTvViewModel] for the whole feature, scoped to the activity.
 *
 * Live TV and Partidos are two destinations over the same feature, and the heavy loading (catalogs,
 * EPG) must not run twice just because the user moved between them. Scoping to the activity makes
 * the instance outlive both screens; a navigation-scoped `hiltViewModel()` would build a second one
 * per destination and the two views would disagree about what is playing.
 *
 * The unwrapping walk matters because Compose can hand a wrapped context to composables -- a bare
 * `as ComponentActivity` cast would throw there instead of finding the activity the context wraps.
 * The navigation-scoped fallback keeps the route composable in any context without one (previews).
 */
@Composable
internal fun activityScopedLiveTvViewModel(): LiveTvViewModel {
    val activity = LocalContext.current.findComponentActivity()
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
