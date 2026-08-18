package com.streambert.tv.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.model.CatalogRow
import com.streambert.tv.data.progress.WatchProgress
import com.streambert.tv.data.tmdb.Genre
import com.streambert.tv.data.tmdb.Genres
import com.streambert.tv.data.tmdb.StreamingService
import com.streambert.tv.data.tmdb.StreamingServices
import com.streambert.tv.ui.components.ContinueWatchingRow
import com.streambert.tv.ui.components.LoadingIndicator
import com.streambert.tv.ui.components.MediaCard
import com.streambert.tv.ui.components.MediaLazyRow
import com.streambert.tv.ui.components.MediaOptionsDialog
import com.streambert.tv.ui.util.requestFocusAfterFrames
import com.streambert.tv.ui.components.RatingChip
import com.streambert.tv.ui.components.StandardRow
import com.streambert.tv.ui.components.Top10Row
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
fun HomeScreen(
    viewModel: HomeViewModel,
    onSelect: (CatalogItem) -> Unit,
    onResume: (WatchProgress) -> Unit,
    onOpenService: (StreamingService) -> Unit,
    onOpenGenre: (Genre, String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(HomeTab.HOME) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }

            state.error != null && state.homeRows.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium
                )
                Button(onClick = { viewModel.load() }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Retry")
                }
            }

            else -> Box(Modifier.fillMaxSize()) {
                // Every tab (incl. My List) uses the same layout: hero, genres,
                // services, then that tab's rows.
                BrowseContent(
                    tab = tab,
                    state = state,
                    onSelect = onSelect,
                    onResume = onResume,
                    onOpenService = onOpenService,
                    onOpenGenre = onOpenGenre,
                    onToggleMyList = viewModel::toggleMyList,
                    onMarkWatched = viewModel::markWatched,
                    onMarkUnwatched = viewModel::markUnwatched,
                    onHeroChanged = viewModel::loadHeroExtra,
                    onRequestTrailer = viewModel::loadHeroTrailer
                )
                // Transparent nav bar overlaid on the full-bleed hero (the hero's
                // top scrim + the title's top offset keep everything legible).
                TopNavBar(
                    selectedTab = tab,
                    onTabSelected = { tab = it },
                    onSearch = onSearch,
                    onSettings = onSettings,
                    onProfile = onSettings,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowseContent(
    tab: HomeTab,
    state: HomeUiState,
    onSelect: (CatalogItem) -> Unit,
    onResume: (WatchProgress) -> Unit,
    onOpenService: (StreamingService) -> Unit,
    onOpenGenre: (Genre, String) -> Unit,
    onToggleMyList: (CatalogItem) -> Unit,
    onMarkWatched: (CatalogItem) -> Unit,
    onMarkUnwatched: (CatalogItem) -> Unit,
    onHeroChanged: (CatalogItem) -> Unit,
    onRequestTrailer: (CatalogItem) -> Unit
) {
    val rows: List<CatalogRow> = when (tab) {
        HomeTab.SHOWS -> state.showsRows
        HomeTab.MOVIES -> state.moviesRows
        HomeTab.MY_LIST -> buildList {
            if (state.myList.isNotEmpty()) add(CatalogRow("My List", state.myList))
            if (state.traktWatchlist.isNotEmpty()) add(CatalogRow("Trakt Watchlist", state.traktWatchlist))
        }
        else -> state.homeRows
    }
    val defaultHero: CatalogItem? = when (tab) {
        HomeTab.HOME -> state.hero
        else -> rows.firstOrNull()?.items?.firstOrNull()
    }
    // The fixed top preview follows the currently-focused card.
    var focused by remember(tab) { mutableStateOf<CatalogItem?>(null) }

    // When idle (nothing focused), rotate the hero through the latest/trending
    // titles every 15s — like Netflix's billboard.
    val heroPool = remember(rows) {
        rows.flatMap { it.items }.distinctBy { "${it.type}_${it.id}" }.take(15)
    }
    var rotIndex by remember(tab) { mutableStateOf(0) }
    LaunchedEffect(heroPool.size, tab) {
        if (heroPool.size > 1) {
            while (true) {
                delay(15_000)
                rotIndex = (rotIndex + 1) % heroPool.size
            }
        }
    }
    val rotatingDefault = heroPool.getOrNull(rotIndex) ?: defaultHero
    val heroItem = focused ?: rotatingDefault

    // Debounced fetch of the featured title's extra info (episodes/runtime/rating).
    LaunchedEffect(heroItem?.id, heroItem?.type) {
        val h = heroItem ?: return@LaunchedEffect
        delay(350)
        onHeroChanged(h)
    }

    // Autoplay a muted trailer preview on the hero for the SELECTED (focused)
    // title. Deliberately only fires when the user has focused a card — not
    // during the idle billboard rotation — and after a short dwell so quickly
    // scrubbing through cards doesn't spin up trailers. Re-keying on the hero
    // item + focus resets the timer and (via HomeHero) tears down the previous
    // player when focus moves; returning to a title replays it from cache.
    var trailerReady by remember(tab) { mutableStateOf(false) }
    LaunchedEffect(heroItem?.id, heroItem?.type, focused != null) {
        trailerReady = false
        val h = heroItem ?: return@LaunchedEffect
        if (focused == null) return@LaunchedEffect
        delay(2_500)
        onRequestTrailer(h)
        trailerReady = true
    }
    val heroTrailer = heroItem?.let { state.heroTrailers["${it.type}_${it.id}"] }

    // Netflix-style "TOP 10 · #N in {Shows|Movies}" badge when the focused title is
    // in one of the ranked (Top 10) rows.
    val topTenRank: Int? = heroItem?.let { h ->
        rows.filter { it.ranked }.firstNotNullOfOrNull { r ->
            r.items.indexOfFirst { it.id == h.id && it.type == h.type }.takeIf { it >= 0 }?.plus(1)
        }
    }

    // Long-press options menu target (null = dialog hidden).
    var optionsItem by remember { mutableStateOf<CatalogItem?>(null) }

    // The hero has no buttons (Nuvio modern home) — focus lives in the rows, so
    // on entry we land on the first genre chip (the first focusable in the pinned
    // rows viewport, always on-screen in the bottom half).
    val firstCardFocus = remember { FocusRequester() }
    // Request focus ONCE on first load — never re-fire on tab switches, which
    // previously yanked focus down from the nav bar and looked like focus
    // "jumping to random items".
    var didAutoFocus by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty() && !didAutoFocus) {
            didAutoFocus = true
            firstCardFocus.requestFocusAfterFrames()
        }
    }

    // Nuvio "Modern" home: the hero backdrop occupies the top ~56% of the screen
    // (shown in full) and the content rows fill the bottom ~44% (they scroll
    // within their own viewport; the hero stays put and reflects the focused row).
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val rowsViewportHeight = screenHeight * 0.44f
    val heroVisibleHeight = screenHeight - rowsViewportHeight
    Box(Modifier.fillMaxSize()) {
        // Pinned hero — reflects the currently focused row item (no buttons; focus
        // lives in the rows, exactly like Nuvio's modern home).
        HomeHero(
            item = heroItem,
            extra = heroItem?.let { state.heroExtras["${it.type}_${it.id}"] },
            trailer = heroTrailer,
            playTrailer = trailerReady,
            modifier = Modifier.fillMaxSize(),
            bottomReserved = rowsViewportHeight,
            heroVisibleHeight = heroVisibleHeight
        )

        // Content rows pinned to the bottom half — always visible; focus starts here.
        // Solid background so the cards never sit on the still-visible backdrop; the
        // hero's bottom fade blends into this seam right at the halfway line.
        LazyColumn(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(rowsViewportHeight)
                .background(Color(0xFF0B0B0F)),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            // Genre quick-filter chips (all tabs). Movies/Shows restrict to their type.
            item {
                val genreMedia = when (tab) {
                    HomeTab.MOVIES -> "movie"
                    HomeTab.SHOWS -> "tv"
                    else -> "all"
                }
                GenreChipsRow(
                    onOpenGenre = { onOpenGenre(it, genreMedia) },
                    firstItemFocusRequester = firstCardFocus
                )
            }
            // Services row (all tabs).
            item { ServicesRow(onOpenService = onOpenService) }

            if (tab == HomeTab.HOME) {
                if (state.continueWatching.isNotEmpty()) {
                    item {
                        ContinueWatchingRow(
                            entries = state.continueWatching,
                            onResume = onResume,
                            onFocus = { p ->
                                focused = CatalogItem(
                                    id = p.tmdbId, type = p.mediaType, title = p.title,
                                    overview = null, posterUrl = p.posterUrl,
                                    backdropUrl = p.backdropUrl, rating = 0.0, year = null
                                )
                            },
                            onLongPress = { p ->
                                optionsItem = CatalogItem(
                                    id = p.tmdbId, type = p.mediaType, title = p.title,
                                    overview = null, posterUrl = p.posterUrl,
                                    backdropUrl = p.backdropUrl, rating = 0.0, year = null
                                )
                            }
                        )
                    }
                }
                // Trakt watchlist row (only when connected + non-empty).
                if (state.traktWatchlist.isNotEmpty()) {
                    item(key = "trakt_watchlist") {
                        StandardRow(
                            title = "Your Trakt Watchlist",
                            items = state.traktWatchlist,
                            onSelect = onSelect,
                            onFocus = { focused = it },
                            onLongPress = { optionsItem = it },
                            firstItemFocusRequester = null
                        )
                    }
                }
                // Personalized recommendation rows, placed high like Netflix's "Top Picks".
                items(state.recommendedRows, key = { "rec_${it.title}" }) { row ->
                    StandardRow(
                        title = row.title,
                        items = row.items,
                        onSelect = onSelect,
                        onFocus = { focused = it },
                        onLongPress = { optionsItem = it },
                        firstItemFocusRequester = null
                    )
                }
            }

            itemsIndexed(rows, key = { _, it -> it.title }) { index, row ->
                val firstReq: FocusRequester? = null
                if (row.ranked) {
                    Top10Row(
                        title = row.title,
                        items = row.items,
                        onSelect = onSelect,
                        onFocus = { focused = it },
                        onLongPress = { optionsItem = it },
                        firstItemFocusRequester = firstReq
                    )
                } else {
                    StandardRow(
                        title = row.title,
                        items = row.items,
                        onSelect = onSelect,
                        onFocus = { focused = it },
                        onLongPress = { optionsItem = it },
                        firstItemFocusRequester = firstReq
                    )
                }
            }
        }
    }

    // Netflix/Nuvio-style quick options menu on long-press.
    optionsItem?.let { item ->
        val inList = state.myList.any { it.id == item.id && it.type == item.type }
        MediaOptionsDialog(
            item = item,
            isInMyList = inList,
            onViewDetails = { onSelect(item) },
            onToggleLibrary = { onToggleMyList(item) },
            onMarkWatched = { onMarkWatched(item) },
            onMarkUnwatched = { onMarkUnwatched(item) },
            onDismiss = { optionsItem = null }
        )
    }
}

