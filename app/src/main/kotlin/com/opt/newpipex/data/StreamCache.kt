package com.opt.newpipex.data

import android.util.LruCache

/**
 * In-memory cache of resolved streams + search results.
 *
 * This is the "keep it warm" half of the extraction policy: once a video's
 * metadata and stream URL have been pulled, they are served from here for the
 * rest of the session instead of hammering the upstream site again.  VOD URLs
 * stay cached for [VOD_TTL_MS]; live playlists expire quickly because their
 * signed URLs rotate.
 */
object StreamCache {

    private const val MAX_ENTRIES = 64
    private const val VOD_TTL_MS = 5 * 60 * 60_000L   // 5 hours (signed URLs last ~6h)
    private const val LIVE_TTL_MS = 30_000L           // 30 seconds

    private data class Entry(val info: StreamInfo, val expiresAt: Long)

    private val cache = LruCache<String, Entry>(MAX_ENTRIES)

    fun get(videoId: String): StreamInfo? {
        val entry = cache.get(videoId) ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(videoId)
            return null
        }
        return entry.info
    }

    fun put(videoId: String, info: StreamInfo) {
        val ttl = if (info.isLive) LIVE_TTL_MS else VOD_TTL_MS
        cache.put(videoId, Entry(info, System.currentTimeMillis() + ttl))
    }

    fun evict(videoId: String) = cache.remove(videoId)

    fun clear() = cache.evictAll()
}
