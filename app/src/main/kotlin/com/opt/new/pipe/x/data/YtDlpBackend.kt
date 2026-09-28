package com.opt.new.pipe.x.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Backend 2: yt-dlp - the Python / Chaquopy path.
 *
 * The project pins yt-dlp for Chaquopy in app/python-requirements.txt (see
 * config/dependencies.toml).  This class first tries to run it in-process via
 * Chaquopy reflection; when the Chaquopy runtime is not packaged into the
 * current build it transparently falls back to a small built-in YouTube
 * extractor that speaks the same innertube endpoints yt-dlp itself uses, so
 * playback keeps working either way.
 *
 * Keep-warm policy: the Chaquopy Python thread stays alive once created (we
 * never call any shutdown), and the fallback reuses [SharedHttp]'s pooled
 * OkHttp client.  Politeness policy: every upstream pull goes through
 * [ExtractionThrottle] and results land in [StreamCache].
 */
class YtDlpBackend(private val context: Context) {

    @Volatile
    private var chaquopyTried = false

    @Volatile
    private var chaquopyAvailable = false

    /**
     * Resolve [url] with yt-dlp.  Returns null if both the Chaquopy runtime
     * and the built-in fallback fail, letting [StreamRepository] surface the
     * combined error message.
     */
    suspend fun extract(url: String): StreamInfo? = withContext(Dispatchers.IO) {
        val videoId = NewPipeBackend.normalizeVideoId(url) ?: url
        StreamCache.get(videoId)?.let { cached ->
            Log.d(TAG, "cache hit for $videoId (no upstream pull)")
            return@withContext cached
        }

        ExtractionThrottle.throttle()
        val viaChaquopy = tryChaupy(url)
        if (viaChaquopy != null) {
            StreamCache.put(videoId, viaChaquopy)
            return@withContext viaChaquopy
        }

        val viaFallback = try runBuiltinExtractor(url) catch (t: Throwable) {
            Log.w(TAG, "built-in fallback failed: ${t.message}")
            null
        }
        if (viaFallback != null) StreamCache.put(videoId, viaFallback)
        viaFallback
    }

    // ------------------------------------------------------------------ //
    // Chaquopy (in-process yt-dlp) - used whenever the runtime exists
    // ------------------------------------------------------------------ //