/** Full-screen billboard: backdrop + Netflix-style badges, title, dotted meta, overview, buttons. */
@Composable
private fun HeroPreview(
    item: CatalogItem?,
    topTenRank: Int? = null,
    extra: com.streambert.tv.ui.home.HeroExtra? = null,
    expanded: Boolean = false,
    onPlay: (() -> Unit)? = null,
    onMoreInfo: (() -> Unit)? = null,
    heightModifier: Modifier = Modifier.height(320.dp),
    playFocusRequester: FocusRequester? = null
) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(heightModifier)
    ) {
        AsyncImage(
            model = item?.backdropUrl ?: item?.posterUrl,
            contentDescription = item?.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20B0B0F),
                        0.55f to Color(0x660B0B0F),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xF20B0B0F)
                    )
                )
        )
        // Top scrim so the overlaid nav bar stays legible over bright artwork.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x99000000),
                        0.18f to Color.Transparent
                    )
                )
        )
        if (item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, end = 48.dp, bottom = 16.dp)
                    .fillMaxWidth(0.6f)
                    .animateContentSize()
            ) {
                // Badge row: TOP 10 pill (+ rank) like Netflix.
                if (topTenRank != null) {
                    val label = if (item.type == com.streambert.tv.data.model.MediaType.TV) "TV Shows" else "Movies"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFE50914))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "TOP 10",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            "  #$topTenRank in $label",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // Small type label above the title (Netflix "SERIES"/"FILM" style).
                Text(
                    if (item.type == com.streambert.tv.data.model.MediaType.TV) "S E R I E S" else "F I L M",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFB9B9C2),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Netflix-style dotted metadata line: Genre · Year · Episodes · Rating.
                val metaParts = buildList {
                    addAll(Genres.namesFor(item.genreIds, max = 2))
                    item.year?.let { add(it) }
                    extra?.episodesLabel?.let { add(it) }
                    extra?.runtimeLabel?.let { add(it) }
                }
                if (metaParts.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            metaParts.joinToString("  ·  "),
                            color = Color(0xFFCFCFCF),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // Content-rating pill (e.g. TV-PG / PG-13), Netflix-style.
                        extra?.contentRating?.takeIf { it.isNotBlank() }?.let { rating ->
                            Box(
                                Modifier
                                    .padding(start = 10.dp)
                                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    rating,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCFCFCF),
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // Action buttons (Netflix-style): Play + More Info.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    if (onPlay != null) {
                        Button(
                            onClick = onPlay,
                            modifier = playFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                Text(
                                    "Play",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 6.dp, end = 8.dp)
                                )
                            }
                        }
                    }
                    if (onMoreInfo != null) {
                        Button(
                            onClick = onMoreInfo,
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0x55FFFFFF),
                                contentColor = Color.White
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text(
                                    "More Info",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 6.dp, end = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Genre quick-filter chips (Netflix-style pills under the top nav) ─────────

@Composable
private fun GenreChipsRow(
    onOpenGenre: (Genre) -> Unit,
    firstItemFocusRequester: FocusRequester? = null
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(Genres.ALL, key = { _, it -> it.name }) { index, genre ->
            GenreChip(
                genre = genre,
                onClick = { onOpenGenre(genre) },
                modifier = if (index == 0 && firstItemFocusRequester != null) {
                    Modifier.focusRequester(firstItemFocusRequester)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun GenreChip(genre: Genre, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(24.dp)),
        colors = CardDefaults.colors(containerColor = Color(0xFF26262E)),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(24.dp))
        )
    ) {
        Text(
            genre.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)
        )
    }
}

// ── Services row ─────────────────────────────────────────────────────────────

@Composable
private fun ServicesRow(
    onOpenService: (StreamingService) -> Unit
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        SectionTitle("Services")
        MediaLazyRow(startPadding = 48.dp, itemSpacing = 14.dp) {
            items(StreamingServices.ALL, key = { it.providerId }) { service ->
                ServiceCard(service = service, onClick = { onOpenService(service) })
            }
        }
    }
}

