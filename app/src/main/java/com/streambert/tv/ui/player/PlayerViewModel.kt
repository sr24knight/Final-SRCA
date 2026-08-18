package com.streambert.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambert.tv.data.model.MediaType
import com.streambert.tv.data.progress.ProgressRepository
import com.streambert.tv.data.progress.WatchProgress
import com.streambert.tv.data.settings.SettingsRepository
import com.streambert.tv.data.stream.StreamRepository
import com.streambert.tv.data.stream.StreamResolution
import com.streambert.tv.data.stream.SubtitleRepository
import com.streambert.tv.data.stream.SubtitleTrack
import com.streambert.tv.data.tmdb.TmdbRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerUiState {
    data class Resolving(val message: String) : PlayerUiState
    data class Ready(
        val url: String,
        val title: String,
        val startPositionMs: Long,
        val tunnelingEnabled: Boolean,
        val subtitles: List<SubtitleTrack> = emptyList(),
        val subtitleScale: Float = 0.06f,
        val subtitleStyle: SubtitleStyle = SubtitleStyle(),
        val playerEngine: String = SettingsRepository.ENGINE_EXOPLAYER,
        val prefs: PlaybackPrefs = PlaybackPrefs(),
        val hasNextEpisode: Boolean = false
    ) : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

/** Playback preferences applied to the active engine. */
data class PlaybackPrefs(
    val hwdecMode: String = "auto-safe",
    val preferredAudioLang: String = "none",
    val preferredSubtitleLang: String = "none",
    val autoplayNextEnabled: Boolean = true,
    val skipIntroEnabled: Boolean = true,
    val isSeries: Boolean = false
)

/**
 * Resolves a playable stream for the requested TMDB title via the configured
 * Stremio addon (Torrentio/Comet + TorBox), then exposes it to the player.
 * Also handles resume: it loads any saved position and persists progress as
 * the user watches. Movies pass season/episode = -1.
 */
class PlayerViewModel(
    private val tmdb: TmdbRepository,
    private val stream: StreamRepository,
    private val progress: ProgressRepository,
    private val settings: SettingsRepository,
    private val subtitles: SubtitleRepository,
    private val type: MediaType,
    private val id: Int,
    private val season: Int,
    private val episode: Int,
    private val title: String,
    private val posterUrl: String = "",
    private val backdropUrl: String = "",
    private val directUrl: String = "",
    private val directHash: String = "",
    private val directDebrid: String = ""
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Resolving("Starting…"))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val progressKey = WatchProgress.keyFor(type, id, season, episode)

    // Guard so we only auto-switch ExoPlayer -> MPV once per resolved stream.
    private var triedMpvFallback = false

    // The exact source currently playing, persisted so resume replays it.
    private var playingUrl: String = ""
    private var playingHash: String = ""

    // The next episode to roll Continue Watching forward to once this one
    // finishes (null for movies or the final episode of a series).
    private data class NextEp(val season: Int, val episode: Int)
    private var nextEpisode: NextEp? = null
    private var nextUpEnqueued = false

    init { resolve() }

    fun resolve() {
        triedMpvFallback = false
        viewModelScope.launch {
            val startMs = progress.get(progressKey)?.positionMs ?: 0L
            val tunneling = settings.currentTunnelingEnabled()
            val s = season.takeIf { it > 0 }
            val e = episode.takeIf { it > 0 }

            // ── Fire off every independent lookup CONCURRENTLY ──────────────
            // Previously these ran one-after-another (engine → nextEpisode →
            // imdbId → subtitles) *before* stream resolution even started, so
            // the player couldn't begin until all of them plus resolution had
            // finished. Now they overlap with each other and with the (usually
            // longest) stream-resolution + debrid step, so the only thing that
            // gates playback is getting the actual stream URL.

            // Resolve "auto" to a concrete engine: ExoPlayer for movies/TV,
            // libmpv for anime. The picker's other values pass through as-is.
            val engineDeferred = async {
                when (settings.currentPlayerEngine()) {
                    SettingsRepository.ENGINE_AUTO ->
                        if (runCatching { tmdb.isAnime(id, type) }.getOrDefault(false))
                            SettingsRepository.ENGINE_MPV else SettingsRepository.ENGINE_EXOPLAYER
                    SettingsRepository.ENGINE_MPV -> SettingsRepository.ENGINE_MPV
                    else -> SettingsRepository.ENGINE_EXOPLAYER
                }
            }
            // Next episode (next in season, else first of next season). Guards
            // autoplay-next AND the Continue-Watching "next up".
            val nextEpisodeDeferred = async { if (season > 0) computeNextEpisode() else null }
            // IMDb id (needed for both subtitles and stream lookup). Wrapped so a
            // failure yields null instead of cancelling the whole resolve scope.
            val imdbDeferred = async { runCatching { tmdb.imdbId(id, type) }.getOrNull() }
            // Subtitles depend on the IMDb id but otherwise run in the background
            // so fetching them no longer blocks the player from starting.
            val subsDeferred = async {
                val imdb = imdbDeferred.await()
                if (!imdb.isNullOrBlank())
                    runCatching { subtitles.fetch(imdb, s, e) }.getOrDefault(emptyList())
                else emptyList()
            }

            val prefs = PlaybackPrefs(
                hwdecMode = if (settings.currentMpvHardwareDecoding()) "auto-safe" else "no",
                preferredAudioLang = settings.currentPreferredAudioLanguage(),
                preferredSubtitleLang = settings.currentPreferredSubtitleLanguage(),
                autoplayNextEnabled = settings.currentAutoplayNextEnabled(),
                skipIntroEnabled = settings.currentSkipIntroEnabled(),
                isSeries = season > 0
            )
            val subScale = settings.currentSubtitleFraction()
            val subStyle = SubtitleStyle(
                delayMs = settings.currentSubtitleDelayMs(),
                fontPercent = settings.currentSubtitleFontPercent(),
                bold = settings.currentSubtitleBold(),
                textColor = settings.currentSubtitleTextColor(),
                opacityPercent = settings.currentSubtitleTextOpacityPercent(),
                outline = settings.currentSubtitleOutline()
            )

            // Once we have a URL, await the concurrent lookups (which have been
            // running throughout resolution, so this is usually instant) and go
            // Ready. Emitting Ready is what triggers PlaybackFactory.prepare().
            suspend fun goReady(url: String, label: String) {
                nextEpisode = nextEpisodeDeferred.await()
                _state.value = PlayerUiState.Ready(
                    url = url,
                    title = label,
                    startPositionMs = startMs,
                    tunnelingEnabled = tunneling,
                    subtitles = subsDeferred.await(),
                    subtitleScale = subScale,
                    subtitleStyle = subStyle,
                    playerEngine = engineDeferred.await(),
                    prefs = prefs,
                    hasNextEpisode = nextEpisode != null
                )
            }

            if (directUrl.isNotBlank()) {
                playingUrl = directUrl
                playingHash = directHash
                goReady(directUrl, title.ifBlank { "Stream" })
                return@launch
            }

            // A scraper source was picked — resolve its hash via TorBox instant.
            if (directHash.isNotBlank()) {
                _state.value = PlayerUiState.Resolving("Preparing instant TorBox stream…")
                when (val r = stream.resolveHash(directHash, title, s, e, directDebrid.ifBlank { null })) {
                    is StreamResolution.Ready -> {
                        playingUrl = r.url
                        playingHash = directHash
                        goReady(r.url, title.ifBlank { r.label })
                    }
                    is StreamResolution.Failure -> _state.value = PlayerUiState.Error(r.message)
                    is StreamResolution.Progress -> _state.value = PlayerUiState.Resolving(r.message)
                }
                return@launch
            }

            val imdb = imdbDeferred.await()
            if (imdb.isNullOrBlank()) {
                _state.value = PlayerUiState.Error("Couldn't find an IMDb id for this title.")
                return@launch
            }

            _state.value = PlayerUiState.Resolving("Looking up title…")
            when (val result = stream.resolveStream(
                imdbId = imdb,
                season = s,
                episode = e,
                onProgress = { msg -> _state.value = PlayerUiState.Resolving(msg) }
            )) {
                is StreamResolution.Ready -> {
                    playingUrl = result.url
                    playingHash = ""
                    goReady(result.url, title.ifBlank { result.label })
                }
                is StreamResolution.Failure ->
                    _state.value = PlayerUiState.Error(result.message)
                is StreamResolution.Progress ->
                    _state.value = PlayerUiState.Resolving(result.message)
            }
        }
    }

    /**
     * Next episode within the current season, or the first episode of the next
     * season, using TMDB per-season episode counts. Null for the series finale.
     */
    private suspend fun computeNextEpisode(): NextEp? {
        val details = runCatching { tmdb.tvDetails(id) }.getOrNull() ?: return null
        val seasons = details.seasons.filter { it.seasonNumber > 0 }
        val current = seasons.firstOrNull { it.seasonNumber == season } ?: return null
        return when {
            episode < current.episodeCount -> NextEp(season, episode + 1)
            seasons.any { it.seasonNumber == season + 1 && it.episodeCount > 0 } -> NextEp(season + 1, 1)
            else -> null
        }
    }

    /** Update + persist subtitle style from the player's subtitle panel. */
    fun updateSubtitleStyle(style: SubtitleStyle) {
        val cur = _state.value
        if (cur is PlayerUiState.Ready) _state.value = cur.copy(subtitleStyle = style)
        viewModelScope.launch {
            settings.setSubtitleDelayMs(style.delayMs)
            settings.setSubtitleFontPercent(style.fontPercent)
            settings.setSubtitleBold(style.bold)
            settings.setSubtitleTextColor(style.textColor)
            settings.setSubtitleTextOpacityPercent(style.opacityPercent)
            settings.setSubtitleOutline(style.outline)
        }
    }

    /**
     * Surface a fatal playback error (from the ExoPlayer listener) so the UI
     * shows a readable message + Retry instead of a silent black screen.
     */
    fun onPlaybackError(message: String) {
        _state.value = PlayerUiState.Error(message)
    }

    /**
     * ExoPlayer hit a decoder-capability error (e.g. the device can't decode 4K
     * HEVC / Dolby Vision). Transparently switch this same stream to the MPV
     * (libmpv) engine, which software-decodes those formats. Falls through to a
     * normal error only if we've already tried MPV.
     */
    fun onDecoderUnsupported(message: String) {
        val cur = _state.value
        if (cur is PlayerUiState.Ready &&
            cur.playerEngine != SettingsRepository.ENGINE_MPV &&
            !triedMpvFallback
        ) {
            triedMpvFallback = true
            _state.value = cur.copy(playerEngine = SettingsRepository.ENGINE_MPV)
        } else {
            _state.value = PlayerUiState.Error(message)
        }
    }

    /** Persist the current playback position (called periodically + on exit). */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= 0) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            progress.save(
                WatchProgress(
                    key = progressKey,
                    type = type.tmdb,
                    tmdbId = id,
                    title = title.ifBlank { "Stream" },
                    posterUrl = posterUrl.ifBlank { null },
                    backdropUrl = backdropUrl.ifBlank { null },
                    season = season,
                    episode = episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    // Remember the exact source so resume replays it (prefer the
                    // torrent hash, which re-resolves a fresh non-expiring URL).
                    streamUrl = playingUrl.ifBlank { null },
                    streamHash = playingHash.ifBlank { null },
                    updatedAt = now
                )
            )

            // Once a series episode is essentially finished, roll Continue
            // Watching forward to the next episode (NuvioTV-style "next up").
            val finished = positionMs.toFloat() / durationMs >= FINISHED_FRACTION
            if (finished && season > 0 && !nextUpEnqueued) {
                nextEpisode?.let { n ->
                    nextUpEnqueued = true
                    progress.save(
                        WatchProgress(
                            key = WatchProgress.keyFor(type, id, n.season, n.episode),
                            type = type.tmdb,
                            tmdbId = id,
                            title = title.ifBlank { "Stream" },
                            posterUrl = posterUrl.ifBlank { null },
                            backdropUrl = backdropUrl.ifBlank { null },
                            season = n.season,
                            episode = n.episode,
                            positionMs = 0,
                            durationMs = 0,
                            nextUp = true,
                            updatedAt = now + 1 // sort just above the finished one
                        )
                    )
                }
            }
        }
    }

    private companion object {
        const val FINISHED_FRACTION = 0.92f
    }
}
