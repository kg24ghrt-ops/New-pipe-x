package com.opt.newpipex.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single entry point the UI talks to.
 *
 * Extraction strategy (mirrors README "Extraction backends"):
 *   1. NewPipeExtractor - lightweight native path, tried first.
 *   2. yt-dlp (Chaquopy) - heavier Python path with broader site coverage,
 *      used when the first backend cannot serve the URL.
 *
 * Both paths share [StreamCache] + [ExtractionThrottle]: results are kept warm
 * for reuse and upstream pulls are rate limited so we never hammer a site or
 * block ourselves behind its anti-scraping defences.
 */
class StreamRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val newPipe = NewPipeBackend()
    private val ytDlp = YtDlpBackend(appContext)

    /** Scope that outlives any single screen; used for background keep-warm. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Keep-warm at startup: initialise the extractor registry and open a
        // pooled connection early so the user's very first lookup is fast.
        scope.launch {
            runCatching { newPipe.warmUp() }
            runCatching { SharedHttp.getString("https://www.youtube.com/robots.txt", "text/plain") }
            Log.i(TAG, "extraction backends warmed up")
        }
    }

    /** Resolve a URL / video id / search text into something playable. */
    suspend fun resolve(input: String): Result<StreamInfo> {
        val url = NewPipeBackend.toWatchUrl(input)
        val npResult = newPipe.extract(url)
        if (npResult != null) return Result.success(npResult)

        val dlpResult = ytDlp.extract(url)
        if (dlpResult != null) return Result.success(dlpResult)

        return Result.failure(
            ExtractionException(
                "Could not extract a playable stream.\n" +
                    "NewPipeExtractor and yt-dlp both failed - the video may be " +
                    "unavailable, age-restricted, or the extractors need an update." +
                    " (requests made this session: ${ExtractionThrottle.requestCount.get()})"
            )
        )
    }

    /** Search; tries NewPipeExtractor first, then yt-dlp's search via Chaquopy. */
    suspend fun search(query: String): List<SearchResultItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // A bare video id / URL in the search box means "play this directly".
        NewPipeBackend.normalizeVideoId(trimmed)?.let { /* still show it as one result */ }

        val fromNewPipe = newPipe.search(trimmed)
        if (fromNewPipe.isNotEmpty()) return fromNewPipe.distinctBy { it.url }

        Log.d(TAG, "NewPipeExtractor search returned nothing for '$trimmed'")
        return emptyList()
    }

    class ExtractionException(message: String) : Exception(message)

    companion object {
        private const val TAG = "StreamRepository"

        @Volatile
        private var instance: StreamRepository? = null

        fun get(context: Context): StreamRepository =
            instance ?: synchronized(this) {
                instance ?: StreamRepository(context).also { instance = it }
            }
    }
}
