package com.streambert.tv.data.realdebrid

import com.streambert.tv.data.settings.SettingsRepository
import com.streambert.tv.data.stream.StreamResolution
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Resolves torrent info-hashes into directly-playable Real-Debrid stream URLs.
 *
 * This is the Real-Debrid counterpart to [com.streambert.tv.data.torbox.TorBoxRepository]:
 * scraper addons (Torrentio no-debrid / Comet) hand us a hash, and RD turns it
 * into an HTTPS stream. Real-Debrid has no public bulk instant-availability
 * endpoint anymore, so "instant" is determined by adding the magnet and seeing
 * whether RD reports it already `downloaded` (cached) within a short poll window.
 *
 * Own implementation written against the public Real-Debrid REST API.
 */
class RealDebridRepository(
    private val api: RealDebridApi,
    private val settings: SettingsRepository
) {

    /**
     * Build a magnet from an info hash and resolve it to a playable URL.
     *
     * [instantOnly] = true (auto-resolve path) only waits a short window for RD
     * to report the release already `downloaded` (cached); if it isn't, it
     * fails fast so the caller can try the next cached source instead of
     * blocking on a download. An explicit pick passes false and gets the full
     * wait window.
     */
    suspend fun resolveHash(
        hash: String,
        name: String,
        season: Int? = null,
        episode: Int? = null,
        instantOnly: Boolean = false
    ): StreamResolution {
        val key = settings.currentRealDebridKey().trim()
        if (key.isBlank()) {
            return StreamResolution.Failure("No Real-Debrid API key set. Add it in Settings to enable playback.")
        }
        val auth = "Bearer $key"
        val magnet = magnetFromHash(hash, name)

        val torrentId = try {
            val add = api.addMagnet(auth, magnet)
            add.body()?.id?.takeIf { add.isSuccessful && it.isNotBlank() }
        } catch (e: Exception) {
            return StreamResolution.Failure("Failed to add source to Real-Debrid: ${e.message}")
        } ?: return StreamResolution.Failure("Real-Debrid did not accept the source.")

        var resolved = false
        try {
            // 1) Read the file list.
            val info = runCatching { api.getTorrentInfo(auth, torrentId) }.getOrNull()
            val files = info?.body()?.files.orEmpty()
            if (info?.isSuccessful != true || files.isEmpty()) {
                return StreamResolution.Failure("Real-Debrid couldn't read the source files.")
            }

            // 2) Select the target file (episode match, else largest video).
            val file = pickBestFile(files, season, episode)
                ?: return StreamResolution.Failure("No playable video file in the source.")
            val select = runCatching { api.selectFiles(auth, torrentId, file.id.toString()) }.getOrNull()
            if (select == null || (!select.isSuccessful && select.code() != 202)) {
                return StreamResolution.Failure("Real-Debrid rejected file selection.")
            }

            // 3) Wait for RD to have the file ready (instant if already cached).
            val ready = awaitDownloaded(
                auth,
                torrentId,
                maxPolls = if (instantOnly) FAST_POLLS else MAX_POLLS,
                intervalMs = if (instantOnly) FAST_POLL_INTERVAL_MS else POLL_INTERVAL_MS
            ) ?: return StreamResolution.Failure(
                if (instantOnly) "NOT_CACHED: not instantly available on Real-Debrid."
                else "Source isn't cached on Real-Debrid yet and is still downloading. " +
                    "Try another source or play again shortly."
            )
            val link = ready.links.firstOrNull { it.isNotBlank() }
                ?: return StreamResolution.Failure("Real-Debrid returned no download link.")

            // 4) Unrestrict to a direct stream URL.
            val unrestrict = runCatching { api.unrestrictLink(auth, link) }.getOrNull()
            val url = unrestrict?.body()?.download?.takeIf { unrestrict.isSuccessful && it.isNotBlank() }
                ?: return StreamResolution.Failure("Real-Debrid could not unrestrict the link.")

            resolved = true
            val label = unrestrict.body()?.filename?.takeIf { it.isNotBlank() }
                ?: file.displayName.takeIf { it.isNotBlank() }
                ?: name
            return StreamResolution.Ready(url = url, label = label)
        } finally {
            // Clean up the torrent entry if we didn't end up playing it.
            if (!resolved) runCatching { api.deleteTorrent(auth, torrentId) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private suspend fun awaitDownloaded(
        auth: String,
        torrentId: String,
        maxPolls: Int = MAX_POLLS,
        intervalMs: Long = POLL_INTERVAL_MS
    ): RdTorrentInfo? {
        repeat(maxPolls) { attempt ->
            val info = runCatching { api.getTorrentInfo(auth, torrentId).body() }.getOrNull()
            if (info != null &&
                info.status.equals("downloaded", ignoreCase = true) &&
                info.links.any { it.isNotBlank() }
            ) return info
            if (attempt < maxPolls - 1) delay(intervalMs)
        }
        return null
    }

    private fun pickBestFile(files: List<RdFile>, season: Int?, episode: Int?): RdFile? {
        val videos = files.filter { isVideo(it.path) }
        val pool = videos.ifEmpty { files }
        if (season != null && episode != null) {
            pool.firstOrNull { matchesEpisode(it.path, season, episode) }?.let { return it }
        }
        return pool.maxByOrNull { it.bytes }
    }

    private fun magnetFromHash(hash: String, name: String): String {
        val dn = java.net.URLEncoder.encode(name, "UTF-8")
        val trackers = DEFAULT_TRACKERS.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$hash&dn=$dn$trackers"
    }

    private fun isVideo(name: String?): Boolean {
        val n = name?.lowercase(Locale.ROOT) ?: return false
        return VIDEO_EXTS.any { n.endsWith(it) }
    }

    private fun matchesEpisode(name: String?, season: Int, episode: Int): Boolean {
        val n = name?.lowercase(Locale.ROOT) ?: return false
        val s = season.toString().padStart(2, '0')
        val e = episode.toString().padStart(2, '0')
        return Regex("s0*${season}e0*${episode}\\b").containsMatchIn(n) ||
            n.contains("s${s}e$e") ||
            Regex("\\b0*${season}x0*${episode}\\b").containsMatchIn(n)
    }

    companion object {
        private const val MAX_POLLS = 8
        private const val POLL_INTERVAL_MS = 1500L
        // Instant-only (auto path): a short window is enough for RD to report a
        // cached release as `downloaded`; anything longer is a real download.
        private const val FAST_POLLS = 3
        private const val FAST_POLL_INTERVAL_MS = 700L
        private val VIDEO_EXTS = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts")
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce"
        )
    }
}
