package com.streambert.tv.data.stream

import com.streambert.tv.data.realdebrid.RealDebridRepository
import com.streambert.tv.data.settings.SettingsRepository
import com.streambert.tv.data.torbox.TorBoxRepository
import java.util.Locale

/**
 * Aggregates playable streams from the user's configured sources:
 *  - **Debrid-backed Torrentio** (TorBox and/or Real-Debrid): returns
 *    already-resolved direct URLs.
 *  - **Scraper addons** (Torrentio no-debrid, Comet): return torrent hashes,
 *    which are resolved on play through the user's debrid service — **TorBox**
 *    (instant-checked via checkcached) is tried first, then **Real-Debrid**
 *    (addMagnet → selectFiles → unrestrict) as a fallback.
 *
 * Everything is merged, de-duplicated, and sorted instant-first.
 */
class StreamRepository(
    private val api: StremioApi,
    private val torbox: TorBoxRepository,
    private val realDebrid: RealDebridRepository,
    private val settings: SettingsRepository
) {

    suspend fun resolveStream(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        onProgress: (String) -> Unit = {}
    ): StreamResolution {
        if (settings.activeAddonSources().isEmpty()) {
            return StreamResolution.Failure(
                "No debrid configured. Add your TorBox and/or Real-Debrid key in Settings."
            )
        }
        onProgress("Finding an instant source…")
        val options = runCatching { buildOptions(imdbId, season, episode) }.getOrNull().orEmpty()
        if (options.isEmpty()) {
            return StreamResolution.Failure(
                "No cached sources found yet. Try another title/quality or play again shortly."
            )
        }

        // Auto-resolve is instant-only: walk the ranked (cached-first) list and
        // resolve each candidate with no download wait. The first one that's
        // actually cached plays; a source that turns out not to be cached fails
        // fast and we roll straight to the next — so the auto path never stalls
        // on a download. (Explicitly picking a source from the list still
        // downloads-and-waits, via resolveHash → resolveViaDebrid instantOnly=false.)
        var attempts = 0
        var lastFailure: String? = null
        for (opt in options) {
            // Debrid-backed sources already carry a resolved URL — play now.
            opt.url?.let { return StreamResolution.Ready(it, opt.label) }
            val hash = opt.hash ?: continue
            if (attempts >= MAX_INSTANT_ATTEMPTS) break
            attempts++
            onProgress(
                if (attempts == 1) "Preparing instant debrid stream…"
                else "Trying the next cached source…"
            )
            when (val r = resolveViaDebrid(hash, opt.label, season, episode, opt.debrid, instantOnly = true)) {
                is StreamResolution.Ready -> return r
                is StreamResolution.Failure -> lastFailure = r.message
                is StreamResolution.Progress -> { /* keep trying the next source */ }
            }
        }
        return StreamResolution.Failure(
            lastFailure?.takeUnless { it.startsWith("NOT_CACHED") }
                ?: "No cached sources are ready right now. Open the source list to pick one to download, or try another quality/title."
        )
    }

    /**
     * Resolve a specific chosen source (from the picker) to a playable URL.
     * [debrid] pins it to a service ("TorBox"/"RD") the user picked; null lets
     * it fall back across whatever is configured.
     */
    suspend fun resolveHash(
        hash: String,
        title: String,
        season: Int?,
        episode: Int?,
        debrid: String? = null
    ): StreamResolution = resolveViaDebrid(hash, title, season, episode, debrid)

    /**
     * Resolve a torrent hash to a playable URL through the configured debrid
     * service(s): TorBox first (fast instant check + CDN), then Real-Debrid as
     * a fallback. Whichever has the release cached plays instantly.
     */
    private suspend fun resolveViaDebrid(
        hash: String,
        title: String,
        season: Int?,
        episode: Int?,
        debrid: String? = null,
        instantOnly: Boolean = false
    ): StreamResolution {
        val hasTorBox = settings.currentTorboxKey().isNotBlank()
        val hasRealDebrid = settings.currentRealDebridKey().isNotBlank()
        // Honour an explicit picker choice — play through exactly that service.
        when (debrid) {
            SettingsRepository.DEBRID_TORBOX ->
                if (hasTorBox) return torbox.resolveHash(hash, title, season, episode, instantOnly)
            SettingsRepository.DEBRID_RD ->
                if (hasRealDebrid) return realDebrid.resolveHash(hash, title, season, episode, instantOnly)
        }
        // Auto / fallback: TorBox first (fast instant check + CDN), then RD.
        if (hasTorBox) {
            val result = torbox.resolveHash(hash, title, season, episode, instantOnly)
            if (result is StreamResolution.Ready || !hasRealDebrid) return result
        }
        if (hasRealDebrid) {
            return realDebrid.resolveHash(hash, title, season, episode, instantOnly)
        }
        return StreamResolution.Failure(
            "No debrid configured. Add a TorBox or Real-Debrid key in Settings."
        )
    }

    private companion object {
        // How many cached candidates the auto path will try (fail-fast each)
        // before giving up. Bounds worst-case time when instant flags are stale.
        const val MAX_INSTANT_ATTEMPTS = 4
    }

    /** All playable streams, instant-first then by quality, for the picker. */
    suspend fun listStreams(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamOption> = try {
        buildOptions(imdbId, season, episode)
    } catch (e: Exception) {
        emptyList()
    }

    // ── Core ─────────────────────────────────────────────────────────────
    private suspend fun buildOptions(
        imdbId: String,
        season: Int?,
        episode: Int?
    ): List<StreamOption> {
        val sources = settings.activeAddonSources()
        if (sources.isEmpty()) return emptyList()

        val type = if (season != null && episode != null) "series" else "movie"
        val id = if (type == "series") "$imdbId:$season:$episode" else imdbId
        val preferred = qualityToInt(settings.currentPreferredQuality())

        val all = mutableListOf<StreamOption>()
        val hasTorBox = settings.currentTorboxKey().isNotBlank()
        val hasRealDebrid = settings.currentRealDebridKey().isNotBlank()
        for (src in sources) {
            val raw = runCatching {
                api.getStreams("${src.baseUrl}stream/$type/$id.json").streams
            }.getOrDefault(emptyList())

            // Debrid sources need a resolved url; scraper sources need a hash.
            val streams = raw.filter {
                if (src.resolveViaTorBox) hashOf(it) != null else !it.url.isNullOrBlank()
            }
            if (streams.isEmpty()) continue

            val hashByStream = streams.associateWith { hashOf(it) }
            // TorBox instant-availability (used by scraper + torbox-direct sources).
            val cached = if (src.isTorBox && hasTorBox)
                torbox.instantHashes(hashByStream.values.filterNotNull().distinct())
            else emptySet()

            streams.forEach { s ->
                val text = "${s.name.orEmpty()} ${s.title.orEmpty()}"
                val q = parseQuality(text.lowercase(Locale.ROOT))
                val hash = hashByStream[s]

                fun option(debrid: String?, instant: Boolean) = StreamOption(
                    url = if (src.resolveViaTorBox) null else s.url,
                    hash = hash,
                    label = s.label,
                    qualityLabel = qualityLabel(q),
                    quality = q,
                    cached = instant,
                    instant = instant,
                    provider = src.label,
                    debrid = debrid,
                    badges = ReleaseBadges.parse(text),
                    sizeLabel = parseSize(text),
                    container = parseContainer(text),
                    language = parseLanguage(text),
                    seeders = parseSeeders(text)
                )

                if (src.resolveViaTorBox) {
                    // Scraper hash source — offer it through EACH connected debrid
                    // so TorBox and Real-Debrid show up as separate options.
                    if (hasTorBox) all.add(option(SettingsRepository.DEBRID_TORBOX, hash != null && hash in cached))
                    if (hasRealDebrid) all.add(option(SettingsRepository.DEBRID_RD, markerCached(s, SettingsRepository.DEBRID_RD)))
                } else {
                    // Direct URL already resolved by a specific debrid service.
                    val instant = if (src.isTorBox) (hash != null && hash in cached) else markerCached(s, src.debrid)
                    all.add(option(src.debrid, instant))
                }
            }
        }

        // Nuvio-style feature filtering (Dolby Vision / HDR / codec / min-quality).
        // Applied as a hard constraint, exactly like Nuvio's stream filters — the
        // app simply won't offer/auto-pick releases the user filtered out.
        val filtered = settings.currentStreamFilters().applyTo(all)

        return filtered
            // Keep TorBox and RD variants of the same release as separate options.
            .distinctBy { "${it.hash ?: it.url}_${it.debrid}" }
            .sortedByDescending { score(it, preferred) }
    }

    private fun hashOf(stream: StremioStream): String? {
        stream.infoHash?.takeIf { it.length == 40 }?.let { return it.lowercase(Locale.ROOT) }
        val m = Regex("[a-fA-F0-9]{40}").find(stream.url.orEmpty())
        return m?.value?.lowercase(Locale.ROOT)
    }

    /**
     * Best-effort read of an addon's OWN "already cached" marker, for [debrid].
     *
     * Real-Debrid has no bulk instant-availability API anymore, so for RD the
     * only cache signal is what the addon writes into the release text:
     *   • Torrentio tags cached releases with a debrid code + plus, e.g.
     *     "[RD+]" / "[AD+]" / "[TB+]", and un-cached ones with "[RD download]".
     *   • Comet / MediaFusion use a "⚡" bolt; some write "cached"/"instant".
     *
     * This is only a hint for ORDERING + badges — the authoritative check is
     * the instant-only resolve at play time. It must be:
     *   • Precise: a stray "+" (DDP5.1+, HDR10+, H.264+) must NOT count.
     *   • Provider-aware: an RD option must not inherit a "[TB+]" TorBox tag.
     *   • Honest about negatives: "[RD download]" / "uncached" means NOT cached.
     *
     * [debrid] = null accepts any known provider marker.
     */
    private fun markerCached(stream: StremioStream, debrid: String? = null): Boolean {
        val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()} ${stream.description.orEmpty()}"
            .lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        // Explicit "not cached" signals win outright — trust the addon.
        if (Regex("uncached|not cached|\\b(download|downloading|queued)\\b").containsMatchIn(text)) {
            return false
        }
        // Provider-specific "[rd+]" / "rd +" style cached tag. \b before the
        // code stops "hard+" matching "rd+" and keeps a stray "+" from counting.
        val codes = when (debrid) {
            SettingsRepository.DEBRID_RD -> listOf("rd", "real-debrid", "realdebrid")
            SettingsRepository.DEBRID_TORBOX -> listOf("tb", "torbox")
            else -> listOf("rd", "tb", "ad", "pm", "dl", "oc", "real-debrid", "realdebrid", "torbox")
        }
        if (codes.any { Regex("\\b$it\\s*\\+").containsMatchIn(text) }) return true
        // Generic instant markers (Comet / MediaFusion "⚡", or the words).
        return text.contains("⚡") || Regex("\\b(cached|instant)\\b").containsMatchIn(text)
    }

    /**
     * Ranking for auto-picking the fastest, most playable source. Priority
     * (each tier dominates the next):
     *   1. Cached/instant on debrid (streams immediately, no download wait).
     *   2. Closest to the user's preferred quality (1080p or 4K, per Settings).
     *   3. Most seeders (fastest for the debrid to serve / least likely to stall).
     * CAM/telesync rips are pushed to the bottom.
     */
    private fun score(option: StreamOption, preferredQuality: Int): Long {
        var s = 0L
        // (1) Cached first — by far the biggest speed factor.
        if (option.instant) s += 1_000_000
        // (2) Closest to preferred quality (range ~0..100k; gaps between
        //     resolutions are tens of thousands, so quality outranks seeders).
        s += (100_000 - kotlin.math.abs(option.quality - preferredQuality) * 40).coerceAtLeast(0).toLong()
        // (3) Most seeders — capped so it only breaks ties within the same
        //     cached + quality tier (max +5000).
        s += (option.seeders.coerceIn(0, 1000) * 5).toLong()
        val lower = option.label.lowercase(Locale.ROOT)
        if (lower.contains("cam") || lower.contains("hdts") || lower.contains("telesync")) s -= 200_000
        return s
    }

    /** Seeder count from the release title. Torrentio/Comet use "👤 123";
     *  falls back to "Seeders: 123" style. Returns 0 when unknown. */
    private fun parseSeeders(text: String): Int {
        Regex("\uD83D\uDC64\\s*(\\d+)").find(text)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("(?:seeders?|seeds)\\s*[:=]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(text)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
        return 0
    }

    private fun qualityLabel(q: Int): String = when (q) {
        2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; 480 -> "480p"; else -> "SD"
    }

    private fun parseQuality(text: String): Int = when {
        text.contains("2160") || text.contains("4k") -> 2160
        text.contains("1080") -> 1080
        text.contains("720") -> 720
        text.contains("480") -> 480
        else -> 0
    }

    private fun qualityToInt(q: String): Int = q.filter { it.isDigit() }.toIntOrNull() ?: 1080

    /** Pulls a "5.6 GB" / "720 MB" style size out of the release text, if present. */
    private fun parseSize(text: String): String? {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(gb|mb|tb)", RegexOption.IGNORE_CASE).find(text) ?: return null
        val num = m.groupValues[1].replace(',', '.')
        val unit = m.groupValues[2].uppercase(Locale.ROOT)
        return "$num $unit"
    }

    /** Detects the file container (MKV/MP4/…) from the release text, if present. */
    private fun parseContainer(text: String): String? {
        val m = Regex("\\b(mkv|mp4|avi|m2ts|ts|mov|wmv|webm)\\b", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return m.groupValues[1].uppercase(Locale.ROOT)
    }

    /** Language hint for the picker. Torrentio rarely tags a single language, so
     *  we show "Multi" when multiple are flagged and "Original" otherwise. */
    private fun parseLanguage(text: String): String {
        val t = text.lowercase(Locale.ROOT)
        return if (Regex("multi|\\bdual\\b|vostfr").containsMatchIn(t)) "Multi" else "Original"
    }
}
