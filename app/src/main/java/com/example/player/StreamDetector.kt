package com.example.player

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.example.data.StreamType
import com.example.data.UrlSecurityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DetectedStream(
    val streamType: StreamType,
    val mimeType: String?,
    val detectionMethod: String
)

/**
 * Automatically detects whether a stream URL is HLS (.m3u8), MPEG-DASH (.mpd), or Progressive MP4
 * using URL/query signature analysis first, falling back to an HTTP HEAD Content-Type probe when
 * extensionless IPTV gateway URLs are provided.
 */
class StreamDetector(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    suspend fun detect(
        streamUrl: String,
        hint: StreamType = StreamType.UNKNOWN,
        headers: Map<String, String> = emptyMap()
    ): DetectedStream = withContext(Dispatchers.IO) {
        detectByUrlPattern(streamUrl)?.let { return@withContext it }

        if (hint != StreamType.UNKNOWN) {
            return@withContext DetectedStream(
                streamType = hint,
                mimeType = mimeTypeFor(hint),
                detectionMethod = "Channel Metadata Hint"
            )
        }

        if (UrlSecurityValidator.isSafeNetworkUrl(streamUrl)) {
            probeContentType(streamUrl, headers)?.let { return@withContext it }
        }

        DetectedStream(
            streamType = StreamType.HLS,
            mimeType = null,
            detectionMethod = "Adaptive Extractor Fallback"
        )
    }

    fun detectByUrlPattern(streamUrl: String): DetectedStream? {
        val lower = streamUrl.lowercase(Locale.US)
        val path = runCatching { Uri.parse(lower).path.orEmpty() }.getOrDefault(lower)
        val query = runCatching { Uri.parse(lower).query.orEmpty() }.getOrDefault("")

        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8/") ||
                path.endsWith(".m3u") || query.contains("format=m3u8") ||
                query.contains("type=hls") || lower.contains("/hls/") -> {
                DetectedStream(
                    streamType = StreamType.HLS,
                    mimeType = MimeTypes.APPLICATION_M3U8,
                    detectionMethod = "URL Signature (.m3u8 / HLS)"
                )
            }
            path.endsWith(".mpd") || path.contains(".mpd/") ||
                query.contains("format=mpd") || query.contains("type=dash") ||
                lower.contains("/dash/") -> {
                DetectedStream(
                    streamType = StreamType.DASH,
                    mimeType = MimeTypes.APPLICATION_MPD,
                    detectionMethod = "URL Signature (.mpd / DASH)"
                )
            }
            path.endsWith(".mp4") || path.endsWith(".m4v") ||
                path.endsWith(".webm") || path.endsWith(".mkv") ||
                query.contains("format=mp4") -> {
                DetectedStream(
                    streamType = StreamType.MP4,
                    mimeType = MimeTypes.VIDEO_MP4,
                    detectionMethod = "URL Signature (.mp4 Progressive)"
                )
            }
            else -> null
        }
    }

    private fun probeContentType(url: String, headers: Map<String, String>): DetectedStream? {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", headers["User-Agent"] ?: "StreamPulse-Android/1.0")
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.US)
                when {
                    contentType.contains("mpegurl") || contentType.contains("m3u8") -> {
                        DetectedStream(
                            streamType = StreamType.HLS,
                            mimeType = MimeTypes.APPLICATION_M3U8,
                            detectionMethod = "HTTP Content-Type ($contentType)"
                        )
                    }
                    contentType.contains("dash+xml") || contentType.contains("mpd") -> {
                        DetectedStream(
                            streamType = StreamType.DASH,
                            mimeType = MimeTypes.APPLICATION_MPD,
                            detectionMethod = "HTTP Content-Type ($contentType)"
                        )
                    }
                    contentType.contains("video/mp4") || contentType.contains("video/webm") ||
                        contentType.contains("video/x-matroska") -> {
                        DetectedStream(
                            streamType = StreamType.MP4,
                            mimeType = MimeTypes.VIDEO_MP4,
                            detectionMethod = "HTTP Content-Type ($contentType)"
                        )
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun mimeTypeFor(type: StreamType): String? = when (type) {
        StreamType.HLS -> MimeTypes.APPLICATION_M3U8
        StreamType.DASH -> MimeTypes.APPLICATION_MPD
        StreamType.MP4 -> MimeTypes.VIDEO_MP4
        StreamType.UNKNOWN -> null
    }
}
