package com.opt.new.pipe.x.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.opt.new.pipe.x.data.SharedHttp
import com.opt.new.pipe.x.data.StreamInfo

/**
 * Thin wrapper around Media3/ExoPlayer that knows how to feed a [StreamInfo]
 * (resolved by either extraction backend) into the player.
 *
 * Progressive MP4 URLs are played as-is; HLS/DASH manifests get the matching
 * media item type so ExoPlayer picks its HlsMediaSource / DashMediaSource.
 */
@OptIn(UnstableApi::class)
object VideoPlayerFactory {

    private const val TAG = "VideoPlayerFactory"

    fun create(context: Context): ExoPlayer =
        ExoPlayer.Builder(context.applicationContext)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { Log.d(TAG, "ExoPlayer instance created") }

    /** Build a [MediaItem] for an extracted stream, with proper MIME + headers. */
    fun mediaItemFor(info: StreamInfo): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(info.directUrl))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(info.title)
                    .setArtist(info.uploader)
                    .build()
            )

        when {
            info.mimeType.contains("mpegURL") || info.mimeType.contains("m3u8") ||
                info.directUrl.contains(".m3u8") ->
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)

            info.mimeType.contains("dash") ->
                builder.setMimeType(MimeTypes.APPLICATION_MPD)

            else -> builder.setMimeType(MimeTypes.APPLICATION_MP4)
        }

        // YouTube's CDN rejects requests without a browser-like UA.
        return builder.build()
    }

    /** HTTP data source factory carrying our shared user-agent header. */
    fun dataSourceFactory(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(
            context.applicationContext,
            DefaultHttpDataSource.Factory()
                .setUserAgent(SharedHttp.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000),
        )

    /** Convenience: prepare the player and start playback of [info]. */
    fun play(player: ExoPlayer, context: Context, info: StreamInfo) {
        player.setMediaItem(mediaItemFor(info))
        player.prepare()
        player.playWhenReady = true
        Log.i(TAG, "playing '${info.title}' (${info.mimeType}, live=${info.isLive})")
    }
}
