package com.streambert.tv.ui.detail

import androidx.activity.compose.BackHandler
import com.streambert.tv.ui.util.requestFocusAfterFrames
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streambert.tv.data.badges.CompiledBadge
import com.streambert.tv.data.badges.StreamBadge
import com.streambert.tv.data.badges.badgesFor
import com.streambert.tv.data.model.CastPerson
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.model.MediaType
import com.streambert.tv.R
import com.streambert.tv.data.mdblist.RatingBadge
import com.streambert.tv.data.stream.StreamOption
import com.streambert.tv.data.tmdb.Episode
import com.streambert.tv.data.tmdb.tmdbImage
import com.streambert.tv.data.watched.WatchedRepository
import com.streambert.tv.ui.components.LoadingIndicator
import com.streambert.tv.ui.components.MediaRow
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onPlayAuto: (season: Int, episode: Int, title: String, poster: String, backdrop: String) -> Unit,
    onPlayStream: (title: String, url: String, hash: String, poster: String, backdrop: String, debrid: String) -> Unit,
    onSelectRelated: (CatalogItem) -> Unit,
    onOpenPerson: (CastPerson) -> Unit,
    onOpenTrailer: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    val poster = state.posterUrl.orEmpty()
    val backdrop = state.backdropUrl.orEmpty()
    var showSources by remember { mutableStateOf(false) }

    // Watched status for the current target (movie, or selected TV episode).
    val headerWatchedKey: String? = if (state.type == MediaType.MOVIE) {
        WatchedRepository.movieKey(state.id)
    } else {
        state.selectedEpisode?.let { WatchedRepository.episodeKey(state.id, state.selectedSeason, it.episodeNumber) }
    }
    val headerWatched = headerWatchedKey != null && headerWatchedKey in state.watchedKeys

    fun targetSeason() = if (state.type == MediaType.TV) state.selectedSeason else -1
    fun targetEpisode() = if (state.type == MediaType.TV) state.selectedEpisode?.episodeNumber ?: -1 else -1
    fun targetTitle(): String = if (state.type == MediaType.TV) {
        val ep = state.selectedEpisode
        if (ep != null) "${state.title} S${state.selectedSeason}E${ep.episodeNumber}" else state.title
    } else state.title

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item {
                Header(
                    state = state,
                    watched = headerWatched,
                    onPlay = {
                        // Fast path (Debrify-style): the Detail page has ALREADY
                        // scraped + ranked + cache-checked the sources for this
                        // target (top = best cached). Hand that straight to the
                        // player so it skips re-scraping and jumps to resolving
                        // just that one source. Only when the list is ready for
                        // the current target; otherwise fall back to a full
                        // auto-resolve.
                        val best = state.sources.firstOrNull()
                            ?.takeIf { !state.sourcesLoading && (!it.url.isNullOrBlank() || !it.hash.isNullOrBlank()) }
                        if (best != null) {
                            onPlayStream(targetTitle(), best.url.orEmpty(), best.hash.orEmpty(), poster, backdrop, best.debrid.orEmpty())
                        } else {
                            onPlayAuto(targetSeason(), targetEpisode(), targetTitle(), poster, backdrop)
                        }
                    },
                    onSources = { showSources = true },
                    onTrailer = state.trailerKey?.let { { onOpenTrailer(state.trailerKeys.joinToString(",")) } },
                    onToggleWatched = viewModel::toggleWatched,
                    onToggleMyList = viewModel::toggleMyList
                )
            }

            if (state.cast.isNotEmpty()) {
                item { CastRow(state.cast, onOpenPerson = onOpenPerson) }
            }

            if (state.type == MediaType.TV) {
                item { SeasonSelector(state, onSelect = viewModel::selectSeason) }
                item {
                    EpisodeRow(
                        state = state,
                        onSelect = { ep ->
                            // Clicking an episode auto-plays it (per product requirement).
                            viewModel.selectEpisode(ep)
                            onPlayAuto(
                                state.selectedSeason,
                                ep.episodeNumber,
                                "${state.title} S${state.selectedSeason}E${ep.episodeNumber}",
                                poster,
                                backdrop
                            )
                        }
                    )
                }
            }

            if (state.recommendations.isNotEmpty()) {
                item {
                    MediaRow(
                        title = "More Like This",
                        items = state.recommendations,
                        onSelect = onSelectRelated
                    )
                }
            }
        }

        if (showSources) {
            SourcesOverlay(
                state = state,
                onPlayOption = { option ->
                    showSources = false
                    onPlayStream(targetTitle(), option.url.orEmpty(), option.hash.orEmpty(), poster, backdrop, option.debrid.orEmpty())
                },
                onDismiss = { showSources = false }
            )
        }
    }
}

// ── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun Header(
    state: DetailUiState,
    watched: Boolean,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onTrailer: (() -> Unit)?,
    onToggleWatched: () -> Unit,
    onToggleMyList: () -> Unit
) {
    val playFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { playFocus.requestFocusAfterFrames() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(460.dp)
    ) {
        AsyncImage(
            model = state.backdropUrl ?: state.posterUrl,
            contentDescription = state.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20B0B0F),
                        0.55f to Color(0x800B0B0F),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1f to Color(0xF20B0B0F)
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, end = 48.dp, bottom = 22.dp)
                .fillMaxWidth(0.62f)
        ) {
            Text(
                state.title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                state.genres.take(2).joinToString(" / ").ifBlank { null },
                state.year,
                state.runtimeLabel
            ).joinToString("  |  ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCFCFCF),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (state.mdbRatings.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.mdbRatings.take(7).forEach { badge ->
                        RatingBadgeItem(badge)
                    }
                }
            } else if (state.imdbRating != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFFF5C518))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("IMDb", color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                    }
                    Text(
                        "  ${"%.1f".format(state.imdbRating)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (state.overview.isNotBlank()) {
                Text(
                    state.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFDDDDDD),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            // Action row: Play + source picker + trailer + watched + My List.
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocus),
                    colors = ButtonDefaults.colors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text("Play", modifier = Modifier.padding(start = 6.dp, end = 8.dp))
                }
                CircleIcon(Icons.Filled.Tune, "Pick source / quality", onClick = onSources)
                if (onTrailer != null) CircleIcon(Icons.Filled.Movie, "Trailer", onClick = onTrailer)
                CircleIcon(
                    if (watched) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    "Mark watched",
                    active = watched,
                    onClick = onToggleWatched
                )
                CircleIcon(
                    if (state.inMyList) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    "My List",
                    active = state.inMyList,
                    onClick = onToggleMyList
                )
            }
        }
    }
}

@Composable
private fun CircleIcon(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ── Cast ───────────────────────────────────────────────────────────────────

/** Maps an MDBList rating source to its bundled brand logo (res/raw), NuvioTV-style. */
private fun ratingLogoRes(source: String): Int? = when (source) {
    "imdb" -> R.raw.rating_imdb
    "trakt" -> R.raw.rating_trakt
    "tmdb" -> R.raw.rating_tmdb_logo
    "letterboxd" -> R.raw.rating_letterboxd
    "tomatoes" -> R.raw.rating_tomatoes
    "metacritic" -> R.raw.rating_metacritic
    "audience" -> R.raw.rating_audience
    else -> null
}

/** NuvioTV-style rating: a small brand logo + the value, no background pill. */
@Composable
private fun RatingBadgeItem(badge: RatingBadge) {
    val logo = ratingLogoRes(badge.source)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = badge.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(18.dp)
                    .widthIn(max = 56.dp)
            )
        } else {
            Text(
                badge.label,
                color = Color(0xFFB0B0B8),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "  ${badge.display}",
            color = Color(0xFFE7E7EA),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CastRow(cast: List<CastPerson>, onOpenPerson: (CastPerson) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            "Cast",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(cast, key = { it.name + it.role }) { person ->
                CastAvatar(person, onClick = { if (person.id > 0) onOpenPerson(person) })
            }
        }
    }
}

