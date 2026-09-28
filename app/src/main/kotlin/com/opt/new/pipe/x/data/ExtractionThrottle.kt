package com.opt.new.pipe.x.data

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared rate-limiting / keep-warm policy for both extraction backends.
 *
 * Two requirements pull in opposite directions and this object balances them:
 *
 *  - "Keep it warm all the time, don't kill it immediately after pulling the
 *     necessary data": the backends reuse one long-lived OkHttp client whose
 *     connection pool keeps TCP+TLS sockets alive between lookups, and every
 *     successful lookup is cached (see [StreamCache]) so repeat plays never
 *     touch the network again.
 *
 *  - "Don't pull too many times ... so you don't block up": every upstream
 *     metadata request must pass through [throttle], which enforces a minimum
 *     interval between requests and caps bursts with a sliding-window quota.
 */
object ExtractionThrottle {

    /** Minimum delay between two upstream lookups. */
    private const val MIN_INTERVAL_MS = 2_500L

    /** Hard cap on lookups inside the sliding window; protects against bursts. */
    private const val MAX_PER_WINDOW = 6
    private const val WINDOW_MS = 60_000L

    private val lock = Any()
    private var lastRequestAt = 0L
    private val windowTimestamps = ArrayDeque<Long>()

    /** Monotonic counter of upstream requests actually performed (for debug). */
    val requestCount = AtomicLong(0)

    /**
     * Suspends until the shared limiter allows another upstream request.
     * Callers must invoke this immediately before touching the network.
     */
    suspend fun throttle() {
        val waitMs = synchronized(lock) {
            val now = System.currentTimeMillis()
            while (windowTimestamps.isNotEmpty() && now - windowTimestamps.first() > WINDOW_MS) {
                windowTimestamps.removeFirst()
            }
            val minIntervalWait = MIN_INTERVAL_MS - (now - lastRequestAt)
            val windowWait = if (windowTimestamps.size >= MAX_PER_WINDOW) {
                WINDOW_MS - (now - windowTimestamps.first())
            } else 0L
            val wait = maxOf(minIntervalWait, windowWait, 0L)
            if (wait == 0L) {
                lastRequestAt = now
                windowTimestamps.addLast(now)
            }
            wait
        }
        if (waitMs > 0) {
            Log.d(TAG, "rate limit: waiting ${waitMs}ms before next upstream lookup")
            delay(waitMs)
            synchronized(lock) {
                val now = System.currentTimeMillis()
                lastRequestAt = now
                windowTimestamps.addLast(now)
            }
        }
        requestCount.incrementAndGet()
    }

    private const val TAG = "ExtractionThrottle"
}
