package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.streamPulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streampulse_prefs"
)

class AppPreferences(private val context: Context) {

    private val keyChannelApiUrl = stringPreferencesKey("channel_api_url")
    private val keyRecentChannelIds = stringPreferencesKey("recent_channel_ids")
    private val keyResizeMode = intPreferencesKey("player_resize_mode")

    val channelApiUrlFlow: Flow<String> = context.streamPulseDataStore.data.map { prefs ->
        prefs[keyChannelApiUrl].orEmpty()
    }

    val recentChannelIdsFlow: Flow<List<String>> = context.streamPulseDataStore.data.map { prefs ->
        prefs[keyRecentChannelIds]
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    val resizeModeFlow: Flow<Int> = context.streamPulseDataStore.data.map { prefs ->
        prefs[keyResizeMode] ?: 0
    }

    suspend fun getChannelApiUrl(): String = channelApiUrlFlow.first()

    suspend fun setChannelApiUrl(url: String) {
        val cleaned = url.trim()
        context.streamPulseDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(keyChannelApiUrl)
            } else if (UrlSecurityValidator.isSafeNetworkUrl(cleaned)) {
                prefs[keyChannelApiUrl] = cleaned
            }
        }
    }

    suspend fun recordRecentChannel(channelId: String) {
        if (channelId.isBlank()) return
        context.streamPulseDataStore.edit { prefs ->
            val current = prefs[keyRecentChannelIds]
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != channelId }
            val updated = (listOf(channelId) + current).take(8)
            prefs[keyRecentChannelIds] = updated.joinToString(",")
        }
    }

    suspend fun getRecentChannelIds(): List<String> = recentChannelIdsFlow.first()

    suspend fun setResizeMode(mode: Int) {
        context.streamPulseDataStore.edit { prefs ->
            prefs[keyResizeMode] = mode
        }
    }
}
