package com.streambert.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.model.CatalogRow
import com.streambert.tv.data.model.MediaType
import com.streambert.tv.data.mylist.MyListRepository
import com.streambert.tv.data.progress.ProgressRepository
import com.streambert.tv.data.progress.WatchProgress
import com.streambert.tv.data.reco.RecommendationEngine
import com.streambert.tv.data.tmdb.TmdbRepository
import com.streambert.tv.data.watched.WatchedRepository
import com.streambert.tv.data.tmdb.WatchProviders
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Top-level browse tabs. */
enum class HomeTab(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SHOWS("TV Shows"),
    MY_LIST("My List")
}

data class HomeUiState(
    val loading: Boolean = true,
    val hero: CatalogItem? = null,
    val continueWatching: List<WatchProgress> = emptyList(),
    val homeRows: List<CatalogRow> = emptyList(),
    val showsRows: List<CatalogRow> = emptyList(),
    val moviesRows: List<CatalogRow> = emptyList(),
    val myList: List<CatalogItem> = emptyList(),
    val watchedKeys: Set<String> = emptySet(),
    /** Personalized recommendation rows (shown high on the Home tab). */
    val recommendedRows: List<CatalogRow> = emptyList(),
    /** The signed-in user's Trakt watchlist (empty when not connected). */
    val traktWatchlist: List<CatalogItem> = emptyList(),
    val serviceLogos: Map<Int, String> = emptyMap(),
    /** Lazily-fetched extra hero metadata, keyed by "type_id". */
    val heroExtras: Map<String, HeroExtra> = emptyMap(),
    /**
     * Lazily-resolved hero trailer streams, keyed by "type_id". A present key
     * with a null value means "resolved, but no trailer available" (so we don't
     * keep re-fetching). Populated on demand for the focused hero title.
     */
    val heroTrailers: Map<String, com.streambert.tv.data.trailer.TrailerStream?> = emptyMap(),
    val error: String? = null
)

/** Netflix-style extra info shown under the hero (fetched on demand for the focused title). */
data class HeroExtra(
    val contentRating: String? = null,
    val episodesLabel: String? = null,
    val runtimeLabel: String? = null,
    /** IMDb rating (0–10) from MDBList, shown on the hero like NuvioTV. */
    val imdbRating: Double? = null
)

