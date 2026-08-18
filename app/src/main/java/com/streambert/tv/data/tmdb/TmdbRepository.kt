package com.streambert.tv.data.tmdb

import com.streambert.tv.data.model.CastPerson
import com.streambert.tv.data.model.CatalogItem
import com.streambert.tv.data.model.MediaType
import com.streambert.tv.data.settings.SettingsRepository

/**
 * High-level access to TMDB. Maps raw API responses into [CatalogItem]s and
 * exposes the data the UI needs (home rows, details, seasons, search, and the
 * IMDb id required for TorBox lookups).
 */
class TmdbRepository(
    private val api: TmdbApi,
    private val settings: SettingsRepository
) {

    private companion object {
        const val ANIMATION_GENRE = 16
    }

    private suspend fun lang() = settings.currentTmdbLang()

    /**
     * Anime detection for content filtering: a title is anime when it is in the
     * Animation genre (16) AND is Japanese (original language "ja" or origin
     * country JP). Western animation (Pixar, etc.) is kept.
     */
    private fun TmdbResult.isAnimeResult(): Boolean =
        genreIds.contains(ANIMATION_GENRE) &&
            (originalLanguage.equals("ja", true) || originCountry.any { it.equals("JP", true) })

    /** Removes anime titles from any TMDB result list (applied app-wide). */
    private fun List<TmdbResult>.dropAnime(): List<TmdbResult> = filterNot { it.isAnimeResult() }

    /** Today's date (yyyy-MM-dd) for "New on <provider>" date-capped queries. */
    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** The active TMDB watch region (ISO-3166-1), e.g. "US". */
    suspend fun regionCode(): String = watchRegion()

    fun TmdbResult.toCatalogItem(forcedType: MediaType? = null): CatalogItem {
        val type = forcedType ?: MediaType.from(mediaTypeRaw)
        return CatalogItem(
            id = id,
            type = type,
            title = displayTitle,
            overview = overview,
            posterUrl = tmdbImage(posterPath, "w500"),
            backdropUrl = tmdbImage(backdropPath, "w1280"),
            rating = voteAverage,
            year = year,
            genreIds = genreIds
        )
    }

    suspend fun trendingMovies(): List<CatalogItem> =
        api.trendingMovies(lang()).results.dropAnime().map { it.toCatalogItem(MediaType.MOVIE) }

    suspend fun trendingTv(): List<CatalogItem> =
        api.trendingTv(lang()).results.dropAnime().map { it.toCatalogItem(MediaType.TV) }

    suspend fun topRatedMovies(): List<CatalogItem> =
        api.topRatedMovies(lang()).results.dropAnime().map { it.toCatalogItem(MediaType.MOVIE) }

    suspend fun topRatedTvShows(): List<CatalogItem> =
        api.topRatedTv(lang()).results.dropAnime().map { it.toCatalogItem(MediaType.TV) }

    /** TV shows airing today. */
    suspend fun airingTodayTv(): List<CatalogItem> =
        api.airingTodayTv(lang()).results.dropAnime().map { it.toCatalogItem(MediaType.TV) }

    suspend fun moviesByGenre(
        genreId: Int,
        sortBy: String = "popularity.desc",
        minVotes: Int? = null
    ): List<CatalogItem> =
        api.discoverMoviesByGenre(
            language = lang(),
            genres = genreId.toString(),
            sortBy = sortBy,
            minVotes = minVotes
        ).results.dropAnime().map { it.toCatalogItem(MediaType.MOVIE) }

    suspend fun tvByGenre(
        genreId: Int,
        sortBy: String = "popularity.desc",
        minVotes: Int? = null
    ): List<CatalogItem> =
        api.discoverTvByGenre(
            language = lang(),
            genres = genreId.toString(),
            sortBy = sortBy,
            minVotes = minVotes
        ).results.dropAnime().map { it.toCatalogItem(MediaType.TV) }

    /** Trending today, capped to 10 — used for the Netflix-style "Top 10" rows. */
    suspend fun top10MoviesToday(): List<CatalogItem> =
        api.trendingMoviesToday(lang()).results.dropAnime().take(10).map { it.toCatalogItem(MediaType.MOVIE) }

    suspend fun top10TvToday(): List<CatalogItem> =
        api.trendingTvToday(lang()).results.dropAnime().take(10).map { it.toCatalogItem(MediaType.TV) }

    /** Movies available on a given watch provider (e.g. Netflix, Prime). */
    suspend fun moviesByProvider(
        providerId: Int,
        sortBy: String = "popularity.desc",
        minVotes: Int? = null
    ): List<CatalogItem> =
        api.discoverMovies(
            language = lang(),
            providers = providerId.toString(),
            region = watchRegion(),
            sortBy = sortBy,
            minVotes = minVotes
        ).results.dropAnime().map { it.toCatalogItem(MediaType.MOVIE) }

    /** Series available on a given watch provider (e.g. Netflix, Apple TV+). */
    suspend fun tvByProvider(
        providerId: Int,
        sortBy: String = "popularity.desc",
        minVotes: Int? = null
    ): List<CatalogItem> =
        api.discoverTv(
            language = lang(),
            providers = providerId.toString(),
            region = watchRegion(),
            sortBy = sortBy,
            minVotes = minVotes
        ).results.dropAnime().map { it.toCatalogItem(MediaType.TV) }

    /** Recently-released movies on a provider ("New on <service>"). */
    suspend fun newMoviesByProvider(providerId: Int): List<CatalogItem> =
        api.discoverMovies(
            language = lang(),
            providers = providerId.toString(),
            region = watchRegion(),
            sortBy = "primary_release_date.desc",
            minVotes = 20,
            releaseDateLte = today()
        ).results.dropAnime().map { it.toCatalogItem(MediaType.MOVIE) }

    /** Recently-premiered series on a provider ("New on <service>"). */
    suspend fun newTvByProvider(providerId: Int): List<CatalogItem> =
        api.discoverTv(
            language = lang(),
            providers = providerId.toString(),
            region = watchRegion(),
            sortBy = "first_air_date.desc",
            minVotes = 20,
            firstAirDateLte = today()
        ).results.dropAnime().map { it.toCatalogItem(MediaType.TV) }

    // Cache provider logos for the lifetime of the repo (they rarely change).
    @Volatile
    private var _providerLogos: Map<Int, String>? = null

    /** Map of TMDB provider id -> logo image URL, for the Services row. */
    suspend fun providerLogos(): Map<Int, String> {
        _providerLogos?.let { return it }
        val region = watchRegion()
        val l = lang()
        val merged = HashMap<Int, String>()
        runCatching {
            (api.watchProvidersMovie(l, region).results + api.watchProvidersTv(l, region).results)
                .forEach { p ->
                    val url = tmdbImage(p.logoPath, "w300")
                    if (url != null && !merged.containsKey(p.providerId)) merged[p.providerId] = url
                }
        }
        _providerLogos = merged
        return merged
    }

    /** Derive a TMDB watch_region (ISO-3166-1) from the metadata locale. */
    private suspend fun watchRegion(): String {
        val l = lang()
        val region = l.substringAfter('-', "").uppercase()
        return region.ifBlank { "US" }
    }

    /** The person's display name (title of their filmography screen). */
    suspend fun personName(personId: Int): String = try {
        api.personDetails(personId, lang()).name.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /**
     * The person's display name + profile photo URL (portrait headshot, h632)
     * in a single TMDB call — used to show the cast member's own photo as the
     * hero backdrop on their filmography screen. Returns ("", null) on failure.
     */
    suspend fun personHeader(personId: Int): Pair<String, String?> = try {
        val d = api.personDetails(personId, lang())
        d.name.orEmpty() to tmdbImage(d.profilePath, "h632")
    } catch (_: Exception) {
        "" to null
    }

    /**
     * A person's movie + TV filmography (from combined_credits), most popular
     * first, de-duplicated by title and filtered to items that have a poster.
     */
    suspend fun personFilmography(personId: Int): List<CatalogItem> = try {
        api.personCombinedCredits(personId, lang()).cast
            .asSequence()
            .filter { it.mediaTypeRaw == "movie" || it.mediaTypeRaw == "tv" }
            .filterNot { it.isAnimeResult() }
            .sortedByDescending { it.popularity }
            .map { it.toCatalogItem() }
            .filter { it.posterUrl != null }
            .distinctBy { "${it.type}_${it.id}" }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun recommendationsFor(item: CatalogItem): List<CatalogItem> {
        val l = lang()
        val page = if (item.type == MediaType.TV) {
            api.tvRecommendations(item.id, l)
        } else {
            api.movieRecommendations(item.id, l)
        }
        return page.results.dropAnime().map { it.toCatalogItem(item.type) }
    }

    suspend fun search(query: String): List<CatalogItem> =
        api.searchMulti(query.trim(), lang()).results
            .filter { it.mediaTypeRaw == "movie" || it.mediaTypeRaw == "tv" }
            .dropAnime()
            .map { it.toCatalogItem() }

    suspend fun movieDetails(id: Int): MovieDetails = api.movieDetails(id, lang())

    suspend fun tvDetails(id: Int): TvDetails = api.tvDetails(id, lang())

    /**
     * Builds a [CatalogItem] from a bare TMDB id + type — used to enrich items
     * that arrive from external sources (e.g. a Trakt watchlist) which only give
     * us ids. Returns null if the title can't be resolved.
     */
    suspend fun catalogItemFromTmdb(id: Int, type: MediaType): CatalogItem? = try {
        if (type == MediaType.MOVIE) {
            val d = api.movieDetails(id, lang())
            CatalogItem(
                id = d.id, type = MediaType.MOVIE, title = d.title.orEmpty(),
                overview = d.overview, posterUrl = tmdbImage(d.posterPath, "w500"),
                backdropUrl = tmdbImage(d.backdropPath, "w1280"), rating = d.voteAverage,
                year = d.year, genreIds = d.genres.map { it.id }
            ).takeIf { it.title.isNotBlank() }
        } else {
            val d = api.tvDetails(id, lang())
            CatalogItem(
                id = d.id, type = MediaType.TV, title = d.name.orEmpty(),
                overview = d.overview, posterUrl = tmdbImage(d.posterPath, "w500"),
                backdropUrl = tmdbImage(d.backdropPath, "w1280"), rating = d.voteAverage,
                year = d.year, genreIds = d.genres.map { it.id }
            ).takeIf { it.title.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    suspend fun seasonDetails(tvId: Int, season: Int): SeasonDetails =
        api.seasonDetails(tvId, season, lang())

    /** Director (first) + top-billed cast for the detail Cast row. */
    suspend fun credits(id: Int, type: MediaType): List<CastPerson> = try {
        val c = if (type == MediaType.MOVIE) api.movieCredits(id, lang()) else api.tvCredits(id, lang())
        val director = c.crew.firstOrNull { it.job.equals("Director", true) }
        val people = ArrayList<CastPerson>()
        if (director?.name != null) {
            people.add(CastPerson(director.id, director.name, "Director", tmdbImage(director.profilePath, "w185")))
        }
        c.cast.sortedBy { it.order }.take(18).forEach { m ->
            if (m.name != null) {
                people.add(CastPerson(m.id, m.name, m.character.orEmpty(), tmdbImage(m.profilePath, "w185")))
            }
        }
        people
    } catch (_: Exception) {
        emptyList()
    }

    /** Ranked YouTube trailer/teaser candidate keys (best first), for robust playback. */
    suspend fun trailerYoutubeKeys(id: Int, type: MediaType): List<String> = try {
        val videos = if (type == MediaType.MOVIE) api.movieVideos(id, lang()) else api.tvVideos(id, lang())
        val yt = videos.results.filter { it.site.equals("YouTube", true) && !it.key.isNullOrBlank() }
        val ranked = yt.sortedWith(
            compareBy<VideoInfo>(
                { if (it.type.equals("Trailer", true)) 0 else if (it.type.equals("Teaser", true)) 1 else 2 },
                { if (it.official) 0 else 1 }
            )
        )
        ranked.mapNotNull { it.key }.distinct().take(5)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Best-effort anime detection for the "Auto (Best for Content)" player
     * engine: a title counts as anime when it is in the Animation genre (16)
     * AND is Japanese (original language "ja" or origin country JP). This keeps
     * Western animation (Pixar, etc.) on ExoPlayer while routing anime to MPV.
     */
    suspend fun isAnime(id: Int, type: MediaType): Boolean = try {
        val ANIMATION_GENRE = 16
        if (type == MediaType.MOVIE) {
            val d = api.movieDetails(id, lang())
            d.genres.any { it.id == ANIMATION_GENRE } && d.originalLanguage.equals("ja", true)
        } else {
            val d = api.tvDetails(id, lang())
            d.genres.any { it.id == ANIMATION_GENRE } &&
                (d.originalLanguage.equals("ja", true) || d.originCountry.any { it.equals("JP", true) })
        }
    } catch (_: Exception) {
        false
    }

    /** Resolve the IMDb id (ttXXXXXXX) needed for TorBox search. */
    suspend fun imdbId(id: Int, type: MediaType): String? = try {
        if (type == MediaType.MOVIE) {
            api.movieDetails(id, lang()).imdbId ?: api.movieExternalIds(id).imdbId
        } else {
            api.tvExternalIds(id).imdbId
        }
    } catch (_: Exception) {
        null
    }
}
