package com.example.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PulseCyan
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class ActiveGestureMode {
    NONE, BRIGHTNESS, VOLUME, SEEK
}

@Composable
fun PlayerGestureSurface(
    isLocked: Boolean,
    isPipMode: Boolean,
    currentBrightness: Float,
    currentVolume: Float,
    currentPositionMs: Long,
    durationMs: Long,
    onSingleTap: () -> Unit,
    onDoubleTapRewind: () -> Unit,
    onDoubleTapForward: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeekCommit: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeGesture by remember { mutableStateOf(ActiveGestureMode.NONE) }
    var hudBrightness by remember { mutableFloatStateOf(currentBrightness) }
    var hudVolume by remember { mutableFloatStateOf(currentVolume) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(doubleTapFeedback) {
        if (doubleTapFeedback != null) {
            delay(650L)
            doubleTapFeedback = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .then(
                if (isPipMode) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(isLocked) {
                            detectTapGestures(
                                onTap = { onSingleTap() },
                                onDoubleTap = { offset ->
                                    if (isLocked) return@detectTapGestures
                                    val halfWidth = containerSize.width / 2f
                                    if (offset.x < halfWidth) {
                                        onDoubleTapRewind()
                                        doubleTapFeedback = "-10s"
                                    } else {
                                        onDoubleTapForward()
                                        doubleTapFeedback = "+10s"
                                    }
                                }
                            )
                        }
                        .pointerInput(isLocked, currentBrightness, currentVolume, durationMs) {
                            if (isLocked) return@pointerInput
                            var startBrightness = currentBrightness
                            var startVolume = currentVolume
                            var totalDragX = 0f
                            var totalDragY = 0f
                            var startX = 0f

                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    activeGesture = ActiveGestureMode.NONE
                                    startBrightness = currentBrightness
                                    startVolume = currentVolume
                                    hudBrightness = currentBrightness
                                    hudVolume = currentVolume
                                    totalDragX = 0f
                                    totalDragY = 0f
                                    seekDeltaMs = 0L
                                    startX = startOffset.x
                                },
                                onDragEnd = {
                                    if (activeGesture == ActiveGestureMode.SEEK && durationMs > 0L) {
                                        onSeekCommit((currentPositionMs + seekDeltaMs).coerceIn(0L, durationMs))
                                    }
                                    activeGesture = ActiveGestureMode.NONE
                                    seekDeltaMs = 0L
                                },
                                onDragCancel = {
                                    activeGesture = ActiveGestureMode.NONE
                                    seekDeltaMs = 0L
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDragX += dragAmount.x
                                    totalDragY += dragAmount.y

                                    if (activeGesture == ActiveGestureMode.NONE) {
                                        val threshold = 18f
                                        if (abs(totalDragY) > threshold || abs(totalDragX) > threshold) {
                                            activeGesture = if (abs(totalDragY) >= abs(totalDragX)) {
                                                if (startX < containerSize.width / 2f) {
                                                    ActiveGestureMode.BRIGHTNESS
                                                } else {
                                                    ActiveGestureMode.VOLUME
                                                }
                                            } else if (durationMs > 0L) {
                                                ActiveGestureMode.SEEK
                                            } else {
                                                ActiveGestureMode.NONE
                                            }
                                        }
                                    }

                                    val heightPx = containerSize.height.coerceAtLeast(300).toFloat()
                                    val widthPx = containerSize.width.coerceAtLeast(300).toFloat()

                                    when (activeGesture) {
                                        ActiveGestureMode.BRIGHTNESS -> {
                                            val delta = -totalDragY / (heightPx * 0.7f)
                                            val next = (startBrightness + delta).coerceIn(0.02f, 1f)
                                            hudBrightness = next
                                            onBrightnessChange(next)
                                        }
                                        ActiveGestureMode.VOLUME -> {
                                            val delta = -totalDragY / (heightPx * 0.7f)
                                            val next = (startVolume + delta).coerceIn(0f, 1f)
                                            hudVolume = next
                                            onVolumeChange(next)
                                        }
                                        ActiveGestureMode.SEEK -> {
                                            val maxScrubMs = 90_000L
                                            val ratio = (totalDragX / widthPx).coerceIn(-1f, 1f)
                                            seekDeltaMs = (ratio * maxScrubMs).toLong()
                                        }
                                        ActiveGestureMode.NONE -> Unit
                                    }
                                }
                            )
                        }
                }
            )
    ) {
        // Brightness vertical HUD pill (left gesture)
        AnimatedVisibility(
            visible = activeGesture == ActiveGestureMode.BRIGHTNESS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp)
        ) {
            VerticalGestureMeter(
                label = "BRIGHTNESS",
                progress = hudBrightness,
                icon = {
                    Icon(
                        imageVector = Icons.Default.BrightnessHigh,
                        contentDescription = null,
                        tint = PulseCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        // Volume vertical HUD pill (right gesture)
        AnimatedVisibility(
            visible = activeGesture == ActiveGestureMode.VOLUME,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp)
        ) {
            VerticalGestureMeter(
                label = "VOLUME",
                progress = hudVolume,
                icon = {
                    Icon(
                        imageVector = if (hudVolume <= 0.01f) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = null,
                        tint = PulseCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        // Horizontal seek scrub preview HUD
        AnimatedVisibility(
            visible = activeGesture == ActiveGestureMode.SEEK && durationMs > 0L,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val targetMs = (currentPositionMs + seekDeltaMs).coerceIn(0L, durationMs)
            val sign = if (seekDeltaMs >= 0) "+" else "-"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC0B0E14))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$sign${formatPlaybackTime(abs(seekDeltaMs))}",
                        style = MaterialTheme.typography.titleLarge,
                        color = PulseCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${formatPlaybackTime(targetMs)} / ${formatPlaybackTime(durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Double tap -10s / +10s visual burst
        AnimatedVisibility(
            visible = doubleTapFeedback != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(
                    if (doubleTapFeedback == "-10s") Alignment.CenterStart else Alignment.CenterEnd
                )
                .padding(horizontal = 44.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xBB0B0E14))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (doubleTapFeedback == "-10s") {
                        Icons.Default.FastRewind
                    } else {
                        Icons.Default.FastForward
                    },
                    contentDescription = null,
                    tint = PulseCyan,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = doubleTapFeedback.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun VerticalGestureMeter(
    label: String,
    progress: Float,
    icon: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xCC0B0E14))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon()
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(110.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight(progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(PulseCyan)
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.65f)
        )
    }
}

fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
