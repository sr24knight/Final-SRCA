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

    /** Build a magnet from an info hash and resolve it to a playable URL. */
    suspend fun resolveHash(
        hash: String,
        name: String,
        season: Int? = null,
        episode: Int? = null
    ): StreamResolution = resolveMagnet(magnetFromHash(hash, name), season, episode)

    /** Add a magnet to TorBox and resolve it to a direct stream URL. */
    suspend fun resolveMagnet(
        magnet: String,
        season: Int? = null,
        episode: Int? = null
    ): StreamResolution {
        val token = settings.currentTorboxKey()
        if (token.isBlank()) {
            return StreamResolution.Failure("No TorBox API key set. Add it in Settings to enable playback.")
        }

        // Fast path (Debrify-style): ask TorBox to add the magnet ONLY if it's
        // already cached. A cached release comes back instantly with a ready
        // torrent id and its files are immediately listable, so playback starts
        // right away with no download-poll wait. If it isn't cached, TorBox
        // returns an error and we fall back to the normal add+poll flow below
        // (so explicitly-chosen uncached sources still download and play).
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
        if (instant?.success == true && instantId != null) {
            torrentId = instantId
        } else {
            // Not cached (or the fast add errored) — add it for real and wait.
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
        }

        val torrent = awaitFiles(torrentId)
            ?: return StreamResolution.Failure(
                "Source isn't cached yet and is still downloading. Try another source or play again shortly."
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
    private suspend fun awaitFiles(torrentId: Long): TorBoxTorrent? {
        repeat(MAX_POLLS) { attempt ->
            val torrent = runCatching { api.getTorrent(torrentId).data }.getOrNull()
            if (torrent != null && torrent.files.isNotEmpty() &&
                (torrent.downloadPresent || torrent.downloadFinished || torrent.cached || torrent.files.isNotEmpty())
            ) return torrent
            if (attempt < MAX_POLLS - 1) delay(POLL_INTERVAL_MS)
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
        private val VIDEO_EXTS = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".ts")
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce"
        )
    }
}
