package com.example.player

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.data.AppPreferences
import com.example.data.ChannelLaunchPayload
import com.example.data.ChannelRepository
import com.example.data.StreamType
import com.example.data.TvChannel
import com.example.data.UrlSecurityValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class PlayerResizeMode(val label: String, val media3Mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): PlayerResizeMode = when (this) {
        FIT -> FILL
        FILL -> ZOOM
        ZOOM -> FIT
    }
}

data class TrackOption(
    val id: String,
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean,
    val isAutoOrOff: Boolean = false
)

data class NativePlayerUiState(
    val channels: List<TvChannel> = emptyList(),
    val currentChannel: TvChannel? = null,
    val apiSourceLabel: String = "",
    val isLoadingChannels: Boolean = true,
    val statusBanner: String? = null,
    val detectedStream: DetectedStream? = null,
    val isDrmActive: Boolean = false,
    val drmStatusLabel: String = "Clear (No DRM)",
    val drmKidBase64Url: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isLiveStream: Boolean = true,
    val isMuted: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val videoResolution: String = "Detecting…",
    val videoCodec: String = "—",
    val audioCodec: String = "—",
    val bitrateLabel: String = "—",
    val videoTracks: List<TrackOption> = emptyList(),
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val resizeMode: PlayerResizeMode = PlayerResizeMode.FIT,
    val isControlsLocked: Boolean = false,
    val playbackError: String? = null
)

