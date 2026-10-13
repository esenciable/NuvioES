@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The sports settings as a category of the app Settings screen, under the Partidos group.
 *
 * The upstream rail refactor (2026-10 sync) folded these toggles into the Live TV settings pane;
 * the owner asked for the pre-merge arrangement back: Live TV settings govern only live TV
 * (Magis + PremiumTV), and the per-sport toggles that filter Partidos live in their own entry.
 * This is that entry -- a sports-only slice of the SAME [LiveTvSettingsViewModel] and the SAME
 * store the Live TV pane used, so the toggles keep governing exactly the matches grid they always
 * filtered ([com.nuvio.tv.ext.livetv.ui.LiveTvViewModel] reads `disabledSports` in both its full
 * load and its matches-only fast path).
 *
 * Deliberately the same state of the Live TV screen rather than a new destination, for the same
 * reason [LiveTvSettingsEntry] documents: a second route would mean a second line in `Screen.kt`.
 */
@Composable
internal fun LiveTvSportsSettingsEntry(
    initialFocusRequester: FocusRequester? = null
) {
    val viewModel: LiveTvSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Same focus contract as [LiveTvSettingsEntry]: the pane focuses its own first control on
    // entry, and the upstream requester wraps the pane in a focus group so the detail retry loop
    // resolves inside it.
    val wrapper = if (initialFocusRequester != null) {
        Modifier
            .focusRequester(initialFocusRequester)
            .focusGroup()
    } else {
        Modifier
    }

    Box(modifier = wrapper.fillMaxSize()) {
        LiveTvSportsSettingsPane(
            state = state,
            onSetSportEnabled = viewModel::setSportEnabled
        )
    }
}

/**
 * The Partidos settings pane: the sports group only, exactly as it rendered inside the combined
 * pane before the rail refactor.
 */
@Composable
internal fun LiveTvSportsSettingsPane(
    state: LiveTvUiState,
    onSetSportEnabled: (String, Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    val firstToggle = remember { FocusRequester() }

    // Focus lands on the first control on entry, never nowhere. Same contract as the channel list.
    LaunchedEffect(Unit) {
        runCatching { firstToggle.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        item(key = "header") {
            SettingsDetailHeader(
                title = stringResource(R.string.live_tv_matches_section),
                subtitle = stringResource(R.string.live_tv_settings_sports_sub)
            )
        }

        item(key = "sports-header") {
            SettingsGroupCard(
                title = stringResource(R.string.live_tv_settings_sports)
            ) {}
        }

        // Every sport the screen has observed the addon publishing, delivered through the store
        // because the settings ViewModel deliberately never loads channels -- its own state's
        // `matches` is always empty, which is exactly why enumerating from it shipped no rows at
        // all (the review's finding). A disabled sport stays known, so its row survives and can be
        // turned back on. Before the user first opens Partidos nothing has been observed yet: the
        // group then shows its header with no rows, a real state, not a bug.
        items(
            items = state.knownSports,
            key = { "sport-$it" }
        ) { sport ->
            val enabled = sport !in state.disabledSports
            SettingsToggleRow(
                title = sportDisplayLabel(sport),
                subtitle = null,
                checked = enabled,
                onToggle = { onSetSportEnabled(sport, !enabled) }
            )
        }
    }
}

/**
 * A sport row's display text. The stored key stays stable; only the "other" bucket (digit-only or
 * missing genre, the addon's old discipline numbers) is localised rather than shown as a number.
 */
@Composable
private fun sportDisplayLabel(sportKey: String): String =
    if (sportKey == LiveTvSports.OTHER_KEY) {
        stringResource(R.string.live_tv_sport_other)
    } else {
        sportKey
    }
