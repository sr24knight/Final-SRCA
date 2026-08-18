package com.streambert.tv.data.torbox

import com.streambert.tv.data.settings.SettingsRepository
import com.streambert.tv.data.stream.StreamResolution
import kotlinx.coroutines.delay
import okhttp3.MultipartBody
import java.util.Locale

/**
 * Resolves torrent info-hashes into directly-playable TorBox stream URLs, and
 * checks TorBox instant (cached) availability.
 *
 * This backs the "TorBox instant" flow for scraper addons (Torrentio/Comet
 * without a debrid baked in): they return hashes, TorBox tells us which are
 * instantly cached, and we resolve the chosen one to an HTTPS stream.
 */
class TorBoxRepository(
    private val api: TorBoxApi,
    private val settings: SettingsRepository
) {

    /** Subset of [hashes] that are instantly cached on TorBox. */
    suspend fun instantHashes(hashes: List<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        return try {
            api.checkCached(hash = hashes.joinToString(","))
                .data.orEmpty()
                .mapNotNull { it.hash?.lowercase(Locale.ROOT) }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Build a magnet from an info hash and resolve it to a playable URL.
     *
     * [instantOnly] = true means "cached or nothing": if TorBox doesn't already
     * have the release, fail fast so the caller can roll to the next cached
     * source instead of waiting on a download. This backs the auto-resolve
     * path. An explicit pick from the source list passes false, keeping the
     * download-and-wait fallback.
     */
    suspend fun resolveHash(
        hash: String,
        name: String,
        season: Int? = null,
        episode: Int? = null,
        instantOnly: Boolean = false
    ): StreamResolution = resolveMagnet(magnetFromHash(hash, name), season, episode, instantOnly)

    /** Add a magnet to TorBox and resolve it to a direct stream URL. */
    suspend fun resolveMagnet(
        magnet: String,
        season: Int? = null,
        episode: Int? = null,
        instantOnly: Boolean = false
    ): StreamResolution {
        val token = settings.currentTorboxKey()
        if (token.isBlank()) {
            return StreamResolution.Failure("No TorBox API key set. Add it in Settings to enable playback.")
        }

        // Fast path (Debrify-style): ask TorBox to add the magnet ONLY if it's
        // already cached. A cached release comes back instantly with a ready
        // torrent id and its files are immediately listable, so playback starts
        // right away with no download-poll wait.
        val instant = try {
            api.createTorrent(
                magnet = part("magnet", magnet),
                seed = part("seed", "1"),
                allowZip = part("allow_zip", "false"),
                addOnlyIfCached = part("add_only_if_cached", "true")
            )
        } catch (e: Exception) {
            null
        }
        val instantId = instant?.data?.torrentId ?: instant?.data?.queuedId

        val torrentId: Long
        val cachedFast: Boolean
        if (instant?.success == true && instantId != null) {
            torrentId = instantId
            cachedFast = true
        } else if (instantOnly) {
            // Auto path: don't wait on a download — let the caller try the next
            // cached source. Marked NOT_CACHED so callers can recognise it.
            return StreamResolution.Failure("NOT_CACHED: not instantly available on TorBox.")
        } else {
            // Explicit pick of an uncached source — add it for real and wait.
            val created = try {
                api.createTorrent(
                    magnet = part("magnet", magnet),
                    seed = part("seed", "1"),
                    allowZip = part("allow_zip", "false"),
                    addOnlyIfCached = part("add_only_if_cached", "false")
                )
            } catch (e: Exception) {
                return StreamResolution.Failure("Failed to add source: ${e.message}")
            }
            torrentId = created.data?.torrentId ?: created.data?.queuedId
                ?: return StreamResolution.Failure(
                    created.detail ?: created.error ?: "TorBox did not return a torrent id."
                )
            cachedFast = false
        }

        // Cached torrents list their files immediately, so only give them a
        // short budget; genuine downloads get the full poll window.
        val torrent = awaitFiles(
            torrentId,
            maxPolls = if (cachedFast) FAST_POLLS else MAX_POLLS,
            intervalMs = if (cachedFast) FAST_POLL_INTERVAL_MS else POLL_INTERVAL_MS
        ) ?: return StreamResolution.Failure(
            if (cachedFast) "TorBox reported the source cached but didn't return its files."
            else "Source isn't cached yet and is still downloading. Try another source or play again shortly."
        )

        val file = pickBestFile(torrent.files, season, episode)
            ?: return StreamResolution.Failure("No playable video file in the source.")

        val link = try {
            api.requestDownloadLink(token = token, torrentId = torrentId, fileId = file.id, redirect = false).data
        } catch (e: Exception) {
            return StreamResolution.Failure("Failed to get stream link: ${e.message}")
        }

        if (link.isNullOrBlank()) return StreamResolution.Failure("TorBox returned an empty stream link.")
        return StreamResolution.Ready(url = link, label = file.name ?: torrent.name ?: "Stream")
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private suspend fun awaitFiles(
        torrentId: Long,
        maxPolls: Int = MAX_POLLS,
        intervalMs: Long = POLL_INTERVAL_MS
    ): TorBoxTorrent? {
        repeat(maxPolls) { attempt ->
            val torrent = runCatching { api.getTorrent(torrentId).data }.getOrNull()
            if (torrent != null && torrent.files.isNotEmpty() &&
                (torrent.downloadPresent || torrent.downloadFinished || torrent.cached || torrent.files.isNotEmpty())
            ) return torrent
            if (attempt < maxPolls - 1) delay(intervalMs)
        }
        return null
    }

    private fun pickBestFile(files: List<TorBoxFile>, season: Int?, episode: Int?): TorBoxFile? {
        val videos = files.filter { isVideo(it.name) }
        if (videos.isEmpty()) return files.maxByOrNull { it.size }
        if (season != null && episode != null) {
            videos.firstOrNull { matchesEpisode(it.name, season, episode) }?.let { return it }
        }
        return videos.maxByOrNull { it.size }
    }

    private fun part(name: String, value: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, value)

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
        // Cached torrents should list files on the first probe; a couple of
        // short retries just absorb TorBox listing lag.
        private const val FAST_POLLS = 3
        private const val FAST_POLL_INTERVAL_MS = 500L
        private val VIDEO_EXTS = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts")
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce"
        )
    }
}