@OptIn(UnstableApi::class)
class NativePlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChannelRepository()
    private val preferences = AppPreferences(application)
    private val streamDetector = StreamDetector()
    private val clearKeyDrmManager = ClearKeyDrmManager()

    private val trackSelector = DefaultTrackSelector(application)
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application)
        .setTrackSelector(trackSelector)
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .build()

    private val _uiState = MutableStateFlow(NativePlayerUiState())
    val uiState: StateFlow<NativePlayerUiState> = _uiState.asStateFlow()

    private var prepareJob: Job? = null
    private var positionTickerJob: Job? = null
    private var lastLoadedTracks: Tracks = Tracks.EMPTY

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val ready = playbackState == Player.STATE_READY
            _uiState.update {
                it.copy(
                    isBuffering = buffering,
                    statusBanner = if (ready) null else it.statusBanner,
                    playbackError = if (ready) null else it.playbackError,
                    isLiveStream = exoPlayer.isCurrentMediaItemLive || (it.currentChannel?.isLive == true)
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _uiState.update {
                    it.copy(videoResolution = "${videoSize.width}×${videoSize.height}")
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            lastLoadedTracks = tracks
            parseTracksAndTelemetry(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val detail = error.localizedMessage ?: error.errorCodeName
            _uiState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    statusBanner = null,
                    playbackError = "Playback failed (${error.errorCodeName}): $detail"
                )
            }
        }
    }

    init {
        exoPlayer.addListener(playerListener)
        exoPlayer.playWhenReady = true
        viewModelScope.launch {
            preferences.resizeModeFlow.collect { mode ->
                val mapped = PlayerResizeMode.entries.getOrElse(mode) { PlayerResizeMode.FIT }
                _uiState.update { it.copy(resizeMode = mapped) }
            }
        }
        startPositionTicker()
    }

    fun initializeFromLaunch(rawPayload: String?, explicitChannelId: String?, explicitApiUrl: String?) {
        val parsed = parseLaunchPayload(rawPayload, explicitChannelId, explicitApiUrl)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingChannels = true,
                    playbackError = null,
                    statusBanner = "Loading channel list from API…"
                )
            }

            val savedApiUrl = preferences.getChannelApiUrl()
            val effectiveApiUrl = parsed.apiUrl?.takeIf { it.isNotBlank() } ?: savedApiUrl
            val loadResult = repository.loadChannels(effectiveApiUrl)

            val mergedChannels = buildList {
                val selected = parsed.selectedChannel
                val alreadyExists = selected != null && loadResult.channels.any { it.id == selected.id }
                if (selected != null && !alreadyExists) {
                    add(selected)
                }
                addAll(loadResult.channels)
            }

            val targetChannel = parsed.selectedChannel
                ?: mergedChannels.firstOrNull { it.id == parsed.channelId }
                ?: mergedChannels.firstOrNull()

            _uiState.update {
                it.copy(
                    channels = mergedChannels,
                    apiSourceLabel = loadResult.sourceLabel,
                    isLoadingChannels = false
                )
            }

            if (targetChannel != null) {
                playChannel(targetChannel)
            } else {
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        statusBanner = null,
                        playbackError = "No playable channels were returned by the Channel API."
                    )
                }
            }
        }
    }

    fun playChannel(channel: TvChannel) {
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            if (!UrlSecurityValidator.isSafeNetworkUrl(channel.streamUrl)) {
                _uiState.update {
                    it.copy(
                        currentChannel = channel,
                        isBuffering = false,
                        statusBanner = null,
                        playbackError = "Blocked an unsafe or unsupported stream URL."
                    )
                }
                return@launch
            }

            preferences.recordRecentChannel(channel.id)

            _uiState.update {
                it.copy(
                    currentChannel = channel,
                    isBuffering = true,
                    playbackError = null,
                    statusBanner = "Detecting stream format (HLS / DASH / MP4)…",
                    detectedStream = null,
                    isDrmActive = false,
                    drmStatusLabel = "Checking DRM…",
                    drmKidBase64Url = null,
                    isLiveStream = channel.isLive,
                    videoResolution = "Detecting…",
                    videoCodec = "—",
                    audioCodec = "—",
                    bitrateLabel = "—",
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    subtitleTracks = emptyList()
                )
            }

            val safeHeaders = UrlSecurityValidator.sanitizeHeaders(channel.headers)

            // 1. Auto-detect HLS / DASH / MP4 stream type
            val detected = streamDetector.detect(
                streamUrl = channel.streamUrl,
                hint = channel.streamTypeHint,
                headers = safeHeaders
            )
            _uiState.update {
                it.copy(
                    detectedStream = detected,
                    statusBanner = if (channel.drm?.isClearKey == true) {
                        "Fetching ClearKey DRM keys & Base64URL encoding…"
                    } else {
                        "Starting ${detected.streamType.badgeCode} playback…"
                    }
                )
            }

            // 2. Resolve ClearKey DRM (fetch keys if remote URL + Base64URL encode into JWK)
            val resolvedDrm = if (channel.drm?.isClearKey == true) {
                clearKeyDrmManager.resolveDrmSession(channel.drm, safeHeaders)
            } else {
                null
            }

            _uiState.update {
                it.copy(
                    isDrmActive = resolvedDrm != null,
                    drmStatusLabel = if (resolvedDrm != null) {
                        "ClearKey Active • ${resolvedDrm.sourceDescription} (${resolvedDrm.keyCount} key)"
                    } else if (channel.drm?.isClearKey == true) {
                        "ClearKey Key Fetch Failed"
                    } else {
                        "Clear Stream (No DRM)"
                    },
                    drmKidBase64Url = resolvedDrm?.primaryKidBase64Url
                )
            }

            // 3. Build OkHttpDataSource + MediaSource matching detected stream type
            val dataSourceFactory = OkHttpDataSource.Factory(ChannelRepository.defaultHttpClient())
                .setUserAgent(safeHeaders["User-Agent"] ?: "StreamPulse-Android/1.0")
                .apply {
                    if (safeHeaders.isNotEmpty()) {
                        setDefaultRequestProperties(safeHeaders)
                    }
                }

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(channel.streamUrl))
            detected.mimeType?.let { mediaItemBuilder.setMimeType(it) }
            val mediaItem = mediaItemBuilder.build()

            val mediaSource: MediaSource = when (detected.streamType) {
                StreamType.HLS -> {
                    HlsMediaSource.Factory(dataSourceFactory).apply {
                        resolvedDrm?.let { drm -> setDrmSessionManagerProvider { drm.sessionManager } }
                    }.createMediaSource(mediaItem)
                }
                StreamType.DASH -> {
                    DashMediaSource.Factory(dataSourceFactory).apply {
                        resolvedDrm?.let { drm -> setDrmSessionManagerProvider { drm.sessionManager } }
                    }.createMediaSource(mediaItem)
                }
                StreamType.MP4 -> {
                    ProgressiveMediaSource.Factory(dataSourceFactory).apply {
                        resolvedDrm?.let { drm -> setDrmSessionManagerProvider { drm.sessionManager } }
                    }.createMediaSource(mediaItem)
                }
                StreamType.UNKNOWN -> {
                    DefaultMediaSourceFactory(dataSourceFactory).apply {
                        resolvedDrm?.let { drm -> setDrmSessionManagerProvider { drm.sessionManager } }
                    }.createMediaSource(mediaItem)
                }
            }

            // Reset any track overrides from previous channel
            trackSelector.parameters = trackSelector.buildUponParameters()
                .clearOverrides()
                .build()

            exoPlayer.stop()
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    fun retryCurrentChannel() {
        _uiState.value.currentChannel?.let { playChannel(it) }
    }

    fun nextChannel() {
        val list = _uiState.value.channels
        if (list.size <= 1) return
        val currentIdx = list.indexOfFirst { it.id == _uiState.value.currentChannel?.id }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % list.size
        playChannel(list[nextIdx])
    }

    fun previousChannel() {
        val list = _uiState.value.channels
        if (list.size <= 1) return
        val currentIdx = list.indexOfFirst { it.id == _uiState.value.currentChannel?.id }
        val prevIdx = if (currentIdx <= 0) list.lastIndex else currentIdx - 1
        playChannel(list[prevIdx])
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
        exoPlayer.seekTo(target)
        _uiState.update { it.copy(currentPositionMs = target) }
    }

    fun seekTo(positionMs: Long) {
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: return
        val clamped = positionMs.coerceIn(0L, duration)
        exoPlayer.seekTo(clamped)
        _uiState.update { it.copy(currentPositionMs = clamped) }
    }

    fun jumpToLiveEdge() {
        if (exoPlayer.isCurrentMediaItemLive) {
            exoPlayer.seekToDefaultPosition()
        }
    }

    fun toggleMute() {
        val nextMuted = !_uiState.value.isMuted
        exoPlayer.volume = if (nextMuted) 0f else 1f
        _uiState.update { it.copy(isMuted = nextMuted) }
    }

    fun toggleControlsLock() {
        _uiState.update { it.copy(isControlsLocked = !it.isControlsLocked) }
    }

    fun cycleResizeMode() {
        val next = _uiState.value.resizeMode.next()
        _uiState.update { it.copy(resizeMode = next) }
        viewModelScope.launch {
            preferences.setResizeMode(next.ordinal)
        }
    }

    fun selectVideoTrack(option: TrackOption) {
        val builder = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (!option.isAutoOrOff) {
            val group = lastLoadedTracks.groups.getOrNull(option.groupIndex)
            if (group != null) {
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            }
        }
        trackSelector.parameters = builder.build()
    }

    fun selectAudioTrack(option: TrackOption) {
        val builder = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (!option.isAutoOrOff) {
            val group = lastLoadedTracks.groups.getOrNull(option.groupIndex)
            if (group != null) {
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            }
        }
        trackSelector.parameters = builder.build()
    }

    fun selectSubtitleTrack(option: TrackOption) {
        val builder = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (option.isAutoOrOff) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val group = lastLoadedTracks.groups.getOrNull(option.groupIndex)
            if (group != null) {
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            }
        }
        trackSelector.parameters = builder.build()
    }

    private fun parseTracksAndTelemetry(tracks: Tracks) {
        val videoOpts = mutableListOf<TrackOption>()
        val audioOpts = mutableListOf<TrackOption>()
        val subOpts = mutableListOf<TrackOption>()

        val hasVideoOverride = trackSelector.parameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO }
        videoOpts.add(
            TrackOption(
                id = "video_auto",
                label = "Auto (Adaptive ABR)",
                groupIndex = -1,
                trackIndex = -1,
                isSelected = !hasVideoOverride,
                isAutoOrOff = true
            )
        )

        var selectedVideoCodec = _uiState.value.videoCodec
        var selectedAudioCodec = _uiState.value.audioCodec
        var selectedBitrate = _uiState.value.bitrateLabel
        var selectedRes = _uiState.value.videoResolution
        var anySubSelected = false

        tracks.groups.forEachIndexed { gIndex, group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (tIndex in 0 until group.length) {
                        if (!group.isTrackSupported(tIndex)) continue
                        val fmt = group.getTrackFormat(tIndex)
                        val selected = group.isTrackSelected(tIndex)
                        val heightLabel = if (fmt.height > 0) "${fmt.height}p" else "Video ${tIndex + 1}"
                        val mbps = if (fmt.bitrate > 0) String.format("%.1f Mbps", fmt.bitrate / 1_000_000f) else ""
                        val label = listOf(heightLabel, mbps).filter { it.isNotEmpty() }.joinToString(" • ")

                        videoOpts.add(
                            TrackOption(
                                id = "v_${gIndex}_$tIndex",
                                label = label,
                                groupIndex = gIndex,
                                trackIndex = tIndex,
                                isSelected = hasVideoOverride && selected
                            )
                        )
                        if (selected) {
                            if (fmt.width > 0 && fmt.height > 0) {
                                selectedRes = "${fmt.width}×${fmt.height}"
                            }
                            selectedVideoCodec = fmt.codecs ?: fmt.sampleMimeType ?: "H.264 / AVC"
                            if (fmt.bitrate > 0) {
                                selectedBitrate = String.format("%.2f Mbps", fmt.bitrate / 1_000_000f)
                            }
                        }
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (tIndex in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIndex)
                        val selected = group.isTrackSelected(tIndex)
                        val lang = fmt.language?.uppercase() ?: "DEFAULT"
                        val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                        val label = listOf(fmt.label ?: lang, ch).filter { it.isNotEmpty() }.joinToString(" • ")
                        audioOpts.add(
                            TrackOption(
                                id = "a_${gIndex}_$tIndex",
                                label = label,
                                groupIndex = gIndex,
                                trackIndex = tIndex,
                                isSelected = selected
                            )
                        )
                        if (selected) {
                            selectedAudioCodec = fmt.codecs ?: fmt.sampleMimeType ?: "AAC"
                        }
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (tIndex in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIndex)
                        val selected = group.isTrackSelected(tIndex)
                        if (selected) anySubSelected = true
                        val label = fmt.label ?: fmt.language?.uppercase() ?: "Caption ${tIndex + 1}"
                        subOpts.add(
                            TrackOption(
                                id = "s_${gIndex}_$tIndex",
                                label = label,
                                groupIndex = gIndex,
                                trackIndex = tIndex,
                                isSelected = selected
                            )
                        )
                    }
                }
            }
        }

        val finalSubOpts = listOf(
            TrackOption(
                id = "sub_off",
                label = "Off",
                groupIndex = -1,
                trackIndex = -1,
                isSelected = !anySubSelected,
                isAutoOrOff = true
            )
        ) + subOpts

        _uiState.update {
            it.copy(
                videoResolution = selectedRes,
                videoCodec = selectedVideoCodec,
                audioCodec = selectedAudioCodec,
                bitrateLabel = selectedBitrate,
                videoTracks = videoOpts.distinctBy { opt -> opt.label },
                audioTracks = audioOpts,
                subtitleTracks = finalSubOpts
            )
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = viewModelScope.launch {
            while (isActive) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.takeIf { it > 0 } ?: 0L
                val buf = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur,
                        bufferedPositionMs = buf
                    )
                }
                delay(500L)
            }
        }
    }

    private fun parseLaunchPayload(
        rawPayload: String?,
        explicitChannelId: String?,
        explicitApiUrl: String?
    ): ChannelLaunchPayload {
        val trimmed = rawPayload?.trim().orEmpty()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            if (obj != null) {
                val apiUrl = explicitApiUrl?.takeIf { it.isNotBlank() }
                    ?: obj.optString("apiUrl").takeIf { it.isNotBlank() }
                val channelObj = obj.optJSONObject("channel") ?: obj
                val parsedChannel = TvChannel.fromJsonObject(channelObj, fallbackIndex = 1)
                val chId = explicitChannelId?.takeIf { it.isNotBlank() }
                    ?: obj.optString("channelId").takeIf { it.isNotBlank() }
                    ?: obj.optString("id").takeIf { it.isNotBlank() }
                    ?: parsedChannel?.id
                return ChannelLaunchPayload(
                    channelId = chId,
                    selectedChannel = parsedChannel,
                    apiUrl = apiUrl
                )
            }
        }
        return ChannelLaunchPayload(
            channelId = explicitChannelId?.takeIf { it.isNotBlank() } ?: trimmed.takeIf { it.isNotBlank() },
            selectedChannel = null,
            apiUrl = explicitApiUrl
        )
    }

    override fun onCleared() {
        prepareJob?.cancel()
        positionTickerJob?.cancel()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        super.onCleared()
    }
}
