package com.example.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.ui.theme.StreamPulseTheme
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
class NativePlayerActivity : ComponentActivity() {

    private val viewModel: NativePlayerViewModel by viewModels()
    private val isInPipState = mutableStateOf(false)
    private lateinit var audioManager: AudioManager

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    viewModel.togglePlayPause()
                    updatePipParamsIfSupported()
                }
                ACTION_PIP_NEXT -> {
                    viewModel.nextChannel()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        hideSystemBarsImmersive()
        registerPipReceiver()

        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }

        val pipSupported = isPipSupported()

        setContent {
            StreamPulseTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val inPip by isInPipState

                LaunchedEffect(state.isPlaying) {
                    updatePipParamsIfSupported()
                }

                NativePlayerScreen(
                    viewModel = viewModel,
                    state = state,
                    isPipMode = inPip,
                    isPipSupported = pipSupported,
                    initialBrightness = readCurrentBrightness(),
                    initialVolume = readCurrentVolume(),
                    onApplyBrightness = { level -> applyWindowBrightness(level) },
                    onApplyVolume = { level -> applyMediaVolume(level) },
                    onEnterPip = { triggerPictureInPicture() },
                    onToggleOrientation = { toggleScreenOrientation() },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val rawPayload = intent?.getStringExtra(EXTRA_CHANNEL_PAYLOAD)
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID)
        val apiUrl = intent?.getStringExtra(EXTRA_API_URL)
        viewModel.initializeFromLaunch(rawPayload, channelId, apiUrl)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            if (viewModel.uiState.value.isPlaying) {
                triggerPictureInPicture()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            hideSystemBarsImmersive()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            viewModel.exoPlayer.pause()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    private fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun buildPipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val playing = viewModel.uiState.value.isPlaying

        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = RemoteAction(
            Icon.createWithResource(
                this,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            getString(if (playing) R.string.pip_action_pause else R.string.pip_action_play),
            getString(if (playing) R.string.pip_action_pause else R.string.pip_action_play),
            playPauseIntent
        )

        val nextIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(ACTION_PIP_NEXT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_next),
            getString(R.string.pip_action_next),
            getString(R.string.pip_action_next),
            nextIntent
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(playPauseAction, nextAction))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(playing)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParamsIfSupported() {
        if (!isPipSupported()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildPipParams()?.let { params ->
                runCatching { setPictureInPictureParams(params) }
            }
        }
    }

    private fun triggerPictureInPicture() {
        if (!isPipSupported()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildPipParams()?.let { params ->
                runCatching { enterPictureInPictureMode(params) }
            }
        }
    }

    private fun hideSystemBarsImmersive() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleScreenOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun readCurrentBrightness(): Float {
        val attr = window.attributes.screenBrightness
        return if (attr in 0.01f..1f) attr else 0.6f
    }

    private fun applyWindowBrightness(level: Float) {
        val lp = window.attributes
        lp.screenBrightness = level.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    private fun readCurrentVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    private fun applyMediaVolume(level: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (level.coerceIn(0f, 1f) * max).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_NEXT)
        }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    companion object {
        const val EXTRA_CHANNEL_PAYLOAD = "extra_channel_payload"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_API_URL = "extra_api_url"

        private const val ACTION_PIP_PLAY_PAUSE = "com.example.player.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_NEXT = "com.example.player.PIP_NEXT"

        fun createLaunchIntent(
            context: Context,
            channelPayload: String? = null,
            channelId: String? = null,
            apiUrl: String? = null
        ): Intent {
            return Intent(context, NativePlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_PAYLOAD, channelPayload)
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_API_URL, apiUrl)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun NativePlayerScreen(
    viewModel: NativePlayerViewModel,
    state: NativePlayerUiState,
    isPipMode: Boolean,
    isPipSupported: Boolean,
    initialBrightness: Float,
    initialVolume: Float,
    onApplyBrightness: (Float) -> Unit,
    onApplyVolume: (Float) -> Unit,
    onEnterPip: () -> Unit,
    onToggleOrientation: () -> Unit,
    onBack: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var showChannelDrawer by remember { mutableStateOf(false) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(initialBrightness) }
    var volume by remember { mutableFloatStateOf(initialVolume) }

    LaunchedEffect(controlsVisible, state.isPlaying, showChannelDrawer, showTrackDialog, showDiagnostics) {
        if (controlsVisible && state.isPlaying && !showChannelDrawer && !showTrackDialog && !showDiagnostics) {
            delay(4200L)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ExoPlayer Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.exoPlayer
                    useController = false
                    keepScreenOn = true
                    resizeMode = state.resizeMode.media3Mode
                }
            },
            update = { playerView ->
                playerView.resizeMode = state.resizeMode.media3Mode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Touch Gesture Layer (brightness, volume, double-tap seek, scrub, single-tap HUD)
        PlayerGestureSurface(
            isLocked = state.isControlsLocked,
            isPipMode = isPipMode,
            currentBrightness = brightness,
            currentVolume = volume,
            currentPositionMs = state.currentPositionMs,
            durationMs = state.durationMs,
            onSingleTap = { controlsVisible = !controlsVisible },
            onDoubleTapRewind = { viewModel.seekBy(-10_000L) },
            onDoubleTapForward = { viewModel.seekBy(10_000L) },
            onBrightnessChange = { next ->
                brightness = next
                onApplyBrightness(next)
            },
            onVolumeChange = { next ->
                volume = next
                onApplyVolume(next)
            },
            onSeekCommit = { targetMs -> viewModel.seekTo(targetMs) }
        )

        // Full Broadcast Player HUD (hidden automatically in PiP mode)
        if (!isPipMode) {
            PlayerControlsHud(
                state = state,
                visible = controlsVisible,
                isPipSupported = isPipSupported,
                onBack = onBack,
                onEnterPip = onEnterPip,
                onOpenChannelDrawer = {
                    controlsVisible = true
                    showChannelDrawer = true
                },
                onOpenTracks = {
                    controlsVisible = true
                    showTrackDialog = true
                },
                onOpenDiagnostics = {
                    controlsVisible = true
                    showDiagnostics = true
                },
                onPlayPause = { viewModel.togglePlayPause() },
                onPreviousChannel = { viewModel.previousChannel() },
                onNextChannel = { viewModel.nextChannel() },
                onRewind10 = { viewModel.seekBy(-10_000L) },
                onForward10 = { viewModel.seekBy(10_000L) },
                onSeekTo = { viewModel.seekTo(it) },
                onJumpToLive = { viewModel.jumpToLiveEdge() },
                onToggleMute = { viewModel.toggleMute() },
                onToggleLock = {
                    viewModel.toggleControlsLock()
                    controlsVisible = true
                },
                onCycleAspectRatio = { viewModel.cycleResizeMode() },
                onToggleOrientation = onToggleOrientation,
                onRetry = { viewModel.retryCurrentChannel() }
            )

            if (showChannelDrawer) {
                ChannelSwitcherSheet(
                    channels = state.channels,
                    currentChannelId = state.currentChannel?.id,
                    sourceLabel = state.apiSourceLabel,
                    onSelectChannel = { viewModel.playChannel(it) },
                    onDismiss = { showChannelDrawer = false }
                )
            }

            if (showDiagnostics) {
                StreamDiagnosticsDialog(
                    state = state,
                    onDismiss = { showDiagnostics = false }
                )
            }

            if (showTrackDialog) {
                TrackSelectorDialog(
                    state = state,
                    onSelectVideo = { viewModel.selectVideoTrack(it) },
                    onSelectAudio = { viewModel.selectAudioTrack(it) },
                    onSelectSubtitle = { viewModel.selectSubtitleTrack(it) },
                    onDismiss = { showTrackDialog = false }
                )
            }
        }
    }
}
