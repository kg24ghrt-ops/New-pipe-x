package com.opt.newpipex.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Long-lived OkHttp client shared by every extraction path.
 *
 * Part of the "keep it warm" policy: we build the client exactly once per
 * process and never shut its connection pool down between lookups, so repeat
 * requests reuse already-established TCP+TLS sockets instead of paying a full
 * handshake each time.  Politeness (not pulling too often) is enforced one
 * layer above, in [ExtractionThrottle].
 */
object SharedHttp {

    private const val TAG = "SharedHttp"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.6478.134 Mobile Safari/537.36"

    /** One client for the whole app session; its pool stays warm. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // Generous keep-alive so idle sockets survive between lookups.
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    /** Simple GET returning the body as text, or null on any failure. */
    fun getString(url: String, accept: String? = null): String? {
        val request = OkRequest.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { accept?.let { header("Accept", it) } }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET $url -> HTTP ${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url failed: ${t.message}")
            null
        }
    }
}

/**
 * Bridges NewPipeExtractor's abstract [Downloader] onto our shared, warm
 * [OkHttpClient].  The extractor keeps using the same pooled connections for
 * its whole lifetime - nothing is closed after a lookup finishes.
 */
class OkHttpBridgeDownloader : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val builder = OkRequest.Builder().url(request.url())
        for ((name, values) in request.headers()) {
            for (value in values) builder.addHeader(name, value)
        }
        if (builder.build().header("User-Agent") == null) {
            builder.header("User-Agent", SharedHttp.USER_AGENT)
        }

        var body: okhttp3.RequestBody? = null
        request.dataToSend()?.let { data ->
            body = okhttp3.RequestBody.create(null, data)
        }
        builder.method(request.httpMethod(), body)

        SharedHttp.client.newCall(builder.build()).execute().use { okResponse ->
            if (okResponse.code == 429 || okResponse.code == 403) {
                throw ReCaptchaException("reCaptcha/challenge requested", okResponse.header("X-YouTube-Captcha"))
            }
            val responseBody = okResponse.body?.string() ?: ""
            val headers = LinkedHashMap<String, List<String>>()
            okResponse.headers.names().forEach { name ->
                headers[name] = okResponse.headers.values(name)
            }
            return Response(
                okResponse.code,
                okResponse.message,
                headers,
                responseBody,
                okResponse.request.url.toString(),
            )
        }
    }
}
