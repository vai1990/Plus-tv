package com.example.data

import android.net.Uri
import java.util.Locale

/**
 * Validates external stream, playlist API, and ClearKey license URLs before any network request
 * or ExoPlayer media load, preventing dangerous scheme injection (file://, javascript:, content://)
 * and cloud metadata endpoint access.
 */
object UrlSecurityValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val BLOCKED_HOSTS = setOf(
        "169.254.169.254",
        "metadata.google.internal",
        "metadata.goog"
    )

    fun isSafeNetworkUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val trimmed = rawUrl.trim()
        if (trimmed.length > 4096) return false
        if (trimmed.any { it == '\r' || it == '\n' }) return false

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme !in ALLOWED_SCHEMES) return false

        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host.isBlank() || host in BLOCKED_HOSTS) return false
        return true
    }

    fun sanitizeHeaders(rawHeaders: Map<String, String>): Map<String, String> {
        val cleaned = LinkedHashMap<String, String>()
        for ((k, v) in rawHeaders) {
            val key = k.trim()
            val value = v.trim()
            if (key.isEmpty() || key.length > 128 || value.length > 2048) continue
            if (key.any { it == '\r' || it == '\n' || it == ':' }) continue
            if (value.any { it == '\r' || it == '\n' }) continue
            cleaned[key] = value
        }
        return cleaned
    }
}
