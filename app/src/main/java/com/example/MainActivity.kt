package com.example

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.example.bridge.AndroidPortalBridge
import com.example.data.AppPreferences
import com.example.data.ChannelRepository
import com.example.data.UrlSecurityValidator
import com.example.player.NativePlayerActivity
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StreamPulseTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val channelRepository = ChannelRepository()
    private lateinit var preferences: AppPreferences

    @Volatile
    private var latestSnapshot = AndroidPortalBridge.PortalSnapshot(
        apiUrl = "",
        sourceLabel = "Built-in Broadcast Feed",
        channels = ChannelRepository.builtInChannels(),
        recentIds = emptyList()
    )

    private var activeWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        preferences = AppPreferences(applicationContext)

        refreshPortalSnapshot(notifyWebView = true)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_ASSETS_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val portalBridge = AndroidPortalBridge(
            onLaunchNative = { payload, apiUrl ->
                runOnUiThread {
                    val intent = NativePlayerActivity.createLaunchIntent(
                        context = this,
                        channelPayload = payload,
                        apiUrl = apiUrl
                    )
                    startActivity(intent)
                }
            },
            onRequestLoadApi = { requestedUrl ->
                handleApiLoadRequest(requestedUrl)
            },
            onResetDefaultApi = {
                lifecycleScope.launch {
                    preferences.setChannelApiUrl("")
                    refreshPortalSnapshot(notifyWebView = true)
                }
            },
            currentStateProvider = { latestSnapshot }
        )

        setContent {
            StreamPulseTheme {
                HybridEpgWebViewScreen(
                    assetLoader = assetLoader,
                    portalBridge = portalBridge,
                    onWebViewReady = { webView ->
                        activeWebView = webView
                        pushSnapshotToWebView(webView)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPortalSnapshot(notifyWebView = true)
    }

    override fun onDestroy() {
        activeWebView?.removeJavascriptInterface(JS_BRIDGE_NAME)
        activeWebView?.destroy()
        activeWebView = null
        super.onDestroy()
    }

    private fun handleApiLoadRequest(rawUrl: String) {
        if (!UrlSecurityValidator.isSafeNetworkUrl(rawUrl)) {
            latestSnapshot = latestSnapshot.copy(
                warningMessage = "Only valid http:// or https:// IPTV API or M3U URLs are allowed."
            )
            activeWebView?.let { pushSnapshotToWebView(it) }
            return
        }

        lifecycleScope.launch {
            val result = channelRepository.loadChannels(rawUrl)
            if (result.fromRemoteApi) {
                preferences.setChannelApiUrl(rawUrl)
            }
            val recent = preferences.getRecentChannelIds()
            latestSnapshot = AndroidPortalBridge.PortalSnapshot(
                apiUrl = if (result.fromRemoteApi) rawUrl else preferences.getChannelApiUrl(),
                sourceLabel = result.sourceLabel,
                channels = result.channels,
                recentIds = recent,
                warningMessage = result.warningMessage
            )
            activeWebView?.let { pushSnapshotToWebView(it) }
        }
    }

    private fun refreshPortalSnapshot(notifyWebView: Boolean) {
        lifecycleScope.launch {
            val savedApi = preferences.getChannelApiUrl()
            val recent = preferences.getRecentChannelIds()
            val result = channelRepository.loadChannels(savedApi)
            latestSnapshot = AndroidPortalBridge.PortalSnapshot(
                apiUrl = savedApi,
                sourceLabel = result.sourceLabel,
                channels = result.channels,
                recentIds = recent,
                warningMessage = result.warningMessage
            )
            if (notifyWebView) {
                activeWebView?.let { pushSnapshotToWebView(it) }
            }
        }
    }

    private fun pushSnapshotToWebView(webView: WebView) {
        val quotedJson = JSONObject.quote(latestSnapshot.toJsonString())
        val js = "if (window.onNativeChannelsLoaded) { window.onNativeChannelsLoaded($quotedJson); }"
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    companion object {
        private const val APP_ASSETS_DOMAIN = "appassets.androidplatform.net"
        private const val LOCAL_EPG_URL = "https://$APP_ASSETS_DOMAIN/assets/index.html"
        private const val JS_BRIDGE_NAME = "AndroidPortal"
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun HybridEpgWebViewScreen(
        assetLoader: WebViewAssetLoader,
        portalBridge: AndroidPortalBridge,
        onWebViewReady: (WebView) -> Unit
    ) {
        var webViewRef by remember { mutableStateOf<WebView?>(null) }

        BackHandler(enabled = webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("epg_webview"),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(Color.parseColor("#0B0E14"))
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            allowFileAccess = false
                            allowContentAccess = false
                            @Suppress("DEPRECATION")
                            allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = false
                            setSupportMultipleWindows(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }

                        addJavascriptInterface(portalBridge, JS_BRIDGE_NAME)

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return assetLoader.shouldInterceptRequest(request.url)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val uri: Uri = request.url
                                // Lock WebView navigation strictly to our bundled local assets domain
                                return uri.host != APP_ASSETS_DOMAIN
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                onWebViewReady(view)
                            }
                        }

                        loadUrl(LOCAL_EPG_URL)
                        webViewRef = this
                    }
                }
            )
        }
    }
}
