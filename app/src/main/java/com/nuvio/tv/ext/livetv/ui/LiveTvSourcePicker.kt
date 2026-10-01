@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.model.LiveTvSourcePicker
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The source picker: shown when a match (or channel) resolved to more than one usable stream.
 *
 * A centered dialog, not a bottom sheet: the house overlays on this screen (the fullscreen error
 * panel, the empty state) are centered cards, and the remote user has no notion of "bottom" to aim
 * at -- centered is where focus already is. Rows are the addon's sources in the addon's own order:
 * it ranked them by priority, and this screen does not re-rank what it does not understand.
 *
 * Back dismisses and nothing plays; picking a row opens fullscreen with THAT source, through the same
 * [LiveTvPlayRequest] pipeline every other play path uses.
 */
@Composable
internal fun LiveTvSourcePicker(
    picker: LiveTvSourcePicker,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Back belongs to the picker while it is up: dismiss, never leave the screen. Composed after the
    // screen's own handler, so this one wins while it exists.
    BackHandler { onDismiss() }

    val firstItemFocus = remember { FocusRequester() }

    // The picker is a decision the user opened on purpose: put focus on the first row immediately,
    // or the D-pad's first DOWN would land nowhere.
    LaunchedEffect(picker) {
        runCatching { firstItemFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Same scrim as the fullscreen error overlay: informs that the screen underneath is
            // paused for input without hiding which view the user came from.
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(20.dp))
                .padding(NuvioTheme.spacing.xl)
        ) {
            Text(
                text = picker.channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = stringResource(R.string.live_tv_source_picker_title),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

            LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                itemsIndexed(items = picker.sources) { index, source ->
                    SourceRow(
                        label = source.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.live_tv_source_fallback, index + 1),
                        focusRequester = if (index == 0) firstItemFocus else null,
                        onClick = { onPick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    label: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    // The house row pattern (LiveTvScreen / zapping drawer): focus IS the border, click is the action.
    var focused by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .clip(shape)
            .background(
                if (focused) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.Surface
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start
        )
    }
}
