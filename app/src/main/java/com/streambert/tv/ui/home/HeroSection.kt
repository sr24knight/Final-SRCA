package com.streambert.tv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streambert.tv.data.trailer.TrailerStream
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.model.MediaType
import com.streambert.tv.data.tmdb.Genres
import com.streambert.tv.ui.components.MediaLazyRow

// ─────────────────────────────────────────────────────────────────────────────
// Reusable home-hero composables (mockup: netflix-style-hero-mockup-v2).
// Structure kept intentionally flat — TopNavBar · HeroCard · PillButton ·
// NextWatchRow — so each piece is easy to extend independently. All interactive
// elements are focusable() with focus-state border/scale for Android TV D-pad.
// ─────────────────────────────────────────────────────────────────────────────

/** Top nav: profile avatar (left) · search + pill links (center) · settings (right). No logo. */
@Composable
fun TopNavBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Profile avatar (left).
        NavIconButton(
            icon = Icons.Filled.AccountCircle,
            desc = "Profile",
            onClick = onProfile,
            containerColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(8.dp)
        )

        Box(Modifier.weight(1f))

        // Center cluster: search + pill links.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NavIconButton(icon = Icons.Filled.Search, desc = "Search", onClick = onSearch)
            HomeTab.values().forEach { t ->
                NavPill(label = t.label, selected = selectedTab == t, onSelect = { onTabSelected(t) })
            }
        }

        Box(Modifier.weight(1f))

        NavIconButton(icon = Icons.Filled.Settings, desc = "Settings", onClick = onSettings)
    }
}