@Composable
private fun CastAvatar(person: CastPerson, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(92.dp)
    ) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape = CircleShape),
            scale = CardDefaults.scale(focusedScale = 1.1f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = CircleShape)
            ),
            modifier = Modifier.size(84.dp)
        ) {
            if (person.imageUrl != null) {
                AsyncImage(
                    model = person.imageUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF2A2A33)), contentAlignment = Alignment.Center) {
                    Text(
                        person.name.take(1).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
        Text(
            person.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (person.role.isNotBlank()) {
            Text(
                person.role,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Sources overlay ──────────────────────────────────────────────────────────

@Composable
private fun SourcesOverlay(
    state: DetailUiState,
    onPlayOption: (StreamOption) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    // Filter tabs: "All" + one per debrid service (TorBox / RD) + one per add-on.
    val debrids = remember(state.sources) {
        state.sources.mapNotNull { it.debrid }.distinct()
    }
    val providers = remember(state.sources) {
        state.sources.map { it.provider.ifBlank { "Add-on" } }.distinct()
    }
    val tabs = remember(debrids, providers) { listOf("All") + debrids + providers }
    var selectedTab by remember(state.sources) { mutableStateOf("All") }
    val visible = remember(state.sources, selectedTab) {
        when (selectedTab) {
            "All" -> state.sources
            // A tab matches its debrid service OR its add-on, so selecting
            // "TorBox"/"RD" shows every release playable via that service.
            else -> state.sources.filter {
                it.debrid == selectedTab || it.provider.ifBlank { "Add-on" } == selectedTab
            }
        }
    }

    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(state.sourcesLoading) {
        if (!state.sourcesLoading) firstTabFocus.requestFocusAfterFrames()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            Modifier
                .width(560.dp)
                .fillMaxHeight()
                .background(Color(0xF00B0B0F))
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            // Filter pills (only when we actually have results).
            if (tabs.size > 1 && !state.sourcesLoading) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(tabs, key = { it }) { tab ->
                        AddonTab(
                            label = tab,
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            focusRequester = if (tab == "All") firstTabFocus else null
                        )
                    }
                }
                Box(Modifier.height(16.dp))
            }

            when {
                state.sourcesLoading -> Box(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator() }

                state.sources.isEmpty() -> Text(
                    state.sourcesError ?: "No sources available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { "${it.hash ?: it.url ?: it.label}_${it.debrid}" }) { option ->
                        SourceCard(
                            option = option,
                            badgeFilters = state.badgeFilters,
                            onClick = { onPlayOption(option) }
                        )
                    }
                }
            }
        }
    }
}

/** Rounded filter pill for an add-on (or "All"). White when selected. */
@Composable
private fun AddonTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(22.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color.White else Color(0x1FFFFFFF),
            focusedContainerColor = if (selected) Color.White else Color(0x3DFFFFFF)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(22.dp))
        ),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Color.Black else Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp)
        )
    }
}

