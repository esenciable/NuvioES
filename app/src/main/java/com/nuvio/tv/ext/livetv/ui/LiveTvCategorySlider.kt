@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
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
 * The list owns where the slider is entered from: the first channel row declares `up =
 * activeCategoryRequester`, so coming back up lands on the category that is actually selected.
 *
 * There is deliberately **no `focusRestorer` here.** It was there, and the device caught it: a
 * restorer remembers the last chip the user touched and sends focus back to *that* one, so entering
 * the slider from the first row landed on a previously visited chip instead of the active one -- two
 * mechanisms answering the same question with opposite answers. The row's `up` is the single answer.
 *
 * Entering the screen never asks this row for focus: the list does, because the user came to watch
 * channels and the chip row is a detour they may never take.
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
    requesters: MutableMap<String, FocusRequester>,
    modifier: Modifier = Modifier,
    /**
     * Where UP goes from a chip. Null leaves the key to the system, which is what happened before and
     * why the search field one row above was unreachable: the chips had no UP at all, so the focus
     * search found nothing and stayed put.
     */
    upTarget: FocusRequester? = null,
    /**
     * Called when a chip takes focus.
     *
     * The chip reports its own focus because nothing outside can ask: `FilterChip` keeps its focus node
     * to itself, which is exactly why focusProperties applied from the outside did nothing. Upstream's
     * search solves its version of this by observing keys at a ROOT container, so this exists to give
     * that container something to consult.
     */
    onChipFocused: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            // UP is handled by the SLIDER, not by each chip. The chip is a FilterChip, which builds its
            // own focus node and its own key handling internally; an ancestor preview sees the key first
            // and cannot be pre-empted by that. Per-chip handlers did nothing, which is how the search
            // field ended up rendered but unreachable.
            .onPreviewKeyEvent { event ->
                if (upTarget == null ||
                    event.type != KeyEventType.KeyDown ||
                    event.key != Key.DirectionUp
                ) {
                    false
                } else {
                    // Consume only if focus actually moved: swallowing the key after a failed request
                    // traps the D-pad on one element, the same shape as the reference fork's buttons
                    // that could not be pressed. Returning false lets the system's search try.
                    runCatching { upTarget.requestFocus() }.isSuccess
                }
            }
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
                modifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) onChipFocused() }
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

    // A plain clickable Box, NOT a FilterChip, and that is the whole fix.
    //
    // FilterChip builds its own focus node deep inside itself, so nothing applied out here can observe it
    // or steer it: focusProperties for UP did nothing, a key handler on the chip did nothing, and
    // onFocusChanged never fired, which meant the container that was waiting for that flag never acted
    // either. All three of those work on ChannelRow, which builds its focus node with this same
    // clickable, and that asymmetry was the answer. Verified on device: with FilterChip the UP key from a
    // chip went nowhere and the search field stayed rendered but unreachable.
    //
    // The styling is by hand so the chip looks exactly the same as before.
    val textColor = if (focused || selected) {
        NuvioTheme.colors.OnSecondary
    } else {
        NuvioTheme.colors.TextSecondary
    }
    val container = when {
        focused && selected -> NuvioTheme.colors.SecondaryVariant
        focused || selected -> NuvioTheme.colors.Secondary
        else -> NuvioTheme.colors.BackgroundCard
    }
    val borderColor = if (focused || selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border
    val borderWidth = if (focused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(borderWidth, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
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
