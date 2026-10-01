package com.nuvio.tv.ext.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The zapping drawer: the fork's LEFT/RIGHT drawer over fullscreen, rebuilt on our own player.
 *
 * The fork opens a channel list over the playing video without stopping it, so a viewer can jump
 * directly to a channel instead of walking there one UP/DOWN at a time. It could not be carried over
 * while the fullscreen was upstream's player -- the drawer would have been a sixth hook or a duplicate
 * of a worse player -- and this feature's own fullscreen removed that obstacle. It lives here, in
 * `ext/livetv`, at zero budget.
 *
 * Focus behaviour: the rows are the only focusable things while open, so the remote cannot leak into
 * the surface behind it. The CURRENT channel is where focus starts, scrolled into view, because the
 * drawer is anchored to what is playing -- that is the reference point a viewer zaps from. OK plays
 * the focused channel and closes; LEFT closes and hands the D-pad back to the surface; RIGHT does
 * nothing inside the drawer (a single-column list has nothing to its right); BACK is handled by the
 * surface, which keeps its own Back handler alive behind the drawer.
 */
@Composable
internal fun LiveTvZappingDrawer(
    channels: List<LiveTvChannelRow>,
    currentKey: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInHorizontally { -it },
        exit = fadeOut() + slideOutHorizontally { -it },
        modifier = modifier
    ) {
        val listState = rememberLazyListState()
        val itemFocus = remember { FocusRequester() }
        val currentIndex = channels.indexOfFirst { it.channel.stableKey == currentKey }

        // The drawer opens anchored on the channel that is playing.
        //
        // Focus entry is RETRIED across frames. ONE requestFocus loses the race against the lazy
        // composition and the scrollToItem -- the row that should take focus is often not composed
        // yet when the effect first runs, the request dies, and the owner reported having to press
        // OK before the drawer ever held focus. This is the house pattern (the search field and the
        // matches grid retry the same way): yield a frame between attempts and swallow the expected
        // "not initialised yet" failure.
        LaunchedEffect(currentKey, channels.size) {
            if (currentIndex > 0) listState.scrollToItem(currentIndex)
            repeat(5) {
                kotlinx.coroutines.yield()
                runCatching { itemFocus.requestFocus() }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(360.dp)
                    .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.96f))
                    .onKeyEvent { event ->
                        // LEFT closes: the same direction that opened the drawer undoes it, and the
                        // column owns the key so the surface never zaps underneath the open drawer.
                        // The surface's own handler closes it too when focus is still on the surface;
                        // this branch only runs once the drawer actually holds focus.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                            onDismiss()
                            true
                        } else {
                            // RIGHT is NOT bubbled up on purpose: focus search would find nothing to
                            // the right of a single-column list and could strand or leak focus. The
                            // surface eats RIGHT while the drawer is open -- the drawer stays an island.
                            false
                        }
                    }
            ) {
                val currentName = currentKey
                    ?.let { key -> channels.firstOrNull { it.channel.stableKey == key }?.channel?.name }
                    .orEmpty()
                if (currentName.isNotEmpty()) {
                    Text(
                        text = currentName,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(NuvioTheme.spacing.lg)
                    )
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxHeight()) {
                    items(
                        items = channels,
                        key = { it.channel.stableKey }
                    ) { row ->
                        DrawerChannelItem(
                            row = row,
                            isCurrent = row.channel.stableKey == currentKey,
                            focusRequester = if (row.channel.stableKey == currentKey) itemFocus else null,
                            onClick = { onSelect(row.channel.stableKey) }
                        )
                    }
                }
            }

            // Dead space next to the drawer, deliberately NOT focusable: focus must not escape the
            // list, or the surface behind would start zapping while the drawer is on screen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = false }
            )
        }
    }
}

@Composable
private fun DrawerChannelItem(
    row: LiveTvChannelRow,
    isCurrent: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    // The house row pattern (LiveTvScreen): focus is the border, click is the action, and `clickable`
    // is what makes the row reachable by the D-pad at all.
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> NuvioTheme.colors.Secondary.copy(alpha = 0.35f)
                    isCurrent -> NuvioTheme.colors.Secondary.copy(alpha = 0.15f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            row.channel.logoUrl?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.channel.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            row.now?.let { programme ->
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isCurrent) {
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = "EN VIVO",
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.Secondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
