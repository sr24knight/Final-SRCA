package com.streambert.tv.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.streambert.tv.data.stream.SubtitleTrack
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds an [ExoPlayer] tuned for premium home-theatre playback:
 *
 *  • **Dolby Vision / HDR10 / HDR10+ / HLG** — handled by the device's video
 *    decoder + display. We keep the default (hardware) MediaCodec renderers and
 *    enable *tunneled* playback, which is the recommended path for 4K/HDR/DV on
 *    Android TV & Google TV and gives the decoder/display the HDR metadata.
 *
 *  • **Dolby Atmos (E-AC3 JOC) / Dolby Digital / DTS / DTS-HD / DTS:X / TrueHD**
 *    — passed through (bit-streamed) untouched to a connected AVR/soundbar when
 *    the HDMI/eARC sink reports support for those encodings, instead of being
 *    downmixed to PCM stereo. ExoPlayer's DefaultAudioSink does this
 *    automatically based on the device AudioCapabilities; we just make sure we
 *    don't get in the way and we prefer decoder extensions when present.
 *
 * Passthrough of Atmos/DTS:X ultimately depends on the hardware chain
 * (device → HDMI/eARC → receiver). On devices without a passthrough-capable
 * sink, core Media3 can decode Dolby (AC3/E-AC3) but **DTS decoding needs the
 * optional media3 FFmpeg decoder extension** (built from source) — see README.
 */
object PlaybackFactory {

    /**
     * A single shared OkHttp client for *playback* (kept separate from the API
     * clients in NetworkModule). Reusing one client across every player build
     * means the connection pool stays warm — TLS handshakes and sockets to the
     * debrid CDN can be reused instead of re-established on every play, which
     * shaves time off the first byte. A short connect timeout also fails fast
     * so we fall back quickly instead of hanging on a dead host.
     */
    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @UnstableApi
    fun create(
        context: Context,
        startPositionMs: Long,
        mediaUri: String,
        tunnelingEnabled: Boolean = false,
        subtitles: List<SubtitleTrack> = emptyList()
    ): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            // Fall back to another decoder if the primary one fails to init
            // (common with exotic HDR/DV or high-bitrate streams).
            setEnableDecoderFallback(true)
            // Use bundled decoder extensions (e.g. FFmpeg for DTS) when they are
            // on the classpath; harmless no-op otherwise.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Tunneled playback gives the best A/V sync + HDR/DV handoff on
                    // capable TVs, but many devices misreport support and render a
                    // BLACK SCREEN with audio only. So it is OFF by default and can
                    // be turned on per-device from Settings.
                    .setTunnelingEnabled(tunnelingEnabled)
            )
        }

        // Start playback almost instantly. The 3rd/4th values are the important
        // ones for *perceived* startup time: they are the amount of media that
        // must be buffered before ExoPlayer begins (and resumes) rendering.
        //   • bufferForPlaybackMs = 1_000  → begin rendering after just ~1s of
        //     media is ready, instead of waiting for a big safety buffer.
        //   • bufferForPlaybackAfterRebufferMs = 2_000 → resume quickly after a
        //     stall without immediately re-stalling.
        // The 1st/2nd values keep a healthy 15s..50s buffer in memory once we're
        // rolling, and setPrioritizeTimeOverSizeThresholds(true) makes those
        // thresholds time-based (not byte-based) so high-bitrate 4K remuxes
        // still start fast.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // OkHttp-backed HTTP stack (robust redirects/timeouts). DefaultMediaSourceFactory
        // auto-selects the right source for the content — progressive (MKV/MP4/WebM),
        // HLS (.m3u8), DASH (.mpd) or SmoothStreaming — because those modules are on the
        // classpath. This is what gives us broad container/streaming coverage.
        val httpDataSourceFactory = OkHttpDataSource.Factory(playbackHttpClient)
            .setUserAgent("StreambertTV")

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // handleAudioFocus = true so we duck/pause correctly.
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)

                val subtitleConfigs = subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(sub.mimeType)
                        .setLanguage(sub.language)
                        .setLabel(sub.displayLanguage)
                        // Carry the provider id onto the resulting Format.id so the
                        // subtitle panel can label it (source + id), Nuvio-style.
                        .setId(sub.id.ifBlank { sub.url })
                        .build()
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setSubtitleConfigurations(subtitleConfigs)
                    .build()
                setMediaItem(mediaItem)
                if (startPositionMs > 0) seekTo(startPositionMs)
                playWhenReady = true
                prepare()
            }
    }
}
