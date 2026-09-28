package com.opt.new.pipe.x.data

/**
 * A playable stream resolved by one of the extraction backends.
 *
 * @property directUrl a URL the player can open directly (progressive MP4 or
 *                     an HLS playlist for live streams).
 * @property isLive    true for live broadcasts; [durationMs] is then unknown
 *                     and no caching of the URL applies (signed URLs expire).
 */
data class StreamInfo(
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val viewCount: Long,
    val thumbnailUrl: String?,
    val directUrl: String,
    val mimeType: String,
    val isLive: Boolean = false,
) {
    val durationText: String
        get() {
            if (isLive) return "LIVE"
            val totalSec = durationMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

    val viewCountText: String
        get() = when {
            viewCount >= 1_000_000_000 -> "%.1fB".format(viewCount / 1e9)
            viewCount >= 1_000_000 -> "%.1fM".format(viewCount / 1e6)
            viewCount >= 1_000 -> "%.1fK".format(viewCount / 1e3)
            else -> viewCount.toString()
        }
}