class HomeViewModel(
    private val repo: TmdbRepository,
    private val progress: ProgressRepository,
    private val myList: MyListRepository,
    private val watched: WatchedRepository,
    private val trakt: com.streambert.tv.data.trakt.TraktRepository,
    private val traktAuth: com.streambert.tv.data.trakt.TraktAuthRepository,
    private val ai: com.streambert.tv.data.gemini.AiCatalog,
    private val mdblist: com.streambert.tv.data.mdblist.MDBListRepository,
    private val youTubeExtractor: com.streambert.tv.data.trailer.YouTubeExtractor,
    private val settings: com.streambert.tv.data.settings.SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val engine = RecommendationEngine(repo)

    init {
        load()
        observeContinueWatching()
        observeMyList()
        observeWatched()
        observeRecommendations()
        observeTraktWatchlist()
        loadServiceLogos()
    }

    /**
     * Loads the Trakt watchlist whenever the connection state flips to
     * authenticated, and clears it on sign-out. Fetch failures leave the row
     * empty (it simply won't render).
     */
    // Guards against re-fetching the watchlist for an unchanged auth identity.
    private var lastTraktSignature: String? = null

    private fun observeTraktWatchlist() {
        viewModelScope.launch {
            traktAuth.authState.collect { st ->
                // Only act when the authenticated identity actually changes. This
                // prevents a request storm: DataStore re-emits on every settings
                // write, and re-loading the watchlist (2 Trakt + ~18 TMDB calls)
                // on each emission would rate-limit our stream endpoints (HTTP 429).
                val signature = if (st.isAuthenticated) "auth:${st.username.orEmpty()}" else "anon"
                if (signature == lastTraktSignature) return@collect
                lastTraktSignature = signature

                if (st.isAuthenticated) {
                    val items = runCatching { trakt.watchlist() }.getOrDefault(emptyList())
                    _state.value = _state.value.copy(traktWatchlist = items)
                } else if (_state.value.traktWatchlist.isNotEmpty()) {
                    _state.value = _state.value.copy(traktWatchlist = emptyList())
                }
            }
        }
    }

    /** Manual refresh (e.g. returning to Home) — re-pulls the watchlist if connected. */
    fun refreshTraktWatchlist() {
        viewModelScope.launch {
            if (traktAuth.authState.first().isAuthenticated) {
                val items = runCatching { trakt.watchlist() }.getOrDefault(emptyList())
                _state.value = _state.value.copy(traktWatchlist = items)
            }
        }
    }

    // ── Recommendation engine ────────────────────────────────────────────────

    /**
     * Recomputes personalized rows whenever the user's signals meaningfully
     * change (a new title watched, saved, or finished). A seed "signature" guard
     * avoids re-fetching on frequent no-op emissions such as playback position
     * updates, which keeps TMDB traffic low.
     */
    private var lastSeedSignature: String? = null

    private fun observeRecommendations() {
        viewModelScope.launch {
            combine(
                progress.continueWatching,
                myList.items,
                watched.watched
            ) { cw, ml, w -> Triple(cw, ml, w) }.collect { (cw, ml, w) ->
                val seeds = buildSeeds(cw, ml)
                val signature = seeds.take(RecommendationEngine.MAX_SEEDS)
                    .joinToString(",") { "${it.type}_${it.id}" }
                if (signature.isBlank() || signature == lastSeedSignature) return@collect
                lastSeedSignature = signature

                val exclude = buildExcludeKeys(cw, ml, w)
                val rows = runCatching { engine.buildRows(seeds, exclude, ml) }
                    .getOrDefault(emptyList())
                _state.value = _state.value.copy(recommendedRows = rows)

                // Additive AI picks row (Gemini), computed once per seed change.
                computeAiPicks(seeds, exclude)
            }
        }
    }

    /**
     * Asks Gemini for personalized picks from the user's top seed titles and
     * prepends them as an "AI Picks For You" row. Best-effort: does nothing if
     * no Gemini key is set or the request fails.
     */
    private fun computeAiPicks(
        seeds: List<RecommendationEngine.Seed>,
        exclude: Set<String>
    ) {
        viewModelScope.launch {
            if (!runCatching { ai.isAvailable() }.getOrDefault(false)) return@launch
            val liked = seeds.take(10).map { it.title }.filter { it.isNotBlank() }
            val picks = runCatching { ai.recommend(liked) }.getOrDefault(emptyList())
                .filter { "${it.type}_${it.id}" !in exclude && it.posterUrl != null }
            if (picks.size >= RecommendationEngine.MIN_ROW) {
                val existing = _state.value.recommendedRows.filterNot { it.title == AI_ROW_TITLE }
                _state.value = _state.value.copy(
                    recommendedRows = listOf(CatalogRow(AI_ROW_TITLE, picks)) + existing
                )
            }
        }
    }

    /** Ranks engagement signals into weighted seeds (recent in-progress > saved). */
    private fun buildSeeds(
        continueWatching: List<WatchProgress>,
        list: List<CatalogItem>
    ): List<RecommendationEngine.Seed> {
        val seeds = LinkedHashMap<String, RecommendationEngine.Seed>()
        continueWatching.sortedByDescending { it.updatedAt }.forEachIndexed { idx, p ->
            val key = "${p.mediaType}_${p.tmdbId}"
            if (key !in seeds && p.title.isNotBlank()) {
                seeds[key] = RecommendationEngine.Seed(
                    id = p.tmdbId, type = p.mediaType, title = p.title,
                    weight = 3.0 / (1.0 + idx * 0.3)
                )
            }
        }
        list.forEachIndexed { idx, item ->
            val key = "${item.type}_${item.id}"
            if (key !in seeds) {
                seeds[key] = RecommendationEngine.Seed(
                    id = item.id, type = item.type, title = item.title,
                    weight = 2.0 / (1.0 + idx * 0.2)
                )
            }
        }
        return seeds.values.toList()
    }

    /** Titles the user already knows (watching / saved / seen) — hidden from recs. */
    private fun buildExcludeKeys(
        continueWatching: List<WatchProgress>,
        list: List<CatalogItem>,
        watchedKeys: Set<String>
    ): Set<String> {
        val out = HashSet<String>()
        continueWatching.forEach { out += "${it.mediaType}_${it.tmdbId}" }
        list.forEach { out += "${it.type}_${it.id}" }
        // Watched keys look like "tv_123" / "movie_123" (series/movie level).
        watchedKeys.forEach { k ->
            val parts = k.split("_")
            if (parts.size == 2) {
                val type = if (parts[0] == "tv") MediaType.TV else MediaType.MOVIE
                parts[1].toIntOrNull()?.let { out += "${type}_$it" }
            }
        }
        return out
    }

    private val heroExtraInFlight = mutableSetOf<String>()

    /**
     * Lazily loads the currently-featured title's extra metadata (episode/season
     * count, runtime, content rating) with a single TMDB detail call, plus the
     * IMDb rating from MDBList (NuvioTV-style, shown on the hero). Results are
     * cached in [HomeUiState.heroExtras] so switching back to a title is instant;
     * MDBList itself caches per title, so the hero rotation won't spam its quota.
     */
    fun loadHeroExtra(item: CatalogItem) {
        val key = "${item.type}_${item.id}"
        if (_state.value.heroExtras.containsKey(key) || key in heroExtraInFlight) return
        heroExtraInFlight += key
        viewModelScope.launch {
            // True IMDb rating from MDBList; fall back to the TMDB vote average we
            // already carry on the item so the hero always shows a number.
            val imdb = runCatching { mdblist.imdbRating(item.id, item.type) }.getOrNull()
                ?: item.rating.takeIf { it > 0.0 }
            val extra = runCatching {
                when (item.type) {
                    MediaType.TV -> {
                        val d = repo.tvDetails(item.id)
                        HeroExtra(
                            contentRating = d.certification,
                            episodesLabel = when {
                                d.numberOfSeasons > 1 -> "${d.numberOfSeasons} Seasons"
                                d.numberOfEpisodes > 0 -> "${d.numberOfEpisodes} Episodes"
                                else -> null
                            },
                            imdbRating = imdb
                        )
                    }
                    else -> {
                        val d = repo.movieDetails(item.id)
                        HeroExtra(
                            contentRating = d.certification,
                            runtimeLabel = d.runtime?.takeIf { it > 0 }?.let(::formatRuntime),
                            imdbRating = imdb
                        )
                    }
                }
            }.getOrNull() ?: HeroExtra(imdbRating = imdb)
            _state.value = _state.value.copy(heroExtras = _state.value.heroExtras + (key to extra))
            heroExtraInFlight -= key
        }
    }

    private val heroTrailerInFlight = mutableSetOf<String>()

    /**
     * Lazily resolves the focused hero title's YouTube trailer to a directly-
     * playable stream (TMDB video keys → [youTubeExtractor]) so the hero can
     * autoplay a muted preview. Cached in [HomeUiState.heroTrailers] (a stored
     * null = "resolved, none") so re-focusing a title replays instantly and we
     * never re-fetch. Honors the "use trailers" setting — when it's off, or the
     * title has no trailer, the value resolves to null and no preview plays.
     */
    fun loadHeroTrailer(item: CatalogItem) {
        val key = "${item.type}_${item.id}"
        if (_state.value.heroTrailers.containsKey(key) || key in heroTrailerInFlight) return
        heroTrailerInFlight += key
        viewModelScope.launch {
            val stream = runCatching {
                if (!settings.currentTmdbUseTrailers()) return@runCatching null
                val keys = repo.trailerYoutubeKeys(item.id, item.type)
                if (keys.isEmpty()) null else youTubeExtractor.extractFirst(keys)
            }.getOrNull()
            _state.value = _state.value.copy(heroTrailers = _state.value.heroTrailers + (key to stream))
            heroTrailerInFlight -= key
        }
    }

    private fun formatRuntime(min: Int): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** Catalog-level watched key (series- or movie-level, no episode context). */
    private fun catalogKey(item: CatalogItem): String =
        if (item.type == MediaType.TV) "tv_${item.id}" else WatchedRepository.movieKey(item.id)

    fun isInMyList(item: CatalogItem): Boolean =
        _state.value.myList.any { it.id == item.id && it.type == item.type }

    fun isWatched(item: CatalogItem): Boolean =
        _state.value.watchedKeys.contains(catalogKey(item))

    fun toggleMyList(item: CatalogItem) {
        viewModelScope.launch { myList.toggle(item) }
    }

    fun toggleWatched(item: CatalogItem) {
        viewModelScope.launch { watched.toggle(catalogKey(item)) }
    }

    fun markWatched(item: CatalogItem) {
        viewModelScope.launch { watched.setWatched(catalogKey(item), true) }
    }

    fun markUnwatched(item: CatalogItem) {
        viewModelScope.launch { watched.setWatched(catalogKey(item), false) }
    }

    private fun observeWatched() {
        viewModelScope.launch {
            watched.watched.collect { keys ->
                _state.value = _state.value.copy(watchedKeys = keys)
            }
        }
    }

    private fun loadServiceLogos() {
        viewModelScope.launch {
            val logos = runCatching { repo.providerLogos() }.getOrDefault(emptyMap())
            if (logos.isNotEmpty()) _state.value = _state.value.copy(serviceLogos = logos)
        }
    }

    private fun observeContinueWatching() {
        viewModelScope.launch {
            progress.continueWatching.collect { list ->
                _state.value = _state.value.copy(continueWatching = list)
            }
        }
    }

    private fun observeMyList() {
        viewModelScope.launch {
            myList.items.collect { list ->
                _state.value = _state.value.copy(myList = list)
            }
        }
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // Fire all fetches in parallel; tolerate per-row failures.
                val trendingMovies = async { safe { repo.trendingMovies() } }
                val trendingTv = async { safe { repo.trendingTv() } }
                val top10Movies = async { safe { repo.top10MoviesToday() } }
                val top10Tv = async { safe { repo.top10TvToday() } }
                val airingToday = async { safe { repo.airingTodayTv() } }
                val netflixTv = async { safe { repo.tvByProvider(WatchProviders.NETFLIX) } }
                val appleTv = async { safe { repo.tvByProvider(WatchProviders.APPLE_TV_PLUS) } }
                val disneyTv = async { safe { repo.tvByProvider(WatchProviders.DISNEY_PLUS) } }
                val primeTv = async { safe { repo.tvByProvider(WatchProviders.PRIME_VIDEO) } }
                val netflixMovies = async { safe { repo.moviesByProvider(WatchProviders.NETFLIX) } }
                val disneyMovies = async { safe { repo.moviesByProvider(WatchProviders.DISNEY_PLUS) } }
                val primeMovies = async { safe { repo.moviesByProvider(WatchProviders.PRIME_VIDEO) } }
                val topRatedMovies = async { safe { repo.topRatedMovies() } }
                val topRatedTv = async { safe { repo.topRatedTvShows() } }

                val tm = trendingMovies.await()
                val tt = trendingTv.await()
                val t10m = top10Movies.await()
                val t10t = top10Tv.await()
                val air = airingToday.await()
                val nfTv = netflixTv.await()
                val apTv = appleTv.await()
                val dyTv = disneyTv.await()
                val prTv = primeTv.await()
                val nfMv = netflixMovies.await()
                val dyMv = disneyMovies.await()
                val prMv = primeMovies.await()
                val trMv = topRatedMovies.await()
                val trTv = topRatedTv.await()

                // Order requested for Home: Trending, Top 10, Airing Today, Top Rated.
                val home = buildList {
                    addRow("Trending Movies", tm)
                    addRow("Trending TV Shows", tt)
                    addRow("Top 10 Movies Today", t10m, ranked = true)
                    addRow("Top 10 TV Shows Today", t10t, ranked = true)
                    addRow("Airing Today in the U.S.", air)
                    addRow("Top Rated Movies", trMv)
                    addRow("Top Rated TV Shows", trTv)
                }
                val shows = buildList {
                    addRow("Trending TV Shows", tt)
                    addRow("Top 10 TV Shows Today", t10t, ranked = true)
                    addRow("Airing Today in the U.S.", air)
                    addRow("Popular on Netflix", nfTv)
                    addRow("Apple TV+ Shows", apTv)
                    addRow("Popular on Disney+", dyTv)
                    addRow("Popular on Prime Video", prTv)
                    addRow("Top Rated TV Shows", trTv)
                }
                val movies = buildList {
                    addRow("Trending Movies", tm)
                    addRow("Top 10 Movies Today", t10m, ranked = true)
                    addRow("Netflix Movies", nfMv)
                    addRow("Disney+ Movies", dyMv)
                    addRow("Prime Video Movies", prMv)
                    addRow("Top Rated Movies", trMv)
                }

                _state.value = _state.value.copy(
                    loading = false,
                    hero = tm.firstOrNull() ?: tt.firstOrNull(),
                    homeRows = home,
                    showsRows = shows,
                    moviesRows = movies,
                    error = if (home.isEmpty()) "Couldn't load content. Check your TMDB key and connection." else null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load.")
            }
        }
    }

    private inline fun MutableList<CatalogRow>.addRow(
        title: String,
        items: List<CatalogItem>,
        ranked: Boolean = false
    ) {
        if (items.isNotEmpty()) add(CatalogRow(title, items, ranked))
    }

    private inline fun safe(block: () -> List<CatalogItem>): List<CatalogItem> =
        try { block() } catch (e: Exception) { emptyList() }

    companion object {
        const val AI_ROW_TITLE = "AI Picks For You"
    }
}