/** A focusable nav icon button (search / settings / avatar). */
@Composable
private fun NavIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent,
    shape: RoundedCornerShape = RoundedCornerShape(50)
) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.08f),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = if (containerColor == Color.Transparent) Color(0x33FFFFFF) else containerColor
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)
        )
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** A nav link rendered as a light rounded pill when active/focused. */
@Composable
private fun NavPill(label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val active = selected || focused
    Card(
        onClick = onSelect,
        scale = CardDefaults.scale(focusedScale = 1f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(50)),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color(0xFFE5E5E5) else Color.Transparent,
            focusedContainerColor = Color(0xFFE5E5E5)
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        modifier = Modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onSelect()
        }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (active) Color(0xFF111111) else Color(0xFFDDDDDD),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/** A pill action button — solid white or translucent — used in the hero. */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    solid: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val content = if (solid) Color.Black else Color.White
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.06f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(24.dp)),
        colors = CardDefaults.colors(
            containerColor = if (solid) Color.White else Color(0x2EFFFFFF),
            focusedContainerColor = if (solid) Color.White else Color(0x59FFFFFF)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(24.dp))
        ),
        modifier = modifier.then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(18.dp)) }
            Text(text, style = MaterialTheme.typography.titleSmall, color = content, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * The rounded hero card: backdrop (Crop) + horizontal & vertical gradient
 * overlays, with the title/meta/description/actions block bottom-left and a
 * small chip badge bottom-right.
 */
@Composable
fun HeroCard(
    item: CatalogItem?,
    extra: HeroExtra?,
    cornerBadge: String?,
    onRemind: () -> Unit,
    onMoreInfo: () -> Unit,
    modifier: Modifier = Modifier,
    heroHeight: Dp = 420.dp,
    primaryFocusRequester: FocusRequester? = null
) {
    Box(
        modifier
            // Fixed height lives here so the card never remeasures when the
            // backdrop swaps from placeholder to loaded (no vertical jump).
            .fillMaxWidth()
            .height(heroHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF12202E))
    ) {
        // Crop fills the fixed box regardless of the source image's aspect ratio,
        // so the container never adapts to the loaded image's dimensions.
        AsyncImage(
            model = item?.backdropUrl ?: item?.posterUrl,
            contentDescription = item?.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Horizontal: dark on the left, transparent by ~55%.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xE0000000),
                    0.3f to Color(0x99000000),
                    0.55f to Color.Transparent
                )
            )
        )
        // Vertical: dark at the bottom, transparent by ~35% up.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.65f to Color.Transparent,
                    1f to Color(0xCC000000)
                )
            )
        )

        if (item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, end = 28.dp, bottom = 26.dp)
                    .fillMaxWidth(0.55f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text("N", color = Color(0xFFC0392B), fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text(
                        if (item.type == MediaType.TV) "SERIES" else "FILM",
                        color = Color(0xFFCCCCCC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    item.title,
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    add(if (item.type == MediaType.TV) "Show" else "Movie")
                    addAll(Genres.namesFor(item.genreIds, max = 1))
                    item.year?.let { add(it) }
                    extra?.contentRating?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString("  ·  "),
                        color = Color(0xFFB5B5B5),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Color(0xFFE5E5E5),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                Row(
                    Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PillButton(
                        text = "Remind me",
                        icon = Icons.Filled.Notifications,
                        solid = true,
                        onClick = onRemind,
                        focusRequester = primaryFocusRequester
                    )
                    PillButton(text = "More info", icon = null, solid = false, onClick = onMoreInfo)
                }
            }
        }

        // Bottom-right chip badge.
        cornerBadge?.takeIf { it.isNotBlank() }?.let {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x8C000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.Event, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(it, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

/** "Your next watch" — a label + a horizontally scrollable row of rounded thumbnails. */
@Composable
fun NextWatchRow(
    items: List<CatalogItem>,
    onSelect: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    Column(modifier.padding(vertical = 16.dp)) {
        Text(
            "Your next watch",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 40.dp, bottom = 10.dp)
        )
        MediaLazyRow(startPadding = 40.dp, itemSpacing = 8.dp) {
            items(items, key = { "${it.type}_${it.id}" }) { item ->
                NextWatchCard(item = item, onClick = { onSelect(item) })
            }
        }
    }
}

@Composable
private fun NextWatchCard(item: CatalogItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.06f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp))
        ),
        modifier = Modifier
            .width(232.dp)
            .aspectRatio(16f / 10f)
    ) {
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Pinned "Modern" hero used on ALL browse tabs (Home / Movies / TV Shows / My List).
// Nuvio-style: the hero occupies only the top ~half of the screen and reflects
// the currently focused row item — backdrop right-aligned with left+bottom fades,
// and a bottom-left title / meta / description block (no buttons; focus lives in
// the rows below). Fixed, viewport-derived height so the container never
// remeasures when the backdrop swaps from placeholder to loaded.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeHero(
    item: CatalogItem?,
    extra: HeroExtra?,
    modifier: Modifier = Modifier,
    bottomReserved: Dp = 0.dp,
    heroVisibleHeight: Dp = 0.dp,
    /** Resolved trailer stream for [item] (null = none/not resolved yet). */
    trailer: TrailerStream? = null,
    /** When true and [trailer] is available, autoplay a muted preview. */
    playTrailer: Boolean = false
) {
    val bg = Color(0xFF0B0B0F)
    // Nuvio "Modern" home hero: pinned to the top ~half of the screen with the
    // content rows always visible below it. The backdrop is right-aligned and
    // fades out to the left and bottom so the title block (and the rows beneath)
    // stay readable. The height is supplied by the caller (fixed, viewport-
    // derived) so the layout never remeasures when the backdrop finishes loading.
    // [bottomReserved] is the height of the rows viewport underneath, so the
    // title block sits just above the first row.
    Box(modifier.background(bg)) {
        AsyncImage(
            model = item?.backdropUrl ?: item?.posterUrl,
            contentDescription = item?.title,
            // Fit shows the WHOLE backdrop (never crops it); anchored top-right so
            // the full artwork sits on the right while the title reads on the left.
            contentScale = ContentScale.Fit,
            alignment = Alignment.TopEnd,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth()
                .then(
                    if (heroVisibleHeight > 0.dp) Modifier.height(heroVisibleHeight)
                    else Modifier.fillMaxSize()
                )
        )

        // Autoplaying muted trailer preview for the selected title, laid directly
        // over the still backdrop (same region + size). It crossfades in and is
        // torn down (ExoPlayer released) whenever [playTrailer]/[trailer] change,
        // so moving focus to another title cleanly stops it. The gradient scrims
        // below render on top of this, keeping the title block legible.
        AnimatedVisibility(
            visible = playTrailer && trailer != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth()
                .then(
                    if (heroVisibleHeight > 0.dp) Modifier.height(heroVisibleHeight)
                    else Modifier.fillMaxSize()
                )
        ) {
            trailer?.let { HeroTrailerLayer(it) }
        }

        // Left fade -> background (title legibility).
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to bg,
                    0.24f to bg.copy(alpha = 0.85f),
                    0.5f to bg.copy(alpha = 0.4f),
                    0.8f to Color.Transparent
                )
            )
        )
        // Bottom fade -> background so the hero blends into the rows beneath it
        // (reaches full background right at the seam where the rows begin, ~56%).
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.34f to Color.Transparent,
                    0.54f to bg.copy(alpha = 0.92f),
                    0.6f to bg
                )
            )
        )

        if (item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 52.dp, end = 80.dp)
                    .padding(bottom = bottomReserved + 14.dp)
                    .fillMaxWidth(0.5f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    item.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    add(if (item.type == MediaType.TV) "Series" else "Movie")
                    addAll(Genres.namesFor(item.genreIds, max = 1))
                    item.year?.let { add(it) }
                    extra?.runtimeLabel?.let { add(it) }
                    extra?.episodesLabel?.let { add(it) }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // IMDb rating (NuvioTV-style): gold "IMDb" badge + the value.
                    extra?.imdbRating?.takeIf { it > 0.0 }?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFF5C518))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("IMDb", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                            Text(
                                "  ${String.format(java.util.Locale.US, "%.1f", r)}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString("   •   "),
                            color = Color(0xFFBFBFC6),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    extra?.contentRating?.takeIf { it.isNotBlank() }?.let { rating ->
                        Box(
                            Modifier
                                .border(BorderStroke(1.dp, Color(0x66FFFFFF)), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(rating, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Color(0xFFE0E0E0),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Controller-less ExoPlayer that plays a resolved trailer [stream] (with sound)
 * inside the hero. Reuses the same lightweight ExoPlayer approach as the full-
 * screen TrailerScreen (progressive, or merged video+audio for adaptive). The player is
 * keyed on the stream URL so a new hero title rebuilds it, and it's released in
 * onDispose when this leaves composition (hero change / navigation away).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun HeroTrailerLayer(stream: TrailerStream) {
    val context = LocalContext.current
    val exo = remember(stream.videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            val dsf = DefaultDataSource.Factory(context)
            val source = if (stream.audioUrl.isNullOrBlank()) {
                ProgressiveMediaSource.Factory(dsf)
                    .createMediaSource(MediaItem.fromUri(stream.videoUrl))
            } else {
                // Adaptive: merge the separate video + audio tracks.
                MergingMediaSource(
                    ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(stream.videoUrl)),
                    ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(stream.audioUrl))
                )
            }
            // Play WITH sound in the hero. handleAudioFocus = true so it ducks/
            // stops any other audio while previewing and gives focus back when
            // the player is released on hero change / navigation away.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(stream.videoUrl) {
        onDispose { exo.release() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exo
                useController = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Fill the hero area (crop) for a cinematic full-bleed preview.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    )
}

/** Focusable hero action button (Play = solid white, More info = translucent gray). */
@Composable
private fun HeroActionButton(
    text: String,
    icon: ImageVector,
    primary: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val content = if (primary) Color.Black else Color.White
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.06f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(4.dp)),
        colors = CardDefaults.colors(
            containerColor = if (primary) Color.White else Color(0x806D6D6E),
            focusedContainerColor = if (primary) Color.White else Color(0xB36D6D6E)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(4.dp))
        ),
        modifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Text(text, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