/** NuvioTV-style stream card: "<addon> - <quality>", an icon meta row, the raw
 *  release filename, and the source name on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    option: StreamOption,
    badgeFilters: List<CompiledBadge>,
    onClick: () -> Unit
) {
    val provider = option.provider.ifBlank { "Add-on" }
    val badges = remember(option.label, badgeFilters) { badgeFilters.badgesFor(option.label) }
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        colors = CardDefaults.colors(
            containerColor = Color(0xB315151B),
            focusedContainerColor = Color(0xFF1C1C24)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFDDDDDD)), shape = RoundedCornerShape(12.dp))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        if (option.instant) append("\u26A1 ")
                        // Lead with the debrid service (TorBox / RD) so the two
                        // read as distinct options; fall back to the add-on name.
                        append(option.debrid ?: provider); append(" - "); append(option.qualityLabel)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Icon meta row: quality | language | size | container | info.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    MetaItem(Icons.Filled.Hd, option.qualityLabel)
                    MetaSeparator()
                    MetaItem(Icons.Filled.Language, option.language)
                    option.sizeLabel?.let { MetaSeparator(); MetaItem(Icons.Filled.Save, it) }
                    option.container?.let { MetaSeparator(); MetaItem(Icons.Filled.Movie, it) }
                    MetaSeparator()
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFF4C9AFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB7B7BF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                // Quality badges (4K / HDR10+ / Dolby Vision / REMUX / HEVC / DD+ / 7.1 …).
                // Offline text pills from the release title always show; NuvioTV-style
                // matched image badges layer in when a badge config is loaded.
                if (option.badges.isNotEmpty() || badges.isNotEmpty() || option.sizeLabel != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        option.badges.forEach { label -> TextBadgeChip(label) }
                        badges.forEach { badge -> StreamBadgeChip(badge) }
                        option.sizeLabel?.let { SizeBadgeChip(it) }
                    }
                }
            }
            Text(
                provider,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A8A93),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp).width(88.dp)
            )
        }
    }
}

/** A colored text pill for a parsed release badge (4K, HDR10+, DOLBY VISION, REMUX …). */
@Composable
private fun TextBadgeChip(label: String) {
    // Color-code by badge family so the panel reads at a glance, Netflix/Nuvio-style.
    val bg = when (label.uppercase()) {
        "4K", "2160P" -> Color(0xFFF5A623)                     // amber – top resolution
        "1080P", "720P", "480P" -> Color(0xFF3D6FB3)           // blue – resolution
        "DOLBY VISION", "HDR10+", "HDR" -> Color(0xFF8E5BD1)   // purple – dynamic range
        "REMUX" -> Color(0xFF1FA9A0)                           // teal – premium source
        "BLURAY", "WEB-DL", "WEBRIP", "HDTV" -> Color(0xFF4A5568) // slate – source
        "HEVC", "AV1", "H.264" -> Color(0xFF556070)            // gray-blue – codec
        "ATMOS", "TRUEHD", "DTS:X", "DTS-HD", "DTS", "DD+", "DD", "AAC" -> Color(0xFF2E9E5B) // green – audio
        "7.1", "5.1", "2.0" -> Color(0xFF6B7280)               // neutral – channels
        else -> Color(0xFF3A3A44)
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(22.dp)
            .background(bg, shape)
            .clip(shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** A single matched badge rendered as its remote image, with optional tag/border color. */
@Composable
private fun StreamBadgeChip(badge: StreamBadge) {
    val shape = RoundedCornerShape(6.dp)
    val bg = badge.backgroundColorOrNull
    val border = badge.borderColorOrNull
    Box(
        modifier = Modifier
            .height(22.dp)
            .then(if (bg != null) Modifier.background(bg, shape) else Modifier)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clip(shape)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = badge.imageUrl,
            contentDescription = badge.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(18.dp)
                .widthIn(min = 28.dp, max = 96.dp)
        )
    }
}

/** Text pill showing the release size (e.g. "SIZE 55 GB"), styled like Nuvio's. */
@Composable
private fun SizeBadgeChip(sizeLabel: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(22.dp)
            .background(Color(0xFF0A0C0C), shape)
            .clip(shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "SIZE $sizeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color(0xFFB0B0B8),
            modifier = Modifier.size(15.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFD5D5DD),
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp)
        )
    }
}

@Composable
private fun MetaSeparator() {
    Text(
        "  |  ",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0x4DFFFFFF)
    )
}

// ── TV season/episode ────────────────────────────────────────────────────────

@Composable
private fun SeasonSelector(state: DetailUiState, onSelect: (Int) -> Unit) {
    if (state.seasons.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Seasons",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.seasons, key = { it.id }) { season ->
                val selected = season.seasonNumber == state.selectedSeason
                Button(
                    onClick = { onSelect(season.seasonNumber) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("S${season.seasonNumber}", modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(state: DetailUiState, onSelect: (Episode) -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
        )
        if (state.episodesLoading) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.CenterStart) {
                LoadingIndicator()
            }
            return
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(state.episodes, key = { it.id }) { ep ->
                EpisodeCard(
                    ep = ep,
                    selected = state.selectedEpisode?.id == ep.id,
                    watched = WatchedRepository.episodeKey(state.id, state.selectedSeason, ep.episodeNumber) in state.watchedKeys,
                    onClick = { onSelect(ep) }
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(ep: Episode, selected: Boolean, watched: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(260.dp)) {
        Card(
            onClick = onClick,
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary))
            ),
            modifier = Modifier.fillMaxWidth().height(146.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = tmdbImage(ep.stillPath, "w300"),
                    contentDescription = ep.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .background(Color(0x99000000), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Select", tint = Color.White)
                }
                if (watched) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Watched",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Text(
            "${ep.episodeNumber}. ${ep.name ?: "Episode ${ep.episodeNumber}"}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
