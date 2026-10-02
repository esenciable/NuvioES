package com.nuvio.tv.ext.livetv.ui

import android.os.SystemClock
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * How long the drawer keeps retrying focus entry before falling back to the first visible row, and
 * the pause between attempts. Time-boxed rather than a fixed retry count: the race being fought is
 * against lazy composition, which on a 1200+ channel list can take an unbounded number of frames
 * after `scrollToItem` -- five retries was simply too few on the device.
 */
private const val FOCUS_ENTRY_TIMEOUT_MS = 600L
private const val FOCUS_ENTRY_RETRY_MS = 50L

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
    onToggleFavorite: (String) -> Unit,
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
        val fallbackFocus = remember { FocusRequester() }
        // The row the fallback requester should sit on: the first VISIBLE row, decided only if the
        // current row never took focus. Null means the fallback has not been armed.
        var fallbackKey by remember { mutableStateOf<String?>(null) }
        val currentIndex = channels.indexOfFirst { it.channel.stableKey == currentKey }

        // The drawer opens anchored on the channel that is playing.
        //
        // THE RACE: `scrollToItem` only scrolls -- the target row still has to be composed by the lazy
        // list on a later frame, and `requestFocus` fails (or the requester is not even attached yet)
        // until that composition happens. One attempt always loses it; the old five `yield()` retries
        // lost it too on long lists, which is why the owner had to press OK before the arrows moved.
        // The fix is a TIME-BOXED retry: one `requestFocus` attempt every [FOCUS_ENTRY_RETRY_MS] for
        // up to [FOCUS_ENTRY_TIMEOUT_MS], stopping at the first success -- `requestFocus` returns true
        // when focus actually landed, so the boolean is the loop's exit condition, not an exception.
        // If the current row never composes in the window (the current channel is scrolled far outside
        // the visible window of a very long list), the fallback focuses the first VISIBLE row, so the
        // drawer ALWAYS holds focus and the arrows always work without an OK press first.
        LaunchedEffect(currentKey, channels.size) {
            if (currentIndex > 0) listState.scrollToItem(currentIndex)
            suspend fun tryFocus(requester: FocusRequester): Boolean =
                runCatching { requester.requestFocus() }.getOrDefault(false)

            val deadline = SystemClock.elapsedRealtime() + FOCUS_ENTRY_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (tryFocus(itemFocus)) return@LaunchedEffect
                delay(FOCUS_ENTRY_RETRY_MS)
            }

            // Fallback: hand focus to whatever row is on screen. The row is already composed (it is
            // visible by definition), but the requester is attached to it only after the recomposition
            // this state write triggers -- hence the same short retry loop.
            fallbackKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
            val fallbackDeadline = SystemClock.elapsedRealtime() + FOCUS_ENTRY_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < fallbackDeadline) {
                if (tryFocus(fallbackFocus)) return@LaunchedEffect
                delay(FOCUS_ENTRY_RETRY_MS)
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
                            focusRequester = when (row.channel.stableKey) {
                                currentKey -> itemFocus
                                fallbackKey -> fallbackFocus
                                else -> null
                            },
                            onClick = { onSelect(row.channel.stableKey) },
                            onToggleFavorite = { onToggleFavorite(row.channel.stableKey) }
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
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    // The house row pattern (LiveTvScreen): focus is the border, click is the action, and `clickable`
    // is what makes the row reachable by the D-pad at all.
    var focused by remember { mutableStateOf(false) }

    // Focus plumbing for the star. `rowFocus` is the row's own focus target: when the caller passed
    // one (the entry-focus requester) it IS the row's requester, otherwise a local unused one stands
    // in so the star's LEFT exit always has a target. `starFocus` is what the row's RIGHT exit aims
    // at -- an explicit target is REQUIRED, not a nicety: one-dimensional focus search only finds
    // candidates strictly outside the focused node's bounds in the direction of travel, and the star
    // sits INSIDE the row's rect, so a geometric search would never find it (and LEFT from the star
    // would never find the row that contains it).
    val localRowFocus = remember { FocusRequester() }
    val rowFocus = focusRequester ?: localRowFocus
    val starFocus = remember { FocusRequester() }

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
            // RIGHT from the row text moves into the star; UP/DOWN stay natural so the arrows keep
            // walking the list from the row exactly as before.
            .focusProperties { right = starFocus }
            .focusRequester(rowFocus)
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
        FavoriteStar(
            isFavorite = row.isFavorite,
            starFocus = starFocus,
            rowFocus = rowFocus,
            onToggle = onToggleFavorite
        )
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

/**
 * The favorite toggle at the end of a channel row, shared by the zapping drawer and the main panel.
 *
 * It is its OWN focusable target: OK here toggles the favorite in place -- it neither plays the
 * channel nor closes the drawer -- while OK on the row body keeps playing (the row's `clickable` is
 * untouched, and the star's own `clickable` consumes the press so it can never fall through to the
 * row). Focused star = white border, the house pattern.
 *
 * Focus edges are explicit for the same geometric reason the row aims RIGHT at [starFocus]: from the
 * star, LEFT must return to the row text ([rowFocus]) rather than let the drawer column's LEFT
 * handler close the drawer, and UP/DOWN keep working by natural search because the rows above and
 * below ARE strictly outside the star's bounds vertically. Their stars sit at the same x, so the
 * nearest candidate keeps the column -- and even when the row body wins the search, one more RIGHT
 * re-enters the star, so focus is never stranded.
 */
@Composable
internal fun FavoriteStar(
    isFavorite: Boolean,
    starFocus: FocusRequester,
    rowFocus: FocusRequester,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Icon(
        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
        contentDescription = stringResource(
            if (isFavorite) R.string.live_tv_remove_favorite else R.string.live_tv_add_favorite
        ),
        tint = if (isFavorite) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
        modifier = modifier
            .size(40.dp)
            .focusRequester(starFocus)
            .focusProperties { left = rowFocus }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.Secondary.copy(alpha = 0.35f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = shape
            )
            .clickable(onClick = onToggle)
            .padding(8.dp)
    )
}
