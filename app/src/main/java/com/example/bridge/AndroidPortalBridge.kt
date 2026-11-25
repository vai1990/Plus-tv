package com.example.bridge

import android.webkit.JavascriptInterface
import com.example.data.TvChannel
import com.example.data.UrlSecurityValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript <-> Native bridge exposed to the local EPG WebView as `window.AndroidPortal`.
 * Handles `AndroidPortal.launchNative(...)`, Channel API loading, and direct stream launching.
 */
class AndroidPortalBridge(
    private val onLaunchNative: (payload: String, apiUrl: String?) -> Unit,
    private val onRequestLoadApi: (apiUrl: String) -> Unit,
    private val onResetDefaultApi: () -> Unit,
    private val currentStateProvider: () -> PortalSnapshot
) {

    data class PortalSnapshot(
        val apiUrl: String,
        val sourceLabel: String,
        val channels: List<TvChannel>,
        val recentIds: List<String>,
        val warningMessage: String? = null
    ) {
        fun toJsonString(): String {
            return JSONObject().apply {
                put("apiUrl", apiUrl)
                put("sourceLabel", sourceLabel)
                if (!warningMessage.isNullOrBlank()) put("warningMessage", warningMessage)
                put("channels", JSONArray(TvChannel.listToJsonString(channels)))
                put("recentIds", JSONArray(recentIds))
            }.toString()
        }
    }

    @JavascriptInterface
    fun launchNative(channelPayload: String) {
        val safePayload = channelPayload.trim()
        if (safePayload.isEmpty()) return
        val currentApi = currentStateProvider().apiUrl.takeIf { it.isNotBlank() }
        onLaunchNative(safePayload, currentApi)
    }

    @JavascriptInterface
    fun launchNative(channelId: String, apiUrl: String) {
        val trimmedId = channelId.trim()
        if (trimmedId.isEmpty()) return
        val validatedApi = apiUrl.trim().takeIf { UrlSecurityValidator.isSafeNetworkUrl(it) }
        val payload = JSONObject().apply {
            put("channelId", trimmedId)
            if (validatedApi != null) put("apiUrl", validatedApi)
        }.toString()
        onLaunchNative(payload, validatedApi)
    }

    @JavascriptInterface
    fun getInitialStateJson(): String {
        return currentStateProvider().toJsonString()
    }

    @JavascriptInterface
    fun loadChannelApi(apiUrl: String) {
        val trimmed = apiUrl.trim()
        if (trimmed.isEmpty()) {
            onResetDefaultApi()
            return
        }
        onRequestLoadApi(trimmed)
    }

    @JavascriptInterface
    fun resetDefaultChannels() {
        onResetDefaultApi()
    }
}
