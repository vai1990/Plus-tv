package com.example.data

import org.json.JSONArray
import org.json.JSONObject

enum class StreamType(val displayLabel: String, val badgeCode: String) {
    HLS("HTTP Live Streaming", "HLS"),
    DASH("MPEG-DASH", "DASH"),
    MP4("Progressive MP4", "MP4"),
    UNKNOWN("Auto Stream", "AUTO")
}

enum class DrmScheme(val displayLabel: String) {
    NONE("Clear (No DRM)"),
    CLEARKEY("ClearKey (CENC)")
}

data class ClearKeyPair(
    val kid: String,
    val key: String
)

data class DrmConfig(
    val scheme: DrmScheme = DrmScheme.NONE,
    val keyUrl: String? = null,
    val keys: List<ClearKeyPair> = emptyList(),
    val rawJwkJson: String? = null
) {
    val isClearKey: Boolean
        get() = scheme == DrmScheme.CLEARKEY && (!keyUrl.isNullOrBlank() || keys.isNotEmpty() || !rawJwkJson.isNullOrBlank())
}

data class EpgProgram(
    val title: String,
    val startTime: String,
    val endTime: String,
    val progress: Float,
    val nextTitle: String
)

data class TvChannel(
    val id: String,
    val number: Int,
    val name: String,
    val category: String,
    val logoUrl: String? = null,
    val accentHex: String = "#00E5FF",
    val streamUrl: String,
    val streamTypeHint: StreamType = StreamType.UNKNOWN,
    val isLive: Boolean = true,
    val qualityLabel: String = "1080p HD",
    val drm: DrmConfig? = null,
    val headers: Map<String, String> = emptyMap(),
    val epg: EpgProgram = EpgProgram(
        title = "Live Broadcast",
        startTime = "LIVE",
        endTime = "24/7",
        progress = 0.65f,
        nextTitle = "Up Next: Continuous Feed"
    )
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("number", number)
            put("name", name)
            put("category", category)
            if (logoUrl != null) put("logoUrl", logoUrl)
            put("accentHex", accentHex)
            put("streamUrl", streamUrl)
            put("streamType", streamTypeHint.badgeCode)
            put("isLive", isLive)
            put("qualityLabel", qualityLabel)
            if (drm != null && drm.isClearKey) {
                put("drm", JSONObject().apply {
                    put("scheme", "clearkey")
                    if (!drm.keyUrl.isNullOrBlank()) put("keyUrl", drm.keyUrl)
                    if (!drm.rawJwkJson.isNullOrBlank()) put("rawJwkJson", drm.rawJwkJson)
                    if (drm.keys.isNotEmpty()) {
                        val arr = JSONArray()
                        drm.keys.forEach { pair ->
                            arr.put(JSONObject().apply {
                                put("kid", pair.kid)
                                put("key", pair.key)
                            })
                        }
                        put("keys", arr)
                    }
                })
            }
            if (headers.isNotEmpty()) {
                val hdr = JSONObject()
                headers.forEach { (k, v) -> hdr.put(k, v) }
                put("headers", hdr)
            }
            put("epg", JSONObject().apply {
                put("title", epg.title)
                put("startTime", epg.startTime)
                put("endTime", epg.endTime)
                put("progress", epg.progress.toDouble())
                put("nextTitle", epg.nextTitle)
            })
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject, fallbackIndex: Int = 1): TvChannel? {
            val streamUrl = obj.optString("streamUrl")
                .ifBlank { obj.optString("url") }
                .ifBlank { obj.optString("stream_url") }
                .ifBlank { obj.optString("manifestUrl") }
                .trim()
            if (!UrlSecurityValidator.isSafeNetworkUrl(streamUrl)) return null

            val id = obj.optString("id")
                .ifBlank { obj.optString("channelId") }
                .ifBlank { "ch_$fallbackIndex" }
            val name = obj.optString("name")
                .ifBlank { obj.optString("title") }
                .ifBlank { obj.optString("channelName") }
                .ifBlank { "Channel $fallbackIndex" }
            val category = obj.optString("category")
                .ifBlank { obj.optString("group") }
                .ifBlank { obj.optString("genre") }
                .ifBlank { "Live TV" }
            val number = obj.optInt("number", fallbackIndex)
            val logoUrl = obj.optString("logoUrl")
                .ifBlank { obj.optString("logo") }
                .ifBlank { obj.optString("icon") }
                .takeIf { UrlSecurityValidator.isSafeNetworkUrl(it) }
            val accentHex = obj.optString("accentHex").ifBlank { "#00E5FF" }
            val isLive = if (obj.has("isLive")) obj.optBoolean("isLive", true) else true
            val qualityLabel = obj.optString("qualityLabel")
                .ifBlank { obj.optString("quality") }
                .ifBlank { "HD" }

            val hintRaw = obj.optString("streamType")
                .ifBlank { obj.optString("format") }
                .uppercase()
            val streamTypeHint = when {
                hintRaw.contains("HLS") || hintRaw.contains("M3U8") -> StreamType.HLS
                hintRaw.contains("DASH") || hintRaw.contains("MPD") -> StreamType.DASH
                hintRaw.contains("MP4") -> StreamType.MP4
                else -> StreamType.UNKNOWN
            }

            val drmConfig = parseDrmConfig(obj)
            val headers = parseHeaders(obj)

            val epgObj = obj.optJSONObject("epg")
            val epg = if (epgObj != null) {
                EpgProgram(
                    title = epgObj.optString("title").ifBlank { "$name Live" },
                    startTime = epgObj.optString("startTime").ifBlank { "LIVE" },
                    endTime = epgObj.optString("endTime").ifBlank { "24/7" },
                    progress = epgObj.optDouble("progress", 0.5).toFloat().coerceIn(0f, 1f),
                    nextTitle = epgObj.optString("nextTitle").ifBlank { "Continuous Broadcast" }
                )
            } else {
                EpgProgram(
                    title = obj.optString("nowPlaying").ifBlank { "$name Live Feed" },
                    startTime = "LIVE",
                    endTime = "24/7",
                    progress = 0.55f,
                    nextTitle = obj.optString("upNext").ifBlank { "Next Program Scheduled" }
                )
            }

            return TvChannel(
                id = id,
                number = number,
                name = name,
                category = category,
                logoUrl = logoUrl,
                accentHex = accentHex,
                streamUrl = streamUrl,
                streamTypeHint = streamTypeHint,
                isLive = isLive,
                qualityLabel = qualityLabel,
                drm = drmConfig,
                headers = headers,
                epg = epg
            )
        }

        private fun parseDrmConfig(obj: JSONObject): DrmConfig? {
            val drmObj = obj.optJSONObject("drm")
            val drmTypeStr = (drmObj?.optString("scheme")
                ?: drmObj?.optString("type")
                ?: obj.optString("drmType")
                ?: obj.optString("drm_scheme")).lowercase()

            val keyUrl = (drmObj?.optString("keyUrl")
                ?: drmObj?.optString("licenseUrl")
                ?: obj.optString("clearKeyUrl")
                ?: obj.optString("drmLicenseUrl")
                ?: obj.optString("drm_license_url"))
                .takeIf { UrlSecurityValidator.isSafeNetworkUrl(it) }

            val rawJwk = drmObj?.optString("rawJwkJson")?.takeIf { it.isNotBlank() }
            val pairs = mutableListOf<ClearKeyPair>()

            // Parse array of {kid, key}
            val keysArr = drmObj?.optJSONArray("keys") ?: obj.optJSONArray("clearKeys")
            if (keysArr != null) {
                for (i in 0 until keysArr.length()) {
                    val item = keysArr.optJSONObject(i) ?: continue
                    val kid = item.optString("kid").trim()
                    val key = item.optString("key").ifBlank { item.optString("k") }.trim()
                    if (kid.isNotEmpty() && key.isNotEmpty()) {
                        pairs.add(ClearKeyPair(kid = kid, key = key))
                    }
                }
            }

            // Parse map object { "<kid>": "<key>" }
            val keysMapObj = drmObj?.optJSONObject("clearKeys") ?: obj.optJSONObject("clearKeys")
            if (keysMapObj != null) {
                val it = keysMapObj.keys()
                while (it.hasNext()) {
                    val kid = it.next().trim()
                    val key = keysMapObj.optString(kid).trim()
                    if (kid.isNotEmpty() && key.isNotEmpty()) {
                        pairs.add(ClearKeyPair(kid = kid, key = key))
                    }
                }
            }

            // Parse inline flat kid/key or "kid:key" string
            val flatKid = (drmObj?.optString("kid") ?: obj.optString("clearKeyKid") ?: obj.optString("kid")).trim()
            val flatKey = (drmObj?.optString("key") ?: obj.optString("clearKeyValue") ?: obj.optString("key")).trim()
            if (flatKid.isNotEmpty() && flatKey.isNotEmpty()) {
                pairs.add(ClearKeyPair(kid = flatKid, key = flatKey))
            }

            val inlinePair = (drmObj?.optString("clearKey") ?: obj.optString("clearKey")).trim()
            if (inlinePair.contains(":")) {
                val parts = inlinePair.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    pairs.add(ClearKeyPair(kid = parts[0].trim(), key = parts[1].trim()))
                }
            }

            val isClearKey = drmTypeStr.contains("clearkey") ||
                drmTypeStr.contains("clear_key") ||
                keyUrl != null ||
                pairs.isNotEmpty() ||
                rawJwk != null

            return if (isClearKey) {
                DrmConfig(
                    scheme = DrmScheme.CLEARKEY,
                    keyUrl = keyUrl,
                    keys = pairs.distinctBy { it.kid },
                    rawJwkJson = rawJwk
                )
            } else {
                null
            }
        }

        private fun parseHeaders(obj: JSONObject): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            val headersObj = obj.optJSONObject("headers")
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = headersObj.optString(k)
                    if (v.isNotBlank()) map[k] = v
                }
            }
            val ua = obj.optString("userAgent").ifBlank { obj.optString("user_agent") }
            if (ua.isNotBlank()) map["User-Agent"] = ua
            val referer = obj.optString("referer")
            if (referer.isNotBlank()) map["Referer"] = referer
            return UrlSecurityValidator.sanitizeHeaders(map)
        }

        fun listToJsonString(channels: List<TvChannel>): String {
            val arr = JSONArray()
            channels.forEach { arr.put(it.toJsonObject()) }
            return arr.toString()
        }
    }
}

data class ChannelLaunchPayload(
    val channelId: String?,
    val selectedChannel: TvChannel?,
    val apiUrl: String?
)
