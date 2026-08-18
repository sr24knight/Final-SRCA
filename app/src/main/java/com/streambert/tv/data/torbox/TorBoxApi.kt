package com.streambert.tv.data.torbox

import okhttp3.MultipartBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * TorBox main API (host: https://api.torbox.app, version path /v1/api).
 *
 * Streaming flow:
 *   1. createTorrent(magnet)           -> torrent_id (instant if cached)
 *   2. getTorrent(id)                  -> file list (pick the episode/movie)
 *   3. requestDownloadLink(id, fileId) -> direct CDN URL for ExoPlayer
 */
interface TorBoxApi {

    /**
     * Adds a magnet to the account. If the release is already cached, TorBox
     * returns immediately with a ready torrent_id. Sent as multipart/form-data,
     * which is what the TorBox endpoint expects.
     */
    @Multipart
    @POST("v1/api/torrents/createtorrent")
    suspend fun createTorrent(
        @Part magnet: MultipartBody.Part,
        @Part seed: MultipartBody.Part,
        @Part allowZip: MultipartBody.Part,
        // When true, TorBox only adds the magnet if it is ALREADY cached and
        // returns immediately (no download wait). If it isn't cached it fails
        // fast with an error instead of queuing a download — which lets us skip
        // the poll-and-wait loop entirely for the common instant-play case.
        @Part addOnlyIfCached: MultipartBody.Part
    ): TorBoxEnvelope<CreateTorrentData>

    /** Single torrent with its files. */
    @GET("v1/api/torrents/mylist")
    suspend fun getTorrent(
        @Query("id") id: Long,
        @Query("bypass_cache") bypassCache: Boolean = true
    ): TorBoxEnvelope<TorBoxTorrent>
    @GET("v1/api/torrents/mylist")
    suspend fun myList(
        @Query("bypass_cache") bypassCache: Boolean = false
    ): TorBoxEnvelope<List<TorBoxTorrent>>

    /**
     * Instant availability check: given comma-separated info hashes, returns the
     * subset that are already cached on TorBox (i.e. play instantly).
     */
    @GET("v1/api/torrents/checkcached")
    suspend fun checkCached(
        @Query("hash") hash: String,
        @Query("format") format: String = "list",
        @Query("list_files") listFiles: Boolean = false
    ): TorBoxEnvelope<List<CachedHash>>

    /**
     * Requests a temporary direct download/stream URL. The URL is returned as
     * the bare `data` string in the envelope.
     */
    @GET("v1/api/torrents/requestdl")
    suspend fun requestDownloadLink(
        @Query("token") token: String,
        @Query("torrent_id") torrentId: Long,
        @Query("file_id") fileId: Long,
        @Query("redirect") redirect: Boolean = false
    ): TorBoxEnvelope<String>
}
