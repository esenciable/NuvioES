@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The category slider: one chip per category the user has not hidden, above the channel list.
 *
 * ### Why a `Row` with a shared scroll, not a `LazyRow`
 *
 * The reference fork put a `LazyRow` inside the channels' `LazyColumn`, and nesting two lazy scrollers
 * is what made a low-end box stutter. Here the chip count is bounded by the addons' catalogs rather
 * than by the channel count, so every chip composes and the row scrolls as one piece -- the same shape
 * the guide uses for its timeline.
 *
 * ### Focus
 *
 * The active chip is the row's restore target, so coming back up from the list lands on the category
 * that is actually selected. Entering the screen never asks this row for focus: the list does, because
 * the user came to watch channels and the chip row is a detour they may never take.
 *
 * The labels are resolved from string resources for the built-ins and from the addon for the rest;
 * selection always compares [LiveTvCategoryId], so translating a label can never change what the chip
 * does.
 */
@Composable
internal fun LiveTvCategorySlider(
    categories: List<LiveTvCategory>,
    selectedCategory: LiveTvCategoryId,
    onSelectCategory: (LiveTvCategoryId) -> Unit,
    activeRequester: FocusRequester,
    requesters: MutableMap<String, FocusRequester>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRestorer(activeRequester)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { category ->
            val requester = requesters.getOrPut(category.id.preferenceKey) { FocusRequester() }
            CategoryChip(
                label = categoryLabel(category),
                selected = category.id == selectedCategory,
                onClick = { onSelectCategory(category.id) },
                modifier = Modifier.focusRequester(requester)
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    // The focused fill is bright, so the label has to flip with it: a chip that keeps secondary text on
    // the focused fill is the one chip a user cannot read from the couch.
    val textColor = if (focused || selected) {
        NuvioTheme.colors.OnSecondary
    } else {
        NuvioTheme.colors.TextSecondary
    }

    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = FilterChipDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            selectedContainerColor = NuvioTheme.colors.Secondary,
            focusedSelectedContainerColor = NuvioTheme.colors.SecondaryVariant,
            contentColor = textColor,
            focusedContentColor = textColor,
            selectedContentColor = textColor,
            focusedSelectedContentColor = textColor
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(50)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(50)
            ),
            selectedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Secondary),
                shape = RoundedCornerShape(50)
            ),
            focusedSelectedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(50)
            )
        ),
        shape = FilterChipDefaults.shape(shape = RoundedCornerShape(50))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xs)
        )
    }
}

/** The chip's display text. Identity stays in the id; only the label is localised. */
@Composable
private fun categoryLabel(category: LiveTvCategory): String = when (val id = category.id) {
    LiveTvCategoryId.All -> stringResource(R.string.live_tv_category_all)
    LiveTvCategoryId.Favorites -> stringResource(R.string.live_tv_category_favorites)
    is LiveTvCategoryId.Addon -> category.addonCatalogName ?: id.catalogId
}
