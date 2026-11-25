package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChannelLoadResult(
    val channels: List<TvChannel>,
    val sourceLabel: String,
    val fromRemoteApi: Boolean,
    val warningMessage: String? = null
)

class ChannelRepository(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun loadChannels(apiUrl: String?): ChannelLoadResult = withContext(Dispatchers.IO) {
        val trimmedUrl = apiUrl?.trim().orEmpty()
        if (trimmedUrl.isNotEmpty() && UrlSecurityValidator.isSafeNetworkUrl(trimmedUrl)) {
            val remoteResult = runCatching { fetchFromRemoteApi(trimmedUrl) }
            val remoteChannels = remoteResult.getOrNull().orEmpty()
            if (remoteChannels.isNotEmpty()) {
                return@withContext ChannelLoadResult(
                    channels = remoteChannels,
                    sourceLabel = trimmedUrl,
                    fromRemoteApi = true
                )
            }
            val reason = remoteResult.exceptionOrNull()?.message ?: "Empty channel playlist"
            return@withContext ChannelLoadResult(
                channels = builtInChannels(),
                sourceLabel = "Built-in Broadcast Feed",
                fromRemoteApi = false,
                warningMessage = "API load fallback ($reason)"
            )
        }

        ChannelLoadResult(
            channels = builtInChannels(),
            sourceLabel = "Built-in Broadcast Feed",
            fromRemoteApi = false
        )
    }

    private fun fetchFromRemoteApi(url: String): List<TvChannel> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
            .header("User-Agent", "StreamPulse-Android/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty().trim()
            if (body.isEmpty()) return emptyList()

            return if (body.startsWith("{") || body.startsWith("[")) {
                parseJsonChannelFeed(body)
            } else if (body.contains("#EXTM3U") || body.contains("#EXTINF")) {
                parseM3uPlaylist(body)
            } else {
                emptyList()
            }
        }
    }

    fun parseJsonChannelFeed(rawJson: String): List<TvChannel> {
        val trimmed = rawJson.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("channels")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("streams")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }

        val parsed = mutableListOf<TvChannel>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            TvChannel.fromJsonObject(obj, fallbackIndex = i + 1)?.let(parsed::add)
        }
        return parsed
    }

    fun parseM3uPlaylist(content: String): List<TvChannel> {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val channels = mutableListOf<TvChannel>()

        var pendingName: String? = null
        var pendingId: String? = null
        var pendingCategory = "Live TV"
        var pendingLogo: String? = null
        var pendingDrmScheme: DrmScheme = DrmScheme.NONE
        var pendingLicenseKeyRaw: String? = null
        val pendingHeaders = LinkedHashMap<String, String>()

        fun resetPending() {
            pendingName = null
            pendingId = null
            pendingCategory = "Live TV"
            pendingLogo = null
            pendingDrmScheme = DrmScheme.NONE
            pendingLicenseKeyRaw = null
            pendingHeaders.clear()
        }

        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingId = extractM3uAttribute(line, "tvg-id")
                    pendingLogo = extractM3uAttribute(line, "tvg-logo")
                        ?.takeIf { UrlSecurityValidator.isSafeNetworkUrl(it) }
                    pendingCategory = extractM3uAttribute(line, "group-title")
                        ?.takeIf { it.isNotBlank() } ?: "Live TV"
                    val commaIdx = line.lastIndexOf(',')
                    val titleFromComma = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
                    pendingName = titleFromComma.ifBlank {
                        extractM3uAttribute(line, "tvg-name") ?: "Channel ${channels.size + 1}"
                    }
                }
                line.startsWith("#KODIPROP:inputstream.adaptive.license_type=", ignoreCase = true) -> {
                    val value = line.substringAfter("=").trim().lowercase()
                    if (value.contains("clearkey")) {
                        pendingDrmScheme = DrmScheme.CLEARKEY
                    }
                }
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=", ignoreCase = true) -> {
                    pendingLicenseKeyRaw = line.substringAfter("=").trim()
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                    pendingHeaders["User-Agent"] = line.substringAfter("=").trim()
                }
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                    pendingHeaders["Referer"] = line.substringAfter("=").trim()
                }
                !line.startsWith("#") -> {
                    val streamUrl = line.trim()
                    if (UrlSecurityValidator.isSafeNetworkUrl(streamUrl)) {
                        val number = channels.size + 1
                        val drm = buildDrmFromKodiProp(pendingDrmScheme, pendingLicenseKeyRaw)
                        val name = pendingName ?: "Channel $number"
                        channels.add(
                            TvChannel(
                                id = pendingId?.takeIf { it.isNotBlank() } ?: "m3u_$number",
                                number = number,
                                name = name,
                                category = pendingCategory,
                                logoUrl = pendingLogo,
                                accentHex = ACCENT_PALETTE[(number - 1) % ACCENT_PALETTE.size],
                                streamUrl = streamUrl,
                                streamTypeHint = StreamType.UNKNOWN,
                                isLive = true,
                                qualityLabel = if (drm?.isClearKey == true) "HD • DRM" else "HD Live",
                                drm = drm,
                                headers = UrlSecurityValidator.sanitizeHeaders(pendingHeaders),
                                epg = EpgProgram(
                                    title = "$name Live Broadcast",
                                    startTime = "LIVE",
                                    endTime = "24/7",
                                    progress = 0.5f,
                                    nextTitle = "Continuous Stream"
                                )
                            )
                        )
                    }
                    resetPending()
                }
            }
        }
        return channels
    }

    private fun buildDrmFromKodiProp(scheme: DrmScheme, rawLicense: String?): DrmConfig? {
        if (scheme != DrmScheme.CLEARKEY && rawLicense.isNullOrBlank()) return null
        val license = rawLicense?.trim().orEmpty()
        return when {
            UrlSecurityValidator.isSafeNetworkUrl(license) -> DrmConfig(
                scheme = DrmScheme.CLEARKEY,
                keyUrl = license
            )
            license.startsWith("{") -> DrmConfig(
                scheme = DrmScheme.CLEARKEY,
                rawJwkJson = license
            )
            license.contains(":") -> {
                val parts = license.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    DrmConfig(
                        scheme = DrmScheme.CLEARKEY,
                        keys = listOf(ClearKeyPair(kid = parts[0].trim(), key = parts[1].trim()))
                    )
                } else null
            }
            else -> null
        }
    }

    private fun extractM3uAttribute(line: String, attr: String): String? {
        val regex = Regex("""$attr="([^"]*)"""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim()
    }

    companion object {
        private val ACCENT_PALETTE = listOf(
            "#00E5FF", "#FF3B5C", "#F59E0B", "#10B981", "#818CF8", "#EC4899"
        )

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        /**
         * Curated real, public, playable HLS, MPEG-DASH, ClearKey DRM DASH, and MP4 streams
         * so every feature (stream auto-detection, ClearKey Base64URL DRM decryption, PiP,
         * adaptive tracks, and gestures) works immediately out-of-the-box.
         */
        fun builtInChannels(): List<TvChannel> = listOf(
            TvChannel(
                id = "sp-hls-01",
                number = 101,
                name = "Pulse Live Cinema",
                category = "Live TV",
                accentHex = "#00E5FF",
                streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                streamTypeHint = StreamType.HLS,
                isLive = true,
                qualityLabel = "1080p HLS",
                epg = EpgProgram(
                    title = "Big Buck Bunny — Master Broadcast",
                    startTime = "20:00",
                    endTime = "21:30",
                    progress = 0.62f,
                    nextTitle = "Up Next: Animation Anthology HD"
                )
            ),
            TvChannel(
                id = "sp-drm-02",
                number = 102,
                name = "Axinom ClearKey DRM",
                category = "DRM Test",
                accentHex = "#F59E0B",
                streamUrl = "https://media.axprod.net/TestVectors/v7-MultiDRM-SingleKey/Manifest_1080p_ClearKey.mpd",
                streamTypeHint = StreamType.DASH,
                isLive = true,
                qualityLabel = "1080p ClearKey",
                drm = DrmConfig(
                    scheme = DrmScheme.CLEARKEY,
                    keys = listOf(
                        ClearKeyPair(
                            kid = "9eb4050de44b4802932e27d75083e266",
                            key = "166634c675823c235a4a9446fad52e4d"
                        )
                    )
                ),
                epg = EpgProgram(
                    title = "Encrypted CENC DASH • ClearKey Live",
                    startTime = "19:45",
                    endTime = "21:00",
                    progress = 0.74f,
                    nextTitle = "Up Next: Multi-Bitrate DRM Showcase"
                )
            ),
            TvChannel(
                id = "sp-dash-03",
                number = 103,
                name = "Akamai DASH HD",
                category = "Cinema",
                accentHex = "#10B981",
                streamUrl = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
                streamTypeHint = StreamType.DASH,
                isLive = true,
                qualityLabel = "1080p DASH",
                epg = EpgProgram(
                    title = "Adaptive MPEG-DASH 30fps Feed",
                    startTime = "20:15",
                    endTime = "21:15",
                    progress = 0.41f,
                    nextTitle = "Up Next: Studio Feature Presentation"
                )
            ),
            TvChannel(
                id = "sp-hls-04",
                number = 104,
                name = "Tears of Steel 4K",
                category = "Cinema",
                accentHex = "#FF3B5C",
                streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
                streamTypeHint = StreamType.HLS,
                isLive = true,
                qualityLabel = "FHD HLS",
                epg = EpgProgram(
                    title = "Sci-Fi Feature: Tears of Steel",
                    startTime = "20:00",
                    endTime = "22:00",
                    progress = 0.35f,
                    nextTitle = "Up Next: Behind the VFX"
                )
            ),
            TvChannel(
                id = "sp-hls-05",
                number = 105,
                name = "Sintel Prime",
                category = "Sports",
                accentHex = "#818CF8",
                streamUrl = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8",
                streamTypeHint = StreamType.HLS,
                isLive = true,
                qualityLabel = "1080p Multi-Audio",
                epg = EpgProgram(
                    title = "Multi-Track Broadcast Showcase",
                    startTime = "19:30",
                    endTime = "21:00",
                    progress = 0.80f,
                    nextTitle = "Up Next: Highlights & Post-Show"
                )
            ),
            TvChannel(
                id = "sp-dash-06",
                number = 106,
                name = "Envivio News 24",
                category = "News",
                accentHex = "#06B6D4",
                streamUrl = "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd",
                streamTypeHint = StreamType.DASH,
                isLive = true,
                qualityLabel = "720p DASH",
                epg = EpgProgram(
                    title = "Global Tech & Broadcast Desk",
                    startTime = "20:00",
                    endTime = "20:45",
                    progress = 0.55f,
                    nextTitle = "Up Next: Evening World Briefing"
                )
            ),
            TvChannel(
                id = "sp-mp4-07",
                number = 107,
                name = "VOD Direct MP4",
                category = "Cinema",
                accentHex = "#EC4899",
                streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                streamTypeHint = StreamType.MP4,
                isLive = false,
                qualityLabel = "720p MP4",
                epg = EpgProgram(
                    title = "On-Demand Progressive MP4 Stream",
                    startTime = "VOD",
                    endTime = "12:14",
                    progress = 0.22f,
                    nextTitle = "Scrub & Gesture Seek Enabled"
                )
            ),
            TvChannel(
                id = "sp-mp4-08",
                number = 108,
                name = "Elephants Dream VOD",
                category = "Live TV",
                accentHex = "#38BDF8",
                streamUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
                streamTypeHint = StreamType.MP4,
                isLive = false,
                qualityLabel = "HD MP4",
                epg = EpgProgram(
                    title = "Open Movie Archive — Direct MP4",
                    startTime = "VOD",
                    endTime = "10:53",
                    progress = 0.15f,
                    nextTitle = "Up Next: Director Commentary"
                )
            )
        )
    }
}
