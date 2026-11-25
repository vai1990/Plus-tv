package com.example.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.TvChannel
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.DrmAmber
import com.example.ui.theme.DrmAmberContainer
import com.example.ui.theme.LiveCrimson
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PulseCyan
import com.example.ui.theme.PulseCyanContainer
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHigh
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PlayerControlsHud(
    state: NativePlayerUiState,
    visible: Boolean,
    isPipSupported: Boolean,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenChannelDrawer: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onJumpToLive: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleOrientation: () -> Unit,
    onRetry: () -> Unit
) {
    // Locked controls floating pill
    if (state.isControlsLocked) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(20.dp)
            ) {
                Surface(
                    onClick = onToggleLock,
                    shape = RoundedCornerShape(50),
                    color = Color(0xCC0B0E14),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PulseCyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .testTag("unlock_controls_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.action_unlock),
                            tint = PulseCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.label_controls_locked),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
        return
    }

    // Center buffering / stream detection indicator
    if (state.isBuffering && state.playbackError == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xC00B0E14))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = PulseCyan,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp)
                )
                Text(
                    text = state.statusBanner ?: stringResource(R.string.label_detecting_stream),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
            }
        }
    }

    // Playback error overlay
    if (state.playbackError != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC0B0E14)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, LiveCrimson.copy(alpha = 0.5f)),
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(0.85f)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.error_playback_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = state.playbackError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = PulseCyan, contentColor = ObsidianBg),
                            modifier = Modifier.testTag("retry_stream_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_retry))
                        }
                        Button(
                            onClick = onOpenChannelDrawer,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary),
                            modifier = Modifier.testTag("error_open_channels_button")
                        ) {
                            Text(stringResource(R.string.action_channels))
                        }
                    }
                }
            }
        }
        return
    }

    // Main HUD Overlay
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xCC0B0E14),
                        0.28f to Color(0x22000000),
                        0.72f to Color(0x22000000),
                        1f to Color(0xDE0B0E14)
                    )
                )
                .safeDrawingPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = TextPrimary
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val channel = state.currentChannel
                        Text(
                            text = if (channel != null) "CH ${channel.number} • ${channel.name}" else stringResource(R.string.player_title_default),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.isLiveStream) {
                            HudBadge(text = "● LIVE", bg = LiveCrimson, fg = Color.White)
                        } else {
                            HudBadge(text = "VOD", bg = SurfaceHigh, fg = TextPrimary)
                        }
                        state.detectedStream?.let { det ->
                            HudBadge(
                                text = det.streamType.badgeCode,
                                bg = PulseCyanContainer,
                                fg = PulseCyan
                            )
                        }
                        if (state.isDrmActive) {
                            HudBadge(
                                text = "CLEARKEY DRM",
                                bg = DrmAmberContainer,
                                fg = DrmAmber
                            )
                        }
                        if (state.videoResolution != "Detecting…") {
                            HudBadge(
                                text = state.videoResolution,
                                bg = Color(0x66FFFFFF),
                                fg = Color.White
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("stream_info_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.action_stream_info),
                        tint = TextPrimary
                    )
                }

                IconButton(
                    onClick = onOpenTracks,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("tracks_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.action_tracks),
                        tint = TextPrimary
                    )
                }

                IconButton(
                    onClick = onOpenChannelDrawer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("channels_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.action_channels),
                        tint = TextPrimary
                    )
                }

                if (isPipSupported) {
                    IconButton(
                        onClick = onEnterPip,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = stringResource(R.string.action_pip),
                            tint = PulseCyan
                        )
                    }
                }
            }

            // Center Playback Transport Controls
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                IconButton(
                    onClick = onPreviousChannel,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x770B0E14))
                        .testTag("prev_channel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.action_prev_channel),
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = onRewind10,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x770B0E14))
                        .testTag("rewind_10_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.action_seek_back),
                        tint = TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(PulseCyan)
                        .testTag("play_pause_button")
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                        tint = ObsidianBg,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = onForward10,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x770B0E14))
                        .testTag("forward_10_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = stringResource(R.string.action_seek_forward),
                        tint = TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = onNextChannel,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x770B0E14))
                        .testTag("next_channel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.action_next_channel),
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Bottom Scrub Bar & Utility Row
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (state.durationMs > 0L) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = formatPlaybackTime(state.currentPositionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                        Slider(
                            value = state.currentPositionMs.toFloat().coerceIn(0f, state.durationMs.toFloat()),
                            onValueChange = { onSeekTo(it.toLong()) },
                            valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = PulseCyan,
                                activeTrackColor = PulseCyan,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_seek_slider")
                        )
                        Text(
                            text = formatPlaybackTime(state.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.currentChannel?.epg?.title.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Swipe L: Brightness • Swipe R: Volume • Double Tap: ±10s",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isLiveStream) {
                            Surface(
                                onClick = onJumpToLive,
                                shape = RoundedCornerShape(50),
                                color = LiveCrimson.copy(alpha = 0.22f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, LiveCrimson),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .testTag("jump_live_button")
                            ) {
                                Text(
                                    text = "LIVE EDGE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mute_button")
                        ) {
                            Icon(
                                imageVector = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.action_mute),
                                tint = TextPrimary
                            )
                        }

                        IconButton(
                            onClick = onCycleAspectRatio,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("aspect_ratio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = stringResource(R.string.action_aspect_ratio),
                                tint = TextPrimary
                            )
                        }

                        IconButton(
                            onClick = onToggleLock,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lock_controls_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = stringResource(R.string.action_lock),
                                tint = TextPrimary
                            )
                        }

                        IconButton(
                            onClick = onToggleOrientation,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("rotate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = stringResource(R.string.action_rotate),
                                tint = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudBadge(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ChannelSwitcherSheet(
    channels: List<TvChannel>,
    currentChannelId: String?,
    sourceLabel: String,
    onSelectChannel: (TvChannel) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            color = SurfaceElevated,
            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.78f)
                .safeDrawingPadding()
                .clickable(enabled = false, onClick = {})
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_channel_drawer_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "${channels.size} channels • $sourceLabel",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_channel_drawer_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = BorderSubtle)
                Spacer(Modifier.height(10.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        val active = ch.id == currentChannelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (active) PulseCyanContainer else SurfaceHigh)
                                .border(
                                    width = 1.dp,
                                    color = if (active) PulseCyan else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    onSelectChannel(ch)
                                    onDismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("drawer_channel_${ch.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${ch.number}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) PulseCyan else TextSecondary,
                                modifier = Modifier.width(38.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ch.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = ch.epg.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (ch.drm?.isClearKey == true) {
                                HudBadge("DRM", DrmAmberContainer, DrmAmber)
                            } else {
                                HudBadge(ch.streamTypeHint.badgeCode, ObsidianBg, PulseCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamDiagnosticsDialog(
    state: NativePlayerUiState,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SurfaceElevated,
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(0.9f)
                .clickable(enabled = false, onClick = {})
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = PulseCyan)
                        Text(
                            text = stringResource(R.string.label_stream_diagnostics),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_diagnostics_button")) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                HorizontalDivider(color = BorderSubtle)

                DiagnosticRow("Channel API", state.apiSourceLabel)
                DiagnosticRow("Detected Protocol", "${state.detectedStream?.streamType?.displayLabel ?: "—"} (${state.detectedStream?.streamType?.badgeCode ?: "AUTO"})")
                DiagnosticRow("Detection Method", state.detectedStream?.detectionMethod ?: "—")
                DiagnosticRow("DRM Engine", state.drmStatusLabel, accent = if (state.isDrmActive) DrmAmber else SignalEmerald)
                if (!state.drmKidBase64Url.isNullOrBlank()) {
                    DiagnosticRow("ClearKey KID (Base64URL)", state.drmKidBase64Url, accent = DrmAmber)
                }
                DiagnosticRow("Video Output", "${state.videoResolution} • ${state.bitrateLabel}")
                DiagnosticRow("Codecs", "${state.videoCodec} / ${state.audioCodec}")
                DiagnosticRow("Aspect Ratio", state.resizeMode.label)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, accent: Color = TextPrimary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TrackSelectorDialog(
    state: NativePlayerUiState,
    onSelectVideo: (TrackOption) -> Unit,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectSubtitle: (TrackOption) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SurfaceElevated,
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(0.9f)
                .clickable(enabled = false, onClick = {})
        ) {
            LazyColumn(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.label_track_selection),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_tracks_button")) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                    }
                    HorizontalDivider(color = BorderSubtle)
                }

                item {
                    Text(
                        text = "VIDEO QUALITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = PulseCyan,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(state.videoTracks, key = { it.id }) { opt ->
                    TrackOptionRow(opt) {
                        onSelectVideo(opt)
                        onDismiss()
                    }
                }

                if (state.audioTracks.isNotEmpty()) {
                    item {
                        Text(
                            text = "AUDIO TRACKS",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseCyan,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(state.audioTracks, key = { it.id }) { opt ->
                        TrackOptionRow(opt) {
                            onSelectAudio(opt)
                            onDismiss()
                        }
                    }
                }

                if (state.subtitleTracks.size > 1) {
                    item {
                        Text(
                            text = "CAPTIONS / SUBTITLES",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulseCyan,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(state.subtitleTracks, key = { it.id }) { opt ->
                        TrackOptionRow(opt) {
                            onSelectSubtitle(opt)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackOptionRow(option: TrackOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (option.isSelected) PulseCyanContainer else SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (option.isSelected) PulseCyan else TextPrimary
        )
        if (option.isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = PulseCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