@Composable
private fun ServiceCard(service: StreamingService, onClick: () -> Unit) {
    // Landscape cover: the bundled service artwork fills the card edge-to-edge.
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.12f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp))
        ),
        modifier = Modifier
            .width(150.dp)
            .height(84.dp)
    ) {
        Image(
            painter = painterResource(service.logoRes),
            contentDescription = service.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(service.brandColor))
        )
    }
}

// ── Genres row (landscape cover art) ─────────────────────────────────────────

@Composable
private fun GenresRow(onOpenGenre: (Genre) -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        SectionTitle("Genres")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(Genres.ALL, key = { it.name }) { genre ->
                GenreCard(genre = genre, onClick = { onOpenGenre(genre) })
            }
        }
    }
}

@Composable
private fun GenreCard(genre: Genre, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary))
        ),
        modifier = Modifier
            .width(240.dp)
            .height(135.dp)
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1A1A22))) {
            AsyncImage(
                model = genre.coverUrl,
                contentDescription = genre.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Fallback label (also readable if the cover art fails to load).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x99000000))
                    )
            )
            Text(
                genre.name,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp)
    )
}

/**
 * My List tab, Netflix-style: the focused title is shown as a big landscape
 * image on the LEFT with its details (genre · year · runtime · rating +
 * synopsis) directly beneath it, while the saved titles and the user's Trakt
 * watchlist appear as poster rows on the RIGHT. Moving focus across the posters
 * updates the left preview live.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MyListContent(
    state: HomeUiState,
    onSelect: (CatalogItem) -> Unit,
    onHeroChanged: (CatalogItem) -> Unit
) {
    val myList = state.myList
    val trakt = state.traktWatchlist
    val hasAny = myList.isNotEmpty() || trakt.isNotEmpty()

    if (!hasAny) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Your list is empty. Open a title and choose \u201cMy List\u201d to add it, or connect Trakt in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium
            )
        }
        return
    }

    // The left preview follows the focused card; defaults to the first title.
    var focused by remember { mutableStateOf<CatalogItem?>(null) }
    val heroItem = focused ?: myList.firstOrNull() ?: trakt.first()

    // Debounced fetch of the featured title's extra info (episodes/runtime/rating).
    LaunchedEffect(heroItem.id, heroItem.type) {
        delay(350)
        onHeroChanged(heroItem)
    }

    // Land focus on the first poster so the D-pad drives the right-hand rows.
    val firstPosterFocus = remember { FocusRequester() }
    var didAutoFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasAny) {
        if (hasAny && !didAutoFocus) {
            didAutoFocus = true
            firstPosterFocus.requestFocusAfterFrames()
        }
    }

    Row(Modifier.fillMaxSize().padding(top = 60.dp)) {
        // LEFT — focused-title detail panel (top offset clears the overlaid nav).
        FocusedDetailPanel(
            item = heroItem,
            extra = state.heroExtras["${heroItem.type}_${heroItem.id}"],
            modifier = Modifier
                .weight(0.42f)
                .fillMaxSize()
                .padding(start = 48.dp, top = 20.dp, end = 24.dp)
        )
        // RIGHT — poster rows; focus here drives the left preview.
        LazyColumn(
            modifier = Modifier.weight(0.58f).fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)
        ) {
            if (myList.isNotEmpty()) {
                item(key = "row_my_list") {
                    PosterRow(
                        title = "My List",
                        items = myList,
                        onSelect = onSelect,
                        onFocus = { focused = it },
                        firstItemFocusRequester = firstPosterFocus
                    )
                }
            }
            if (trakt.isNotEmpty()) {
                item(key = "row_trakt") {
                    PosterRow(
                        title = "Trakt Watchlist",
                        items = trakt,
                        onSelect = onSelect,
                        onFocus = { focused = it },
                        firstItemFocusRequester = if (myList.isEmpty()) firstPosterFocus else null
                    )
                }
            }
        }
    }
}

/** The left-hand preview: a big landscape image + title, dotted meta and synopsis below it. */
@Composable
private fun FocusedDetailPanel(
    item: CatalogItem,
    extra: HeroExtra?,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        // Landscape key art of the focused title.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A22))
        ) {
            AsyncImage(
                model = item.backdropUrl ?: item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp)
        )
        // Dotted meta line: Genre · Year · Runtime/Episodes · Content rating.
        val metaParts = buildList {
            addAll(Genres.namesFor(item.genreIds, max = 2))
            item.year?.let { add(it) }
            extra?.runtimeLabel?.let { add(it) }
            extra?.episodesLabel?.let { add(it) }
            extra?.contentRating?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (metaParts.isNotEmpty()) {
            Text(
                metaParts.joinToString("  ·  "),
                color = Color(0xFFB7B7BF),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        item.overview?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFCFCFCF),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** A titled horizontal row of portrait poster cards (right side of My List). */
@Composable
private fun PosterRow(
    title: String,
    items: List<CatalogItem>,
    onSelect: (CatalogItem) -> Unit,
    onFocus: (CatalogItem) -> Unit,
    firstItemFocusRequester: FocusRequester? = null
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)
        )
        MediaLazyRow(startPadding = 8.dp, itemSpacing = 14.dp) {
            itemsIndexed(items, key = { _, it -> "${it.type}_${it.id}" }) { index, item ->
                MediaCard(
                    item = item,
                    onClick = { onSelect(item) },
                    onFocus = onFocus,
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
        }
    }
}