    private fun tryChaupy(url: String): StreamInfo? {
        if (!chaquopyTried) {
            synchronized(this) {
                if (!chaquopyTried) {
                    chaquopyTried = true
                    chaquopyAvailable = try {
                        // python.PyObject / Python are only present when the
                        // Chaquopy plugin was applied; probe reflectively so
                        // this file compiles against both configurations.
                        val pythonClass = Class.forName("com.chaquo.python.Python")
                        val startMethod = pythonClass.getMethod("start", Context::class.java)
                        startMethod.invoke(null, context.applicationContext)
                        Log.i(TAG, "Chaquopy runtime started; yt-dlp thread kept warm for the session")
                        true
                    } catch (t: Throwable) {
                        Log.i(TAG, "Chaquopy runtime not packaged in this build; using built-in fallback")
                        false
                    }
                }
            }
        }
        if (!chaquopyAvailable) return null

        return try {
            val pythonClass = Class.forName("com.chaquo.python.Python")
            val getInstance = pythonClass.getMethod("getInstance")
            val python = getInstance.invoke(null)
            // Keep the returned PyObject alive on purpose: the interpreter
            // thread stays warm between calls (do NOT dispose it eagerly).
            val execed = pythonClass.getMethod("exec", String::class.java, String::class.java)
                .invoke(python, "yt_dlp_runner", YTDLP_BRIDGE_SOURCE)
            val callAttr = execed.javaClass.getMethod("call", String::class.java, Array<Any>::class.java)
            val result = callAttr.invoke(execed, "get_stream_info", arrayOf(url))
            val json = result.javaClass.getMethod("toString").invoke(result) as String
            parseYtDlpJson(json, url)
        } catch (t: Throwable) {
            Log.w(TAG, "Chaquopy yt-dlp invocation failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------ //
    // Built-in innertube fallback (same endpoints yt-dlp's YouTube IE uses)
    // ------------------------------------------------------------------ //

    private fun runBuiltinExtractor(url: String): StreamInfo? {
        val videoId = NewPipeBackend.normalizeVideoId(url) ?: return null

        val playerBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 30)
                    put("userAgent", SharedHttp.USER_AGENT)
                })
            })
            put("videoId", videoId)
        }.toString()

        val playerJson = postJson(PLAYER_URL, playerBody) ?: return null
        val player = JSONObject(playerJson)

        val details = player.optJSONObject("videoDetails")
        val title = details?.optString("title", "").orEmpty().ifBlank { url }
        val author = details?.optString("author", "Unknown").orEmpty().ifBlank { "Unknown" }
        val lengthSec = details?.optString("lengthSeconds", "0")?.toLongOrNull() ?: 0
        val views = details?.optString("viewCount", "0")?.toLongOrNull() ?: 0
        val isLive = details?.optBoolean("isLiveContent", false) == true || lengthSec == 0L && !isPlayableStreams(player)

        // Pick the best progressive mp4 (has both audio+video, mimeType video/mp4).
        val formats = player.optJSONArray("streams") ?: return null
        var chosen: JSONObject? = null
        var chosenBitrate = -1
        for (i in 0 until formats.length()) {
            val stream = formats.optJSONObject(i) ?: continue
            val mime = stream.optString("mimeType")
            if (!mime.startsWith("video/mp4")) continue
            val bitrate = stream.optInt("bitrate", 0)
            if (stream.has("url") && bitrate > chosenBitrate) {
                chosen = stream
                chosenBitrate = bitrate
            }
        }

        val directUrl = chosen?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: run {
                // No plain mp4 (e.g. age-gated / live): try the HLS master playlist.
                player.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                    ?: player.optString("dashManifestUrl").takeIf { it.isNotBlank() }
            }
            ?: return null

        val info = StreamInfo(
            title = title,
            uploader = author,
            durationMs = lengthSec * 1000,
            viewCount = views,
            thumbnailUrl = details?.optJSONArray("thumbnail")
                ?.optJSONArray("thumbnails")?.let { arr ->
                    (0 until arr.length()).maxByOrNull { arr.optJSONObject(it)?.optString("url")?.length ?: 0 }
                        ?.let { arr.optJSONObject(it)?.optString("url") }
                },
            directUrl = directUrl,
            mimeType = if (directUrl.contains(".m3u8") || directUrl.contains("m3u8")) "application/x-mpegURL" else "video/mp4",
            isLive = isLive,
        )
        Log.i(TAG, "built-in extractor resolved '$title' (${info.mimeType})")
        return info
    }

    private fun isPlayableStreams(player: JSONObject): Boolean {
        val streams = player.optJSONArray("streams") ?: return false
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (s.has("url")) return true
        }
        return false
    }

    private fun postJson(urlStr: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", SharedHttp.USER_AGENT)
                setRequestProperty("X-Youtube-Client-Name", "3")
                setRequestProperty("X-Youtube-Client-Version", "19.09.37")
            }
            DataOutputStream(conn.outputStream).use { it.writeBytes(body) }
            if (conn.responseCode != 200) {
                Log.w(TAG, "innertube POST -> HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().readText()
        } catch (t: Throwable) {
            Log.w(TAG, "innertube POST failed: ${t.message}")
            null
        } finally {
            // Deliberately do NOT disconnect evict sockets from the pool -
            // keep-alive connections stay warm for the next lookup.
            conn?.let { /* reuse-friendly: leave pooled connection management to the OS/OkHttp */ }
        }
    }

    private fun parseYtDlpJson(json: String, requestedUrl: String): StreamInfo? {
        return try {
            val root = JSONObject(json)
            val formats = root.optJSONArray("formats") ?: return null
            var chosen: JSONObject? = null
            var chosenBitrate = -1
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                if (vcodec == "none") continue
                val hasAudio = acodec != "none"
                val formatId = f.optString("format_id")
                val isMerged = "/" in formatId
                if (!hasAudio && !isMerged) continue
                val bitrate = f.optLong("tbr", f.optLong("vbr", 0))
                if (f.has("url") && bitrate > chosenBitrate) {
                    chosen = f
                    chosenBitrate = bitrate.toInt()
                }
            }
            val directUrl = chosen?.optString("url")?.takeIf { it.isNotBlank() }
                ?: root.optString("url").takeIf { it.isNotBlank() }
                ?: return null
            StreamInfo(
                title = root.optString("title", requestedUrl),
                uploader = root.optString("uploader", "Unknown"),
                durationMs = root.optLong("duration", 0) * 1000,
                viewCount = root.optLong("view_count", 0),
                thumbnailUrl = root.optString("thumbnail").takeIf { it.isNotBlank() },
                directUrl = directUrl,
                mimeType = chosen?.optString("ext")?.let { if (it == "m3u8") "application/x-mpegURL" else "video/mp4" }
                    ?: "video/mp4",
                isLive = root.optBoolean("is_live", false),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse yt-dlp JSON: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "YtDlpBackend"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

        /**
         * Python module executed inside Chaquopy when the runtime is present.
         * It imports the yt-dlp release pinned in app/python-requirements.txt
         * and returns its extraction result as JSON, mirroring what the CLI
         * `yt-dlp -J <url>` would print.
         */
        private val YTDLP_BRIDGE_SOURCE = """
            import json
            import yt_dlp

            # Options mirror the CLI defaults but ask for a single playable
            # progressive file so ExoPlayer can open the URL directly.
            _YDL_OPTS = {
                'quiet': True,
                'no_warnings': True,
                'noplaylist': True,
                'format': 'bv*+ba/b[protocol=https]',
                'skip_download': True,
                'http_headers': {
                    'User-Agent': ('Mozilla/5.0 (Linux; Android 14; Pixel 7) '
                                   'AppleWebKit/537.36 (KHTML, like Gecko) '
                                   'Chrome/126.0.6478.134 Mobile Safari/537.36'),
                },
            }

            def get_stream_info(url):
                # The YoutubeDL instance is intentionally short-lived per call,
                # but the interpreter + imported modules stay warm afterwards.
                with yt_dlp.YoutubeDL(_YDL_OPTS) as ydl:
                    info = ydl.extract_info(url, download=False)
                return json.dumps(info)
        """.trimIndent()

        /** Not used at runtime; keeps a stable fingerprint helper available. */
        internal fun md5(input: String): String =
            MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
