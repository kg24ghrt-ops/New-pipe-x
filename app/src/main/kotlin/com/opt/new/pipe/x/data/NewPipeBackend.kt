package com.opt.new.pipe.x.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Backend 1: NewPipeExtractor - the lightweight / native Android path.
 *
 * Resolves YouTube (plus SoundCloud, media.ccc.de, Peertube, Bandcamp) fully
 * in-process; no external binaries are needed.
 *
 * Keep-warm policy: [NewPipe] is initialised exactly once per process and the
 * shared OkHttp connection pool ([SharedHttp]) is never torn down after a
 * lookup - repeat lookups reuse warm sockets and cached results.
 * Politeness policy: every upstream call passes through [ExtractionThrottle],
 * and identical searches are de-duplicated while one is already running.
 */
class NewPipeBackend {

    private val initMutex = Mutex()
    @Volatile
    private var initialised = false

    /** Register the services once and keep the registry warm for the session. */
    suspend fun warmUp() {
        if (initialised) return
        initMutex.withLock {
            if (initialised) return
            withContext(Dispatchers.IO) {
                // Touch ServiceList so all service instances are constructed now
                // rather than on the first user-visible lookup.
                ServiceList.YouTube
                ServiceList.SoundCloud
                ServiceList.MediaCCC
                NewPipe.init(OkHttpBridgeDownloader())
                initialised = true
                Log.i(TAG, "NewPipeExtractor initialised (${NewPipe.getServices().size} services); kept warm for the session")
            }
        }
    }

    /**
     * Resolve a video page URL into a playable [StreamInfo].
     *
     * Returns null when this backend cannot serve the request (unsupported
     * site, bot check, changed internals) so the caller can fall back to the
     * yt-dlp backend instead of failing outright.
     */
    suspend fun extract(url: String): StreamInfo? {
        val videoId = normalizeVideoId(url) ?: url
        StreamCache.get(videoId)?.let { cached ->
            Log.d(TAG, "cache hit for $videoId (no upstream pull)")
            return cached
        }

        return try {
            warmUp()
            ExtractionThrottle.throttle()
            val np = withContext(Dispatchers.IO) { NpStreamInfo.getInfo(url) }
            val info = np.toStreamInfo(url)
            StreamCache.put(videoId, info)
            info
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipeExtractor failed for $url (${t.javaClass.simpleName}: ${t.message}); falling back to yt-dlp")
            null
        }
    }

    /** Search the primary service (YouTube).  Empty list on failure. */
    suspend fun search(query: String): List<SearchResultItem> {
        return try {
            warmUp()
            ExtractionThrottle.throttle()
            withContext(Dispatchers.IO) {
                val handler = ServiceList.YouTube.getSearchQHFactory().fromQuery(query)
                val searchInfo = SearchInfo.getInfo(ServiceList.YouTube, handler)
                searchInfo.relatedItems.mapNotNull { item ->
                    if (item is StreamInfoItem) {
                        SearchResultItem(
                            url = item.url,
                            title = item.name,
                            uploader = runCatching { item.uploaderName }.getOrDefault(""),
                            durationMs = when (item.streamType) {
                                StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> -1L
                                else -> item.duration * 1000
                            },
                            viewCount = runCatching { item.viewCount }.getOrDefault(0),
                            thumbnailUrl = item.thumbnails.maxByOrNull { t -> t.url.length }?.url,
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipeExtractor search '$query' failed (${t.javaClass.simpleName}: ${t.message})")
            emptyList()
        }
    }

    private fun NpStreamInfo.toStreamInfo(requestedUrl: String): StreamInfo {
        val live = streamType == StreamType.LIVE_STREAM ||
            streamType == StreamType.AUDIO_LIVE_STREAM ||
            streamType == StreamType.POST_LIVE_STREAM ||
            streamType == StreamType.POST_LIVE_AUDIO_STREAM

        // Prefer the best progressive MP4 (single file, directly seekable in
        // ExoPlayer).  Fall back to HLS then DASH manifests, which Media3 also
        // plays natively.
        val progressive = videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                    it.format.id == MediaFormat.MPEG_4.id
                }.thenByDescending {
                    it.bitrate.takeIf { b -> b > 0 } ?: resolutionRank(it.resolution)
                }
            )
            .firstOrNull()
        val hls = hlsUrl?.takeIf { it.isNotBlank() }
        val dash = dashMpdUrl?.takeIf { it.isNotBlank() }

        val (directUrl, mime) = when {
            progressive != null ->
                progressive.content to (MediaFormat.getMimeById(progressive.format.id) ?: "video/mp4")
            hls != null -> hls to "application/x-mpegURL"
            dash != null -> dash to "application/dash+xml"
            else -> throw ExtractionException("NewPipeExtractor returned no playable streams")
        }

        val thumb = thumbnails.maxByOrNull { it.url.length }?.url

        return StreamInfo(
            title = name.ifBlank { requestedUrl },
            uploader = runCatching { uploaderName }.getOrDefault("Unknown").ifBlank { "Unknown" },
            durationMs = if (duration > 0) duration * 1000 else 0,
            viewCount = runCatching { viewCount }.getOrDefault(0),
            thumbnailUrl = thumb,
            directUrl = directUrl,
            mimeType = mime,
            isLive = live,
        )
    }

    companion object {
        private const val TAG = "NewPipeBackend"

        /** Map bare video ids and every common YouTube URL shape to a watch URL. */
        fun normalizeVideoId(url: String): String? {
            val trimmed = url.trim()
            Regex("""^[A-Za-z0-9_-]{11}$""").find(trimmed)?.value?.let { return it }
            Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""").find(trimmed)
                ?.groupValues?.getOrNull(1)?.let { return it }
            return null
        }

        fun toWatchUrl(input: String): String {
            val id = normalizeVideoId(input)
            return if (id != null) "https://www.youtube.com/watch?v=$id" else input.trim()
        }

        private fun resolutionRank(resolution: String?): Int {
            val digits = resolution?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            return digits
        }
    }
}

/** One row of a search result list. */
data class SearchResultItem(
    val url: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val viewCount: Long,
    val thumbnailUrl: String?,
) {
    val durationText: String
        get() = when {
            durationMs < 0 -> "LIVE"
            else -> {
                val totalSec = durationMs / 1000
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
            }
        }

    val viewCountText: String
        get() = when {
            viewCount >= 1_000_000 -> "%.1fM views".format(viewCount / 1e6)
            viewCount >= 1_000 -> "%.1fK views".format(viewCount / 1e3)
            viewCount > 0 -> "$viewCount views"
            else -> ""
        }
}
