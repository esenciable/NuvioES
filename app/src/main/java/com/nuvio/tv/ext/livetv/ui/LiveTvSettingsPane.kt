@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.EpgSourceOrigin
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.canBeHidden
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The feature's settings, inside the feature.
 *
 * Deliberately a state of the Live TV screen rather than a new destination: a second route would mean a
 * second line in `Screen.kt` and a second composable in `NuvioNavHost.kt`, and the conflict budget is
 * the resource this project protects. Back closes the pane and stays on the screen, which is the
 * behaviour a user expects from a panel anyway.
 *
 * Built from the house settings components, so it looks like the rest of the app instead of like a
 * feature that arrived from somewhere else.
 */
@Composable
internal fun LiveTvSettingsPane(
    state: LiveTvUiState,
    onSetAdultFilter: (Boolean) -> Unit,
    onSetEpgSourceEnabled: (String, Boolean) -> Unit,
    onSetCategoryVisible: (LiveTvCategoryId, Boolean) -> Unit,
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
                title = stringResource(R.string.live_tv_settings_title),
                subtitle = stringResource(R.string.live_tv_settings_subtitle)
            )
        }

        item(key = "parental") {
            SettingsGroupCard(
                title = stringResource(R.string.live_tv_settings_parental)
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.live_tv_settings_adult_filter),
                    // Honest about what it is. A household that believes a keyword list is a guarantee
                    // is worse off than one that was told.
                    subtitle = stringResource(R.string.live_tv_settings_adult_filter_sub),
                    checked = state.adultFilterActive,
                    onToggle = { onSetAdultFilter(!state.adultFilterActive) },
                    modifier = Modifier.focusRequester(firstToggle)
                )
            }
        }

        item(key = "categories-header") {
            SettingsGroupCard(
                title = stringResource(R.string.live_tv_settings_categories),
                subtitle = stringResource(R.string.live_tv_settings_categories_sub)
            ) {}
        }

        // Every category the user is allowed to hide. All and Favorites are absent by construction:
        // without All the list has no default state, and Favorites is our own store, not a catalog.
        items(
            items = state.categories.filter { it.id.canBeHidden },
            key = { "category-${it.id.preferenceKey}" }
        ) { category ->
            val visible = category.id.preferenceKey !in state.hiddenCategoryIds
            SettingsToggleRow(
                title = category.settingsLabel(),
                subtitle = null,
                checked = visible,
                onToggle = { onSetCategoryVisible(category.id, !visible) }
            )
        }

        item(key = "sports-header") {
            SettingsGroupCard(
                title = stringResource(R.string.live_tv_settings_sports),
                subtitle = stringResource(R.string.live_tv_settings_sports_sub)
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

        item(key = "sources-header") {
            SettingsGroupCard(
                title = stringResource(R.string.live_tv_settings_sources),
                subtitle = stringResource(R.string.live_tv_settings_sources_disclosure)
            ) {}
        }

        items(items = state.epgSources, key = EpgSource::id) { source ->
            val enabled = source.id !in state.disabledEpgSourceIds
            SettingsToggleRow(
                title = source.name,
                subtitle = sourceOriginLabel(source),
                checked = enabled,
                onToggle = { onSetEpgSourceEnabled(source.id, !enabled) }
            )
        }
    }
}

/** Says where a source comes from, because "whose server am I contacting" is the whole question. */
@Composable
private fun sourceOriginLabel(source: EpgSource): String = when (source.origin) {
    is EpgSourceOrigin.FromAddon -> stringResource(R.string.live_tv_settings_source_addon)
    EpgSourceOrigin.BuiltIn -> stringResource(R.string.live_tv_settings_source_third_party)
    EpgSourceOrigin.User -> stringResource(R.string.live_tv_settings_source_user)
}

/** A hideable category is always an addon catalog, so the addon's own name is the label. */
private fun LiveTvCategory.settingsLabel(): String =
    addonCatalogName ?: (id as? LiveTvCategoryId.Addon)?.catalogId.orEmpty()

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
