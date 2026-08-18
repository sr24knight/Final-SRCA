package com.streambert.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.progress.WatchProgress
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val ROW_PADDING = 48.dp

// ── Landscape card (standard Netflix-style rows) ─────────────────────────────

@Composable
fun LandscapeCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    showPlayIcon: Boolean = false,
    onFocus: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.3f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary))
        ),
        modifier = modifier
            .width(184.dp)
            .aspectRatio(16f / 9f)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .cardLongPress(onLongPress)
            .onFocusChanged { if (it.isFocused) onFocus() }
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color(0xCC000000)
                        )
                    )
            )
            if (showPlayIcon) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(46.dp)
                        .background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            )
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x66FFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(Color(0xFFE50914))
                    )
                }
            }
        }
    }
}

@Composable
fun StandardRow(
    title: String,
    items: List<CatalogItem>,
    onSelect: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (CatalogItem) -> Unit = {},
    onLongPress: (CatalogItem) -> Unit = {},
    firstItemFocusRequester: FocusRequester? = null
) {
    if (items.isEmpty()) return
    Column(modifier.padding(vertical = 12.dp)) {
        RowTitle(title)
        MediaLazyRow(startPadding = ROW_PADDING, itemSpacing = 14.dp) {
            itemsIndexed(items, key = { _, it -> "${it.type}_${it.id}" }) { index, item ->
                LandscapeCard(
                    title = item.title,
                    imageUrl = item.backdropUrl ?: item.posterUrl,
                    onClick = { onSelect(item) },
                    onFocus = { onFocus(item) },
                    onLongPress = { onLongPress(item) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
        }
    }
}

// ── Top 10 row (portrait poster + big rank numeral) ──────────────────────────

@Composable
fun Top10Row(
    title: String,
    items: List<CatalogItem>,
    onSelect: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (CatalogItem) -> Unit = {},
    onLongPress: (CatalogItem) -> Unit = {},
    firstItemFocusRequester: FocusRequester? = null
) {
    if (items.isEmpty()) return
    Column(modifier.padding(vertical = 12.dp)) {
        RowTitle(title)
        MediaLazyRow(startPadding = ROW_PADDING, itemSpacing = 4.dp) {
            itemsIndexed(items, key = { _, it -> "${it.type}_${it.id}" }) { index, item ->
                Top10Card(
                    rank = index + 1,
                    item = item,
                    onClick = { onSelect(item) },
                    onFocus = { onFocus(item) },
                    onLongPress = { onLongPress(item) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
        }
    }
}

@Composable
private fun Top10Card(
    rank: Int,
    item: CatalogItem,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    Row(verticalAlignment = Alignment.Bottom) {
        // Big rank numeral, Netflix-style.
        Text(
            text = rank.toString(),
            fontSize = 88.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF2A2A33),
            modifier = Modifier.padding(end = 4.dp)
        )
        Card(
            onClick = onClick,
            scale = CardDefaults.scale(focusedScale = 1.3f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary))
            ),
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(2f / 3f)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .cardLongPress(onLongPress)
                .onFocusChanged { if (it.isFocused) onFocus() }
        ) {
            AsyncImage(
                model = item.posterUrl ?: item.backdropUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── Continue Watching row ────────────────────────────────────────────────────

@Composable
fun ContinueWatchingRow(
    entries: List<WatchProgress>,
    onResume: (WatchProgress) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (WatchProgress) -> Unit = {},
    onLongPress: (WatchProgress) -> Unit = {}
) {
    if (entries.isEmpty()) return
    Column(modifier.padding(vertical = 12.dp)) {
        RowTitle("Continue Watching")
        MediaLazyRow(startPadding = ROW_PADDING, itemSpacing = 14.dp) {
            items(entries, key = { it.key }) { entry ->
                LandscapeCard(
                    title = entry.title,
                    imageUrl = entry.backdropUrl ?: entry.posterUrl,
                    onClick = { onResume(entry) },
                    progress = entry.fraction,
                    showPlayIcon = true,
                    onFocus = { onFocus(entry) },
                    onLongPress = { onLongPress(entry) }
                )
            }
        }
    }
}

@Composable
private fun RowTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = ROW_PADDING, bottom = 10.dp)
    )
}
