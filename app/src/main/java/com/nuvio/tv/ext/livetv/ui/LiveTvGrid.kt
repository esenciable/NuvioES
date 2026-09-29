@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.EpgGridCell
import com.nuvio.tv.ext.livetv.domain.EpgGridLayout
import com.nuvio.tv.ext.livetv.domain.EpgGridWindow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SLOT_MS = EpgGridLayout.DEFAULT_SLOT_MINUTES * 60_000L
private val CHANNEL_COLUMN = 200.dp
private val SLOT_HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The programme grid: channels down the side, time across.
 *
 * ### One scroll state, and no `LazyRow` inside a `LazyColumn`
 *
 * The reference fork's grid put a `LazyRow` inside each `LazyColumn` row, so a visible screen composed
 * dozens of cells per row and a low-end box stuttered. Here every row shares a single horizontal scroll
 * state with the ruler above it: the timeline moves as one piece, the channel column stays put, and the
 * number of composed cells is bounded by the window rather than by the catalogue.
 *
 * ### Focus
 *
 * Every cell is focusable and does something when pressed: it opens that channel. The window is a fixed
 * number of slots, so this stays true however many channels the addon publishes.
 */
@Composable
internal fun LiveTvGrid(
    rows: List<LiveTvChannelRow>,
    nowEpochMs: Long,
    onPlayChannel: (String) -> Unit
) {
    val horizontalScroll = rememberScrollState()
    // Keyed on the slot, not on the millisecond, so the window is not rebuilt on every tick.
    val window = remember(nowEpochMs / SLOT_MS) { EpgGridLayout.windowAround(nowEpochMs) }
    val firstCell = remember { FocusRequester() }
    val firstKey = rows.firstOrNull()?.channel?.stableKey

    LaunchedEffect(Unit) { runCatching { firstCell.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg)
    ) {
        TimeRuler(window, horizontalScroll)
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            items(items = rows, key = { it.channel.stableKey }) { row ->
                GridRow(
                    row = row,
                    window = window,
                    horizontalScroll = horizontalScroll,
                    onPlayChannel = onPlayChannel,
                    modifier = if (row.channel.stableKey == firstKey) {
                        Modifier.focusRequester(firstCell)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeRuler(window: EpgGridWindow, horizontalScroll: androidx.compose.foundation.ScrollState) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(CHANNEL_COLUMN))
        Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
            repeat(window.slotCount) { slot ->
                val at = window.startEpochMs + slot * window.slotMs
                Text(
                    text = SLOT_HOUR_FORMAT.format(
                        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.width(window.slotWidthDp.dp)
                )
            }
        }
    }
}

@Composable
private fun GridRow(
    row: LiveTvChannelRow,
    window: EpgGridWindow,
    horizontalScroll: androidx.compose.foundation.ScrollState,
    onPlayChannel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cells = remember(row.channel.stableKey, row.programmes, window) {
        EpgGridLayout.cellsFor(row.programmes, window)
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(CHANNEL_COLUMN).padding(end = NuvioTheme.spacing.sm)) {
            Text(
                text = row.channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!row.hasGuide) {
                Text(
                    text = stringResource(R.string.live_tv_no_guide),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }

        Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
            if (cells.isEmpty()) {
                Box(
                    modifier = Modifier
                        .width(window.slotWidthDp.dp * window.slotCount)
                        .height(CELL_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NuvioTheme.colors.Surface)
                )
            } else {
                cells.forEachIndexed { index, cell ->
                    ProgrammeCell(
                        cell = cell,
                        window = window,
                        onAir = row.now?.title == cell.programme.title,
                        onClick = { onPlayChannel(row.channel.stableKey) },
                        modifier = if (index == 0) Modifier else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    cell: EpgGridCell,
    window: EpgGridWindow,
    onAir: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .padding(end = 2.dp)
            .width(window.slotWidthDp.dp * cell.slotCount)
            .height(CELL_HEIGHT)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> NuvioTheme.colors.SecondaryVariant
                    onAir -> NuvioTheme.colors.BackgroundElevated
                    else -> NuvioTheme.colors.Surface
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = cell.programme.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.TextPrimary,
            fontWeight = if (onAir) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val CELL_HEIGHT = 56.dp
