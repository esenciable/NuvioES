package com.nuvio.tv.ext.livetv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The feature's settings as a category of the app Settings screen.
 *
 * Resolves its own [LiveTvSettingsViewModel] -- the settings-only slice of the state, without the
 * channel list or the guide -- and renders the same [LiveTvSettingsPane] the in-screen button shows,
 * over the same store. Both entry points therefore read and write the same preferences and cannot
 * diverge.
 */
@Composable
internal fun LiveTvSettingsEntry(
    initialFocusRequester: FocusRequester? = null
) {
    val viewModel: LiveTvSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    /*
     * No FocusRequester is threaded through to the pane: the pane focuses its own first control on
     * entry (LaunchedEffect + firstToggle), the same contract as the in-screen usage, and the pane
     * is rendered unchanged. The upstream requester is still honoured, though: it is attached to a
     * focus group around the pane, so the settings detail retry loop resolves inside the pane
     * instead of falling through to `moveFocus` after its retry window and walking focus off the
     * very control the pane just focused.
     */
    val wrapper = if (initialFocusRequester != null) {
        Modifier
            .focusRequester(initialFocusRequester)
            .focusGroup()
    } else {
        Modifier
    }

    Box(modifier = wrapper.fillMaxSize()) {
        LiveTvSettingsPane(
            state = state,
            onSetAdultFilter = viewModel::setAdultFilter,
            onSetEpgSourceEnabled = viewModel::setEpgSourceEnabled,
            onSetCategoryVisible = viewModel::setCategoryVisible
        )
    }
}
