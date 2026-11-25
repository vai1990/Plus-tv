package com.example.player

import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import com.example.data.ClearKeyPair
import com.example.data.DrmConfig
import com.example.data.UrlSecurityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ResolvedClearKeyDrm(
    val sessionManager: DrmSessionManager,
    val keyCount: Int,
    val primaryKidBase64Url: String,
    val sourceDescription: String
)

/**
 * Handles ClearKey DRM key acquisition, Hex -> Base64URL (URL-safe, unpadded) encoding,
 * W3C EME JWK JSON generation, and Media3 DefaultDrmSessionManager construction.
 */
@OptIn(UnstableApi::class)
class ClearKeyDrmManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    suspend fun resolveDrmSession(
        drmConfig: DrmConfig?,
        headers: Map<String, String> = emptyMap()
    ): ResolvedClearKeyDrm? = withContext(Dispatchers.IO) {
        if (drmConfig == null || !drmConfig.isClearKey) return@withContext null

        val keyPairs = mutableListOf<ClearKeyPair>()
        var sourceDesc = "Inline ClearKey Hex"

        // 1. Fetch remote ClearKey keys if a keyUrl is configured
        val keyUrl = drmConfig.keyUrl
        if (!keyUrl.isNullOrBlank() && UrlSecurityValidator.isSafeNetworkUrl(keyUrl)) {
            val fetchedBody = fetchRemoteKeyPayload(keyUrl, headers)
            if (!fetchedBody.isNullOrBlank()) {
                val parsedRemote = parseKeyPayload(fetchedBody)
                if (parsedRemote.isNotEmpty()) {
                    keyPairs.addAll(parsedRemote)
                    sourceDesc = "Fetched & Base64URL Encoded from Key API"
                }
            }
        }

        // 2. Parse raw JWK JSON if supplied directly
        if (keyPairs.isEmpty() && !drmConfig.rawJwkJson.isNullOrBlank()) {
            val parsedJwk = parseKeyPayload(drmConfig.rawJwkJson)
            if (parsedJwk.isNotEmpty()) {
                keyPairs.addAll(parsedJwk)
                sourceDesc = "JWK ClearKey Payload"
            }
        }

        // 3. Use inline kid/key pairs
        if (keyPairs.isEmpty() && drmConfig.keys.isNotEmpty()) {
            keyPairs.addAll(drmConfig.keys)
            sourceDesc = "Hex -> Base64URL Encoded JWK"
        }

        if (keyPairs.isEmpty()) return@withContext null

        val jwkResult = buildClearKeyJwkJson(keyPairs)
        val callback = LocalMediaDrmCallback(jwkResult.jwkJson.toByteArray(Charsets.UTF_8))

        val sessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(callback)

        ResolvedClearKeyDrm(
            sessionManager = sessionManager,
            keyCount = jwkResult.encodedCount,
            primaryKidBase64Url = jwkResult.primaryKidBase64Url,
            sourceDescription = sourceDesc
        )
    }

    private fun fetchRemoteKeyPayload(url: String, headers: Map<String, String>): String? {
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", headers["User-Agent"] ?: "StreamPulse-Android/1.0")
                .get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim() else null
            }
        }.getOrNull()
    }

    /**
     * Parses ClearKey responses in any common IPTV format:
     * 1. W3C JWK JSON: { "keys": [{ "kty": "oct", "k": "...", "kid": "..." }] }
     * 2. Key-Value JSON map: { "<kidHex>": "<keyHex>" } or { "keys": { "<kidHex>": "<keyHex>" } }
     * 3. Flat JSON object: { "kid": "...", "key": "..." }
     * 4. Plain text: "<kidHex>:<keyHex>" (single or multi-line)
     */
    fun parseKeyPayload(rawPayload: String): List<ClearKeyPair> {
        val trimmed = rawPayload.trim()
        val results = mutableListOf<ClearKeyPair>()

        if (trimmed.startsWith("{")) {
            runCatching {
                val root = JSONObject(trimmed)
                val keysArr = root.optJSONArray("keys")
                if (keysArr != null) {
                    for (i in 0 until keysArr.length()) {
                        val entry = keysArr.optJSONObject(i) ?: continue
                        val kid = entry.optString("kid").trim()
                        val k = entry.optString("k").ifBlank { entry.optString("key") }.trim()
                        if (kid.isNotEmpty() && k.isNotEmpty()) {
                            results.add(ClearKeyPair(kid = kid, key = k))
                        }
                    }
                } else if (root.optJSONObject("keys") != null) {
                    val mapObj = root.getJSONObject("keys")
                    val iter = mapObj.keys()
                    while (iter.hasNext()) {
                        val kid = iter.next().trim()
                        val k = mapObj.optString(kid).trim()
                        if (kid.isNotEmpty() && k.isNotEmpty()) {
                            results.add(ClearKeyPair(kid = kid, key = k))
                        }
                    }
                } else if (root.has("kid") && (root.has("key") || root.has("k"))) {
                    val kid = root.optString("kid").trim()
                    val k = root.optString("key").ifBlank { root.optString("k") }.trim()
                    if (kid.isNotEmpty() && k.isNotEmpty()) {
                        results.add(ClearKeyPair(kid = kid, key = k))
                    }
                } else {
                    // Treat root object itself as { "<kid>": "<key>" } map
                    val iter = root.keys()
                    while (iter.hasNext()) {
                        val kid = iter.next().trim()
                        if (kid.equals("type", ignoreCase = true)) continue
                        val k = root.optString(kid).trim()
                        if (kid.length >= 16 && k.length >= 16) {
                            results.add(ClearKeyPair(kid = kid, key = k))
                        }
                    }
                }
            }
        } else {
            trimmed.lines().forEach { line ->
                val cleanLine = line.trim()
                if (cleanLine.contains(":")) {
                    val parts = cleanLine.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        results.add(ClearKeyPair(kid = parts[0].trim(), key = parts[1].trim()))
                    }
                }
            }
        }
        return results
    }

    data class JwkBuildResult(
        val jwkJson: String,
        val encodedCount: Int,
        val primaryKidBase64Url: String
    )

    fun buildClearKeyJwkJson(pairs: List<ClearKeyPair>): JwkBuildResult {
        val keysArray = JSONArray()
        var firstKid = ""

        pairs.forEach { pair ->
            val encodedKid = toBase64UrlNoPadding(pair.kid)
            val encodedKey = toBase64UrlNoPadding(pair.key)
            if (encodedKid.isNotEmpty() && encodedKey.isNotEmpty()) {
                if (firstKid.isEmpty()) firstKid = encodedKid
                keysArray.put(
                    JSONObject().apply {
                        put("kty", "oct")
                        put("k", encodedKey)
                        put("kid", encodedKid)
                    }
                )
            }
        }

        val root = JSONObject().apply {
            put("keys", keysArray)
            put("type", "temporary")
        }
        return JwkBuildResult(
            jwkJson = root.toString(),
            encodedCount = keysArray.length(),
            primaryKidBase64Url = firstKid
        )
    }

    /**
     * Converts a 32-char Hex string (or UUID-formatted hex) into URL-safe unpadded Base64URL
     * required by Android's ClearKey MediaDrm implementation. If already Base64/Base64URL,
     * normalizes it cleanly into URL-safe unpadded Base64URL.
     */
    fun toBase64UrlNoPadding(rawInput: String): String {
        val cleaned = rawInput.trim()
        if (cleaned.isEmpty()) return ""

        val hexCandidate = cleaned.replace("-", "")
        if (hexCandidate.length % 2 == 0 && hexCandidate.length >= 32 && hexCandidate.all { it in HEX_CHARS }) {
            val bytes = ByteArray(hexCandidate.length / 2)
            for (i in bytes.indices) {
                val hi = Character.digit(hexCandidate[i * 2], 16)
                val lo = Character.digit(hexCandidate[i * 2 + 1], 16)
                bytes[i] = ((hi shl 4) + lo).toByte()
            }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        // Already Base64 or Base64URL -> normalize to URL-safe unpadded form
        return cleaned
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    companion object {
        private val HEX_CHARS = "0123456789abcdefABCDEF".toSet()
    }
}
