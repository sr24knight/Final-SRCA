package com.streambert.tv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streambert.tv.data.stream.DynamicRangeFilter
import com.streambert.tv.data.stream.MinQualityFilter
import com.streambert.tv.data.stream.StreamFilters
import com.streambert.tv.data.stream.VideoCodecFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "streambert_settings")

/** One configured stream source (a debrid-backed Torrentio, or a custom addon). */
data class AddonSource(
    val baseUrl: String,
    val isTorBox: Boolean,
    val resolveViaTorBox: Boolean,
    val label: String,
    /**
     * For direct-URL debrid sources, the service that already resolved the URL
     * ("TorBox"/"RD"). Null for scraper add-ons, which are resolved per
     * connected debrid at play time.
     */
    val debrid: String? = null
)

/**
 * Persists user settings (TMDB key, TorBox key, preferences) via DataStore.
 *
 * Mirrors the keys used by the desktop Electron app where sensible:
 *  - TMDB API key (read-access token / v4 bearer)
 *  - TorBox API key (the new default streaming source on TV)
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val TMDB_KEY = stringPreferencesKey("tmdb_api_key")
        val TORBOX_KEY = stringPreferencesKey("torbox_api_key")
        val TMDB_LANG = stringPreferencesKey("tmdb_lang")
        val AUTOPLAY = booleanPreferencesKey("autoplay_default")
        val PREFER_QUALITY = stringPreferencesKey("prefer_quality") // e.g. "1080p"
        val TUNNELING = booleanPreferencesKey("tunneling_enabled")
        // Stream source: "torrentio" (uses debrid key) or "custom" (full addon URL, e.g. Comet)
        val ADDON_TYPE = stringPreferencesKey("addon_type")
        val CUSTOM_ADDON_URL = stringPreferencesKey("custom_addon_url")
        val REALDEBRID_KEY = stringPreferencesKey("realdebrid_key")
        // All installed Stremio add-ons (stream + subtitle), one URL per line.
        val SCRAPER_ADDONS = stringPreferencesKey("scraper_addons")
        // OMDb API key for real IMDb ratings
        val OMDB_KEY = stringPreferencesKey("omdb_key")
        // MDBList API key for multi-source ratings
        val MDBLIST_KEY = stringPreferencesKey("mdblist_key")
        // Google Gemini API key
        val GEMINI_KEY = stringPreferencesKey("gemini_key")
        // MDBList: master enable + per-provider display toggles
        val MDBLIST_ENABLED = booleanPreferencesKey("mdblist_enabled")
        val MDB_SHOW_IMDB = booleanPreferencesKey("mdb_show_imdb")
        val MDB_SHOW_TOMATOES = booleanPreferencesKey("mdb_show_tomatoes")
        val MDB_SHOW_AUDIENCE = booleanPreferencesKey("mdb_show_audience")
        val MDB_SHOW_METACRITIC = booleanPreferencesKey("mdb_show_metacritic")
        val MDB_SHOW_TMDB = booleanPreferencesKey("mdb_show_tmdb")
        val MDB_SHOW_TRAKT = booleanPreferencesKey("mdb_show_trakt")
        val MDB_SHOW_LETTERBOXD = booleanPreferencesKey("mdb_show_letterboxd")
        // TMDB metadata enrichment: master enable + per-group toggles
        val TMDB_ENRICH_ENABLED = booleanPreferencesKey("tmdb_enrich_enabled")
        val TMDB_USE_ARTWORK = booleanPreferencesKey("tmdb_use_artwork")
        val TMDB_USE_BASIC_INFO = booleanPreferencesKey("tmdb_use_basic_info")
        val TMDB_USE_DETAILS = booleanPreferencesKey("tmdb_use_details")
        val TMDB_USE_CREDITS = booleanPreferencesKey("tmdb_use_credits")
        val TMDB_USE_TRAILERS = booleanPreferencesKey("tmdb_use_trailers")
        val TMDB_USE_MORE_LIKE_THIS = booleanPreferencesKey("tmdb_use_more_like_this")
        val TMDB_USE_COLLECTIONS = booleanPreferencesKey("tmdb_use_collections")
        // Subtitle appearance: "small" | "medium" | "large"
        val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        // Nuvio-style subtitle style controls (player subtitle panel).
        val SUBTITLE_DELAY_MS = longPreferencesKey("subtitle_delay_ms")
        val SUBTITLE_FONT_PCT = intPreferencesKey("subtitle_font_pct")          // 50..250
        val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")
        val SUBTITLE_TEXT_COLOR = intPreferencesKey("subtitle_text_color")      // opaque ARGB base
        val SUBTITLE_TEXT_OPACITY_PCT = intPreferencesKey("subtitle_text_opacity_pct") // 0..100
        val SUBTITLE_OUTLINE = booleanPreferencesKey("subtitle_outline")
        // Internal player engine: "mpv" (default) or "exoplayer"
        val PLAYER_ENGINE = stringPreferencesKey("player_engine")
        // MPV hardware decoding (true = mediacodec-copy, false = software)
        val MPV_HWDEC = booleanPreferencesKey("mpv_hardware_decoding")
        // Preferred audio / subtitle language codes ("none" = off)
        val PREF_AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        val PREF_SUB_LANG = stringPreferencesKey("preferred_subtitle_language")
        // Netflix-style playback aids
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next_episode")
        val SKIP_INTRO = booleanPreferencesKey("skip_intro_enabled")
        // Nuvio-style stream feature filters (stored as enum names).
        // ANY | EXCLUDE | ONLY for DV/HDR; ANY | H264 | HEVC | AV1 for codec;
        // ANY | P720 | P1080 | P2160 for minimum quality.
        val STREAM_DV_FILTER = stringPreferencesKey("stream_dolby_vision_filter")
        val STREAM_HDR_FILTER = stringPreferencesKey("stream_hdr_filter")
        val STREAM_CODEC_FILTER = stringPreferencesKey("stream_codec_filter")
        val STREAM_MIN_QUALITY = stringPreferencesKey("stream_min_quality")
        // Dolby Vision (DV7) handling mode, mirroring Nuvio's Dv7HandlingMode.
        val DV_HANDLING = stringPreferencesKey("dv_handling_mode")
        // NuvioTV-format stream-badge config URL (fetched + cached at runtime).
        val BADGE_CONFIG_URL = stringPreferencesKey("badge_config_url")

        // Trakt.tv OAuth (device-code flow). Credentials are user-supplied (their
        // own Trakt API app); tokens are obtained + refreshed at runtime.
        val TRAKT_CLIENT_ID = stringPreferencesKey("trakt_client_id")
        val TRAKT_CLIENT_SECRET = stringPreferencesKey("trakt_client_secret")
        val TRAKT_ACCESS_TOKEN = stringPreferencesKey("trakt_access_token")
        val TRAKT_REFRESH_TOKEN = stringPreferencesKey("trakt_refresh_token")
        val TRAKT_CREATED_AT = longPreferencesKey("trakt_created_at")
        val TRAKT_EXPIRES_IN = longPreferencesKey("trakt_expires_in")
        val TRAKT_USERNAME = stringPreferencesKey("trakt_username")
    }

    companion object {
        const val ADDON_TORRENTIO = "torrentio"
        const val ADDON_CUSTOM = "custom"
        // Debrid service display names, used to tag/filter sources in the picker.
        const val DEBRID_TORBOX = "TorBox"
        const val DEBRID_RD = "RD"
        const val ENGINE_MPV = "mpv"
        const val ENGINE_EXOPLAYER = "exoplayer"
        // "auto": ExoPlayer for Movies & TV Shows, libmpv for Anime.
        const val ENGINE_AUTO = "auto"
        // Default stream-badge config (user-provided preset).
        const val DEFAULT_BADGE_CONFIG_URL = "https://pastebin.com/raw/gyj0HUuE"
        // Dolby Vision (DV7) handling modes, mirroring Nuvio. AUTO is the default.
        const val DV_AUTO = "AUTO"
        const val DV_HDR10_BASE_LAYER = "HDR10_BASE_LAYER"
        const val DV_DV81_LIBDOVI = "DV81_LIBDOVI"
        const val DV_STRIP = "STRIP_DV"
        const val DV_OFF = "OFF"
        private const val TORRENTIO_HOST = "https://torrentio.strem.fun"
        // Default subtitle add-on: SubSense (English, up to 10 results). Replaces
        // the old OpenSubtitles v3 Pro add-on.
        const val DEFAULT_SUBSENSE =
            "https://subsense.nepiraw.com/jxq7v9tk-%7B%22languages%22%3A%5B%22en%22%5D%2C%22maxSubtitles%22%3A10%7D/manifest.json"
        // Default installed add-ons. Torrent scrapers (resolved via TorBox instant)
        // plus the SubSense subtitle add-on — all managed through the single Add-ons list.
        private val DEFAULT_SCRAPERS = listOf(
            "https://cometfortheweebs.midnightignite.me/eyJtYXhSZXN1bHRzUGVyUmVzb2x1dGlvbiI6MTAsIm1heFNpemUiOjAsImNhY2hlZE9ubHkiOnRydWUsInNvcnRDYWNoZWRVbmNhY2hlZFRvZ2V0aGVyIjpmYWxzZSwicmVtb3ZlVHJhc2giOnRydWUsInJlc3VsdEZvcm1hdCI6WyJhbGwiXSwiZGVicmlkU2VydmljZXMiOltdLCJlbmFibGVUb3JyZW50IjpmYWxzZSwiZGVkdXBsaWNhdGVTdHJlYW1zIjp0cnVlLCJzY3JhcGVEZWJyaWRBY2NvdW50VG9ycmVudHMiOnRydWUsImRlYnJpZFN0cmVhbVByb3h5UGFzc3dvcmQiOiIiLCJsYW5ndWFnZXMiOnsicmVxdWlyZWQiOltdLCJhbGxvd2VkIjpbXSwiZXhjbHVkZSI6W10sInByZWZlcnJlZCI6W119LCJyZXNvbHV0aW9ucyI6eyJyMjE2MHAiOnRydWUsInIxNDQwcCI6dHJ1ZSwicjEwODBwIjp0cnVlLCJyNzIwcCI6dHJ1ZSwicjU3NnAiOmZhbHNlLCJyNDgwcCI6ZmFsc2UsInIzNjBwIjpmYWxzZSwicjI0MHAiOmZhbHNlLCJ1bmtub3duIjpmYWxzZX0sIm9wdGlvbnMiOnsicmVtb3ZlX3JhbmtzX3VuZGVyIjotMTAwMDAwMDAwMDAsImFsbG93X2VuZ2xpc2hfaW5fbGFuZ3VhZ2VzIjpmYWxzZSwicmVtb3ZlX3Vua25vd25fbGFuZ3VhZ2VzIjpmYWxzZX19/manifest.json",
            "https://torrentio.strem.fun/sort=qualitysize|qualityfilter=480p,unknown,scr,cam,threed|debridoptions=nodownloadlinks,nocatalog/manifest.json",
            DEFAULT_SUBSENSE
        )

        /**
         * Migrates a stored add-on list to SubSense: drops the legacy
         * OpenSubtitles add-on and guarantees SubSense is present. Applied on
         * every read so existing installs switch over automatically.
         */
        private fun migrateSubtitleAddons(raw: String): String {
            val lines = raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.contains("opensubtitles", true) || it.contains("dexter21767", true) }
                .toMutableList()
            val hasSubSense = lines.any { it.contains("subsense", true) || it.contains("nepiraw", true) }
            if (!hasSubSense) lines.add(DEFAULT_SUBSENSE)
            return lines.joinToString("\n")
        }
    }

    // Falls back to the baked-in token (from local.properties -> BuildConfig) so the
    // user doesn't have to enter it. A key typed in Settings always takes precedence.
    val tmdbKey: Flow<String> = context.dataStore.data.map {
        it[Keys.TMDB_KEY]?.takeIf { k -> k.isNotBlank() }
            ?: com.streambert.tv.BuildConfig.TMDB_DEFAULT_TOKEN
    }
    val torboxKey: Flow<String> = context.dataStore.data.map { it[Keys.TORBOX_KEY].orEmpty() }
    val tmdbLang: Flow<String> = context.dataStore.data.map { it[Keys.TMDB_LANG] ?: "en-US" }
    val addonType: Flow<String> = context.dataStore.data.map { it[Keys.ADDON_TYPE] ?: ADDON_TORRENTIO }
    val customAddonUrl: Flow<String> = context.dataStore.data.map { it[Keys.CUSTOM_ADDON_URL].orEmpty() }
    val realDebridKey: Flow<String> = context.dataStore.data.map { it[Keys.REALDEBRID_KEY].orEmpty() }
    val scraperAddons: Flow<String> =
        context.dataStore.data.map { migrateSubtitleAddons(it[Keys.SCRAPER_ADDONS] ?: DEFAULT_SCRAPERS.joinToString("\n")) }
    val omdbKey: Flow<String> = context.dataStore.data.map { it[Keys.OMDB_KEY].orEmpty() }
    val mdblistKey: Flow<String> = context.dataStore.data.map {
        it[Keys.MDBLIST_KEY]?.takeIf { k -> k.isNotBlank() }
            ?: com.streambert.tv.BuildConfig.MDBLIST_DEFAULT_KEY
    }
    val mdblistEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDBLIST_ENABLED] ?: true }
    /** Google Gemini API key; falls back to the local.properties/BuildConfig value. */
    val geminiKey: Flow<String> = context.dataStore.data.map {
        it[Keys.GEMINI_KEY]?.takeIf { k -> k.isNotBlank() }
            ?: com.streambert.tv.BuildConfig.GEMINI_DEFAULT_KEY
    }

    /** URL of the NuvioTV-format stream-badge config. Defaults to the shared preset. */
    val badgeConfigUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.BADGE_CONFIG_URL] ?: DEFAULT_BADGE_CONFIG_URL }

    // ── Trakt.tv ────────────────────────────────────────────────────────────
    val traktClientId: Flow<String> = context.dataStore.data.map { it[Keys.TRAKT_CLIENT_ID].orEmpty() }
    val traktClientSecret: Flow<String> = context.dataStore.data.map { it[Keys.TRAKT_CLIENT_SECRET].orEmpty() }
    val traktAccessToken: Flow<String> = context.dataStore.data.map { it[Keys.TRAKT_ACCESS_TOKEN].orEmpty() }
    val traktRefreshToken: Flow<String> = context.dataStore.data.map { it[Keys.TRAKT_REFRESH_TOKEN].orEmpty() }
    val traktUsername: Flow<String> = context.dataStore.data.map { it[Keys.TRAKT_USERNAME].orEmpty() }

    /** True once we hold both an access and refresh token. */
    val traktAuthenticated: Flow<Boolean> = context.dataStore.data.map {
        !it[Keys.TRAKT_ACCESS_TOKEN].isNullOrBlank() && !it[Keys.TRAKT_REFRESH_TOKEN].isNullOrBlank()
    }
    val mdbShowImdb: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_IMDB] ?: true }
    val mdbShowTomatoes: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_TOMATOES] ?: true }
    val mdbShowAudience: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_AUDIENCE] ?: true }
    val mdbShowMetacritic: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_METACRITIC] ?: true }
    val mdbShowTmdb: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_TMDB] ?: true }
    val mdbShowTrakt: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_TRAKT] ?: true }
    val mdbShowLetterboxd: Flow<Boolean> = context.dataStore.data.map { it[Keys.MDB_SHOW_LETTERBOXD] ?: true }

    val tmdbEnrichEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_ENRICH_ENABLED] ?: true }
    val tmdbUseArtwork: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_ARTWORK] ?: true }
    val tmdbUseBasicInfo: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_BASIC_INFO] ?: true }
    val tmdbUseDetails: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_DETAILS] ?: true }
    val tmdbUseCredits: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_CREDITS] ?: true }
    val tmdbUseTrailers: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_TRAILERS] ?: true }
    val tmdbUseMoreLikeThis: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_MORE_LIKE_THIS] ?: true }
    val tmdbUseCollections: Flow<Boolean> = context.dataStore.data.map { it[Keys.TMDB_USE_COLLECTIONS] ?: true }
    val subtitleSize: Flow<String> = context.dataStore.data.map { it[Keys.SUBTITLE_SIZE] ?: "medium" }

    // ── Nuvio-style subtitle style (player panel) ─────────────────────────────
    /** Subtitle timing offset in milliseconds (negative = earlier). */
    val subtitleDelayMs: Flow<Long> = context.dataStore.data.map { it[Keys.SUBTITLE_DELAY_MS] ?: 0L }
    /** Font size as a percentage (100 = default). */
    val subtitleFontPercent: Flow<Int> = context.dataStore.data.map { it[Keys.SUBTITLE_FONT_PCT] ?: 100 }
    val subtitleBold: Flow<Boolean> = context.dataStore.data.map { it[Keys.SUBTITLE_BOLD] ?: false }
    /** Base text color (opaque ARGB int); opacity applied separately. Default white. */
    val subtitleTextColor: Flow<Int> = context.dataStore.data.map { it[Keys.SUBTITLE_TEXT_COLOR] ?: 0xFFFFFFFF.toInt() }
    val subtitleTextOpacityPercent: Flow<Int> = context.dataStore.data.map { it[Keys.SUBTITLE_TEXT_OPACITY_PCT] ?: 100 }
    val subtitleOutline: Flow<Boolean> = context.dataStore.data.map { it[Keys.SUBTITLE_OUTLINE] ?: true }

    /**
     * Internal player engine, matching Nuvio's picker:
     *  - [ENGINE_EXOPLAYER] (default): best compatibility with app features.
     *  - [ENGINE_MPV]: libmpv (beta) with our OSD controls; decodes more formats.
     *  - [ENGINE_AUTO]: ExoPlayer for Movies & TV Shows, MPV for Anime.
     */
    val playerEngine: Flow<String> = context.dataStore.data.map { it[Keys.PLAYER_ENGINE] ?: ENGINE_EXOPLAYER }

    /** MPV hardware decoding (default ON). Turn OFF if a device shows artifacts/black video. */
    val mpvHardwareDecoding: Flow<Boolean> = context.dataStore.data.map { it[Keys.MPV_HWDEC] ?: true }

    /** Preferred audio/subtitle language codes; "none" means no auto-selection. */
    val preferredAudioLanguage: Flow<String> = context.dataStore.data.map { it[Keys.PREF_AUDIO_LANG] ?: "none" }
    val preferredSubtitleLanguage: Flow<String> = context.dataStore.data.map { it[Keys.PREF_SUB_LANG] ?: "none" }

    /** Auto-advance to the next episode as the credits start (default ON). */
    val autoplayNextEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTOPLAY_NEXT] ?: true }

    /** Show a "Skip Intro" button early in TV episodes (default ON). */
    val skipIntroEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SKIP_INTRO] ?: true }

    /**
     * Nuvio-style stream feature filters. Default ANY (no filtering). Persisted
     * as enum names and resolved to a [com.streambert.tv.data.stream.StreamFilters]
     * snapshot via [currentStreamFilters].
     */
    val streamDolbyVisionFilter: Flow<String> =
        context.dataStore.data.map { it[Keys.STREAM_DV_FILTER] ?: DynamicRangeFilter.ANY.name }
    val streamHdrFilter: Flow<String> =
        context.dataStore.data.map { it[Keys.STREAM_HDR_FILTER] ?: DynamicRangeFilter.ANY.name }
    val streamCodecFilter: Flow<String> =
        context.dataStore.data.map { it[Keys.STREAM_CODEC_FILTER] ?: VideoCodecFilter.ANY.name }
    val streamMinQuality: Flow<String> =
        context.dataStore.data.map { it[Keys.STREAM_MIN_QUALITY] ?: MinQualityFilter.ANY.name }

    /**
     * Dolby Vision (DV7) handling. Defaults to **AUTO** (recommended): query
     * display capabilities and let the decoder handle DV. Other modes mirror
     * Nuvio: play the HDR10 base layer, convert DV7→DV8.1, strip DV, or pass
     * DV7 through untouched.
     */
    val dvHandlingMode: Flow<String> = context.dataStore.data.map { it[Keys.DV_HANDLING] ?: DV_AUTO }

    /** Auto-play on selection is ON by default (per product requirement). */
    val autoplay: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTOPLAY] ?: true }

    val preferredQuality: Flow<String> =
        context.dataStore.data.map { it[Keys.PREFER_QUALITY] ?: "1080p" }

    /**
     * Tunneled playback (default OFF). Tunneling can give better A/V sync and
     * HDR/DV handoff on capable TVs, but many Android TV / Google TV devices
     * misreport support and render a black screen with audio only — so it is
     * opt-in per-device from Settings.
     */
    val tunnelingEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TUNNELING] ?: false }

    /**
     * App is considered "configured" once a TMDB key exists. TorBox can be
     * added later from Settings, but without it playback will not work.
     * Emits null while the first value is still loading so the UI can show a
     * splash instead of briefly flashing the setup screen.
     */
    val isConfigured: Flow<Boolean> =
        context.dataStore.data.map {
            (it[Keys.TMDB_KEY].orEmpty()).isNotBlank() ||
                com.streambert.tv.BuildConfig.TMDB_DEFAULT_TOKEN.isNotBlank()
        }

    suspend fun currentTmdbKey(): String = tmdbKey.first()
    suspend fun currentTorboxKey(): String = torboxKey.first()
    suspend fun currentTmdbLang(): String = tmdbLang.first()
    suspend fun currentPreferredQuality(): String = preferredQuality.first()
    suspend fun currentAutoplay(): Boolean = autoplay.first()
    suspend fun currentTunnelingEnabled(): Boolean = tunnelingEnabled.first()
    suspend fun currentAddonType(): String = addonType.first()
    suspend fun currentCustomAddonUrl(): String = customAddonUrl.first()
    suspend fun currentOmdbKey(): String = omdbKey.first()
    suspend fun currentMdblistKey(): String = mdblistKey.first()
    suspend fun currentMdblistEnabled(): Boolean = mdblistEnabled.first()
    suspend fun currentGeminiKey(): String = geminiKey.first()
    suspend fun currentMdbShowImdb(): Boolean = mdbShowImdb.first()
    suspend fun currentMdbShowTomatoes(): Boolean = mdbShowTomatoes.first()
    suspend fun currentMdbShowAudience(): Boolean = mdbShowAudience.first()
    suspend fun currentMdbShowMetacritic(): Boolean = mdbShowMetacritic.first()
    suspend fun currentMdbShowTmdb(): Boolean = mdbShowTmdb.first()
    suspend fun currentMdbShowTrakt(): Boolean = mdbShowTrakt.first()
    suspend fun currentMdbShowLetterboxd(): Boolean = mdbShowLetterboxd.first()
    suspend fun currentTmdbEnrichEnabled(): Boolean = tmdbEnrichEnabled.first()
    suspend fun currentTmdbUseArtwork(): Boolean = tmdbUseArtwork.first()
    suspend fun currentTmdbUseBasicInfo(): Boolean = tmdbUseBasicInfo.first()
    suspend fun currentTmdbUseDetails(): Boolean = tmdbUseDetails.first()
    suspend fun currentTmdbUseCredits(): Boolean = tmdbUseCredits.first()
    suspend fun currentTmdbUseTrailers(): Boolean = tmdbUseTrailers.first()
    suspend fun currentTmdbUseMoreLikeThis(): Boolean = tmdbUseMoreLikeThis.first()
    suspend fun currentTmdbUseCollections(): Boolean = tmdbUseCollections.first()
    suspend fun currentSubtitleSize(): String = subtitleSize.first()
    suspend fun currentSubtitleDelayMs(): Long = subtitleDelayMs.first()
    suspend fun currentSubtitleFontPercent(): Int = subtitleFontPercent.first()
    suspend fun currentSubtitleBold(): Boolean = subtitleBold.first()
    suspend fun currentSubtitleTextColor(): Int = subtitleTextColor.first()
    suspend fun currentSubtitleTextOpacityPercent(): Int = subtitleTextOpacityPercent.first()
    suspend fun currentSubtitleOutline(): Boolean = subtitleOutline.first()
    suspend fun currentPlayerEngine(): String = playerEngine.first()
    suspend fun currentMpvHardwareDecoding(): Boolean = mpvHardwareDecoding.first()
    suspend fun currentPreferredAudioLanguage(): String = preferredAudioLanguage.first()
    suspend fun currentPreferredSubtitleLanguage(): String = preferredSubtitleLanguage.first()
    suspend fun currentAutoplayNextEnabled(): Boolean = autoplayNextEnabled.first()
    suspend fun currentSkipIntroEnabled(): Boolean = skipIntroEnabled.first()
    suspend fun currentStreamDolbyVisionFilter(): String = streamDolbyVisionFilter.first()
    suspend fun currentStreamHdrFilter(): String = streamHdrFilter.first()
    suspend fun currentStreamCodecFilter(): String = streamCodecFilter.first()
    suspend fun currentStreamMinQuality(): String = streamMinQuality.first()
    suspend fun currentDvHandlingMode(): String = dvHandlingMode.first()

    /** Resolve the persisted filter names into a usable [StreamFilters] snapshot. */
    suspend fun currentStreamFilters(): StreamFilters = StreamFilters(
        dolbyVision = DynamicRangeFilter.fromName(currentStreamDolbyVisionFilter()),
        hdr = DynamicRangeFilter.fromName(currentStreamHdrFilter()),
        codec = VideoCodecFilter.fromName(currentStreamCodecFilter()),
        minQuality = MinQualityFilter.fromName(currentStreamMinQuality())
    )
    suspend fun currentRealDebridKey(): String = realDebridKey.first()
    suspend fun currentScraperAddons(): String = scraperAddons.first()

    /**
     * All active stream sources. Torrentio mode uses **both** debrids when their
     * keys are set (TorBox and/or Real-Debrid) — results are merged so a title
     * cached on either service plays instantly. Scraper add-ons (no debrid baked
     * in) are appended and resolved via TorBox instant. Custom mode is a single addon.
     */
    suspend fun activeAddonSources(): List<AddonSource> {
        val out = mutableListOf<AddonSource>()
        if (currentAddonType() == ADDON_CUSTOM) {
            normalizeAddonUrl(currentCustomAddonUrl()).takeIf { it.isNotBlank() }?.let {
                out.add(AddonSource(it, isTorBox = false, resolveViaTorBox = false, label = "Addon"))
            }
        } else {
            currentTorboxKey().takeIf { it.isNotBlank() }?.let {
                out.add(AddonSource("$TORRENTIO_HOST/torbox=$it/", isTorBox = true, resolveViaTorBox = false, label = "Torrentio", debrid = DEBRID_TORBOX))
            }
            currentRealDebridKey().takeIf { it.isNotBlank() }?.let {
                out.add(AddonSource("$TORRENTIO_HOST/realdebrid=$it/", isTorBox = false, resolveViaTorBox = false, label = "Torrentio", debrid = DEBRID_RD))
            }
        }
        // Scraper addons return raw torrents; resolve them via a debrid service
        // (TorBox instant first, then Real-Debrid). Enabled if either key is set.
        if (currentTorboxKey().isNotBlank() || currentRealDebridKey().isNotBlank()) {
            scraperAddonSources().forEach { out.add(it) }
        }
        return out
    }

    private suspend fun scraperAddonSources(): List<AddonSource> =
        currentScraperAddons().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("http") }
            // Subtitle-only add-ons live in the same list but don't return streams — skip them here.
            .filterNot { isSubtitleAddonUrl(it) }
            .map { raw ->
                val base = normalizeAddonUrl(raw)
                val label = when {
                    base.contains("comet", true) -> "Comet"
                    base.contains("torrentio", true) -> "Torrentio"
                    else -> "Scraper"
                }
                // If the add-on already has a debrid configured (e.g. a
                // Torrentio "realdebrid=…"/"torbox=…" URL, or a Comet config
                // that names a debrid service), it returns directly-playable
                // URLs and its OWN cached markers. Treat it as a DIRECT source
                // so we use those URLs + markers instead of discarding them and
                // re-resolving every release by hash. Only fall back to
                // hash-resolution for a true no-debrid scraper.
                when (val debrid = detectAddonDebrid(base)) {
                    null -> AddonSource(base, isTorBox = true, resolveViaTorBox = true, label = label)
                    else -> AddonSource(
                        base,
                        isTorBox = debrid == DEBRID_TORBOX,
                        resolveViaTorBox = false,
                        label = label,
                        debrid = debrid
                    )
                }
            }
            .toList()

    /** Heuristic: a URL that points at a subtitles add-on (by its host/config). */
    private fun isSubtitleAddonUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("opensubtitle") || u.contains("subtitle") || u.contains("subdl") ||
            u.contains("subsource") || u.contains("wyzie") ||
            u.contains("subsense") || u.contains("nepiraw")
    }

    /**
     * Detects whether an add-on URL has a debrid service baked in — meaning it
     * returns directly-playable URLs (and its own cached markers) rather than
     * raw torrent hashes. Returns [DEBRID_TORBOX] / [DEBRID_RD] when confident,
     * or null for a no-debrid scraper (the safe default → hash-resolution).
     *
     * Conservative on purpose: a false "direct" would make the source return
     * zero playable options (its hash-only streams get filtered out), so we
     * only flag it when the config is unambiguous.
     */
    private fun detectAddonDebrid(url: String): String? {
        val u = url.lowercase()
        // Torrentio-style query segments are unambiguous.
        if (Regex("(^|[/=&?])torbox=").containsMatchIn(u)) return DEBRID_TORBOX
        if (Regex("(^|[/=&?])(realdebrid|real-debrid)=").containsMatchIn(u)) return DEBRID_RD

        // Comet / MediaFusion & friends encode their config as a base64 blob in
        // a path segment. Decode it and read the selected debrid service(s).
        val cfg = u + " " + decodeConfigBlobs(url)
        val services = Regex("\"debridservices\"\\s*:\\s*\\[([^\\]]*)\\]").find(cfg)?.groupValues?.get(1)
        if (!services.isNullOrBlank()) {
            if (services.contains("torbox") && !services.contains("realdebrid")) return DEBRID_TORBOX
            if (services.contains("realdebrid") || services.contains("real-debrid")) return DEBRID_RD
        }
        return null
    }

    /**
     * Best-effort decode of base64(url) path/query segments in an add-on URL so
     * [detectAddonDebrid] can read an embedded JSON config. Non-base64 or binary
     * segments are ignored; only text that looks like config is kept.
     */
    private fun decodeConfigBlobs(url: String): String {
        val out = StringBuilder()
        url.split('/', '?', '&', '=').forEach { seg ->
            val s = seg.trim().removeSuffix("manifest.json").trim('/')
            if (s.length < 16) return@forEach
            runCatching {
                // base64url in URLs usually drops the '=' padding — restore it.
                val padded = s + "=".repeat((4 - s.length % 4) % 4)
                val bytes = android.util.Base64.decode(padded, android.util.Base64.URL_SAFE)
                val text = String(bytes, Charsets.UTF_8)
                if (text.contains("{") || text.contains("debrid", true)) {
                    out.append(text.lowercase()).append(' ')
                }
            }
        }
        return out.toString()
    }

    /** Normalized base URLs of every installed add-on — subtitle add-ons are queried from this same list. */
    suspend fun addonBaseUrls(): List<String> =
        currentScraperAddons().lineSequence()
            .map { normalizeAddonUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    /** Subtitle text size as a fraction of the video height (Media3 SubtitleView). */
    suspend fun currentSubtitleFraction(): Float = when (currentSubtitleSize()) {
        "small" -> 0.040f
        "large" -> 0.090f
        else -> 0.060f
    }

    /** Human label for the active source, used in progress messages. */
    suspend fun currentAddonLabel(): String =
        if (currentAddonType() == ADDON_CUSTOM) "your addon" else "Torrentio"

    private fun normalizeAddonUrl(raw: String): String {
        var url = raw.trim()
        if (url.isBlank()) return ""
        if (!url.startsWith("http")) url = "https://$url"
        // A pasted manifest link ends in /manifest.json — drop that.
        url = url.removeSuffix("manifest.json")
        if (!url.endsWith("/")) url = "$url/"
        return url
    }

    suspend fun setTmdbKey(value: String) =
        context.dataStore.edit { it[Keys.TMDB_KEY] = value.trim() }

    suspend fun setTorboxKey(value: String) =
        context.dataStore.edit { it[Keys.TORBOX_KEY] = value.trim() }

    suspend fun setTmdbLang(value: String) =
        context.dataStore.edit { it[Keys.TMDB_LANG] = value }

    suspend fun setAutoplay(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTOPLAY] = value }

    suspend fun setPreferredQuality(value: String) =
        context.dataStore.edit { it[Keys.PREFER_QUALITY] = value }

    suspend fun currentBadgeConfigUrl(): String = badgeConfigUrl.first()

    suspend fun setBadgeConfigUrl(value: String) =
        context.dataStore.edit { it[Keys.BADGE_CONFIG_URL] = value.trim() }

    // ── Trakt.tv accessors ────────────────────────────────────────────────
    suspend fun currentTraktClientId(): String = traktClientId.first()
    suspend fun currentTraktClientSecret(): String = traktClientSecret.first()
    suspend fun currentTraktAccessToken(): String = traktAccessToken.first()
    suspend fun currentTraktRefreshToken(): String = traktRefreshToken.first()
    suspend fun currentTraktUsername(): String = traktUsername.first()
    suspend fun currentTraktCreatedAt(): Long = context.dataStore.data.first()[Keys.TRAKT_CREATED_AT] ?: 0L
    suspend fun currentTraktExpiresIn(): Long = context.dataStore.data.first()[Keys.TRAKT_EXPIRES_IN] ?: 0L
    suspend fun currentTraktAuthenticated(): Boolean = traktAuthenticated.first()

    suspend fun setTraktClientId(value: String) =
        context.dataStore.edit { it[Keys.TRAKT_CLIENT_ID] = value.trim() }

    suspend fun setTraktClientSecret(value: String) =
        context.dataStore.edit { it[Keys.TRAKT_CLIENT_SECRET] = value.trim() }

    /** Persist an OAuth token set (created_at/expires_in are epoch-seconds/seconds). */
    suspend fun saveTraktToken(accessToken: String, refreshToken: String, createdAt: Long, expiresIn: Long) =
        context.dataStore.edit {
            it[Keys.TRAKT_ACCESS_TOKEN] = accessToken
            it[Keys.TRAKT_REFRESH_TOKEN] = refreshToken
            it[Keys.TRAKT_CREATED_AT] = createdAt
            it[Keys.TRAKT_EXPIRES_IN] = expiresIn
        }

    suspend fun setTraktUsername(value: String) =
        context.dataStore.edit {
            if (value.isBlank()) it.remove(Keys.TRAKT_USERNAME) else it[Keys.TRAKT_USERNAME] = value
        }

    /** Clear all Trakt tokens + username (keeps client credentials). */
    suspend fun clearTraktAuth() =
        context.dataStore.edit {
            it.remove(Keys.TRAKT_ACCESS_TOKEN)
            it.remove(Keys.TRAKT_REFRESH_TOKEN)
            it.remove(Keys.TRAKT_CREATED_AT)
            it.remove(Keys.TRAKT_EXPIRES_IN)
            it.remove(Keys.TRAKT_USERNAME)
        }

    suspend fun setTunnelingEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.TUNNELING] = value }

    suspend fun setAddonType(value: String) =
        context.dataStore.edit { it[Keys.ADDON_TYPE] = value }

    suspend fun setCustomAddonUrl(value: String) =
        context.dataStore.edit { it[Keys.CUSTOM_ADDON_URL] = value.trim() }

    suspend fun setRealDebridKey(value: String) =
        context.dataStore.edit { it[Keys.REALDEBRID_KEY] = value.trim() }

    suspend fun setScraperAddons(value: String) =
        context.dataStore.edit { it[Keys.SCRAPER_ADDONS] = value.trim() }


    suspend fun setOmdbKey(value: String) =
        context.dataStore.edit { it[Keys.OMDB_KEY] = value.trim() }

    suspend fun setMdblistKey(value: String) =
        context.dataStore.edit { it[Keys.MDBLIST_KEY] = value.trim() }

    suspend fun setGeminiKey(value: String) =
        context.dataStore.edit { it[Keys.GEMINI_KEY] = value.trim() }

    suspend fun setMdblistEnabled(v: Boolean) = context.dataStore.edit { it[Keys.MDBLIST_ENABLED] = v }
    suspend fun setMdbShowImdb(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_IMDB] = v }
    suspend fun setMdbShowTomatoes(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_TOMATOES] = v }
    suspend fun setMdbShowAudience(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_AUDIENCE] = v }
    suspend fun setMdbShowMetacritic(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_METACRITIC] = v }
    suspend fun setMdbShowTmdb(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_TMDB] = v }
    suspend fun setMdbShowTrakt(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_TRAKT] = v }
    suspend fun setMdbShowLetterboxd(v: Boolean) = context.dataStore.edit { it[Keys.MDB_SHOW_LETTERBOXD] = v }
    suspend fun setTmdbEnrichEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_ENRICH_ENABLED] = v }
    suspend fun setTmdbUseArtwork(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_ARTWORK] = v }
    suspend fun setTmdbUseBasicInfo(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_BASIC_INFO] = v }
    suspend fun setTmdbUseDetails(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_DETAILS] = v }
    suspend fun setTmdbUseCredits(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_CREDITS] = v }
    suspend fun setTmdbUseTrailers(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_TRAILERS] = v }
    suspend fun setTmdbUseMoreLikeThis(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_MORE_LIKE_THIS] = v }
    suspend fun setTmdbUseCollections(v: Boolean) = context.dataStore.edit { it[Keys.TMDB_USE_COLLECTIONS] = v }

    suspend fun setSubtitleSize(value: String) =
        context.dataStore.edit { it[Keys.SUBTITLE_SIZE] = value }

    suspend fun setSubtitleDelayMs(value: Long) =
        context.dataStore.edit { it[Keys.SUBTITLE_DELAY_MS] = value }
    suspend fun setSubtitleFontPercent(value: Int) =
        context.dataStore.edit { it[Keys.SUBTITLE_FONT_PCT] = value.coerceIn(50, 250) }
    suspend fun setSubtitleBold(value: Boolean) =
        context.dataStore.edit { it[Keys.SUBTITLE_BOLD] = value }
    suspend fun setSubtitleTextColor(value: Int) =
        context.dataStore.edit { it[Keys.SUBTITLE_TEXT_COLOR] = value }
    suspend fun setSubtitleTextOpacityPercent(value: Int) =
        context.dataStore.edit { it[Keys.SUBTITLE_TEXT_OPACITY_PCT] = value.coerceIn(0, 100) }
    suspend fun setSubtitleOutline(value: Boolean) =
        context.dataStore.edit { it[Keys.SUBTITLE_OUTLINE] = value }

    suspend fun setPlayerEngine(value: String) =
        context.dataStore.edit { it[Keys.PLAYER_ENGINE] = value }

    suspend fun setStreamDolbyVisionFilter(value: String) =
        context.dataStore.edit { it[Keys.STREAM_DV_FILTER] = value }

    suspend fun setStreamHdrFilter(value: String) =
        context.dataStore.edit { it[Keys.STREAM_HDR_FILTER] = value }

    suspend fun setStreamCodecFilter(value: String) =
        context.dataStore.edit { it[Keys.STREAM_CODEC_FILTER] = value }

    suspend fun setStreamMinQuality(value: String) =
        context.dataStore.edit { it[Keys.STREAM_MIN_QUALITY] = value }

    suspend fun setDvHandlingMode(value: String) =
        context.dataStore.edit { it[Keys.DV_HANDLING] = value }

    suspend fun setMpvHardwareDecoding(value: Boolean) =
        context.dataStore.edit { it[Keys.MPV_HWDEC] = value }

    suspend fun setPreferredAudioLanguage(value: String) =
        context.dataStore.edit { it[Keys.PREF_AUDIO_LANG] = value }

    suspend fun setPreferredSubtitleLanguage(value: String) =
        context.dataStore.edit { it[Keys.PREF_SUB_LANG] = value }

    suspend fun setAutoplayNextEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTOPLAY_NEXT] = value }

    suspend fun setSkipIntroEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.SKIP_INTRO] = value }
}
