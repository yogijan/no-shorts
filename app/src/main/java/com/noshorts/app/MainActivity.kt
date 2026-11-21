package com.noshorts.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * YouTube's mobile site in a WebView, made to behave like a native app, with every Shorts surface removed:
 *  - assets/noshorts.js hides Shorts shelves, the Shorts tab, Shorts chips and any video card linking to a Short;
 *  - any navigation to /shorts/ID (tap, deep link, back button) is rewritten to the normal player, /watch?v=ID.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingBar: View
    private lateinit var errorView: View
    private lateinit var filterJs: String

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var pageColor = 0
    private var playing = false
    private var contentReady = false
    private var loadFailed = false
    private var failedUrl: String? = null

    private val dp: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filterJs = assets.open("noshorts.js").bufferedReader().use { it.readText() }
        pageColor = resources.getColor(R.color.background, theme)

        root = FrameLayout(this).apply { setBackgroundColor(pageColor) }
        webView = WebView(this).apply { setBackgroundColor(pageColor) }
        loadingBar = View(this).apply {
            setBackgroundColor(LOADING_BAR_COLOR)
            pivotX = 0f
            scaleX = 0f
            alpha = 0f
        }
        errorView = buildErrorView()
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(errorView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(loadingBar, FrameLayout.LayoutParams(MATCH_PARENT, (3 * dp).toInt(), Gravity.TOP))
        setContentView(root)

        holdSplashUntilContent()
        setUpEdgeToEdge()
        setUpWebView()

        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) webView.loadUrl(startUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-tapping the launcher icon shouldn't throw away where you were.
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            exitFullscreen()
            webView.loadUrl(startUrl(intent))
        }
    }

    private fun startUrl(i: Intent?): String =
        Urls.forIntent(i?.data, i?.getStringExtra(Intent.EXTRA_TEXT))

    /** Keep the splash (or plain background) up until the first page paints, instead of a blank flash. */
    private fun holdSplashUntilContent() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!contentReady) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        root.postDelayed({ markContentReady() }, SPLASH_TIMEOUT_MS)
    }

    private fun markContentReady() {
        if (contentReady) return
        contentReady = true
        root.invalidate()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setUpWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            // Present as regular Chrome rather than an embedded WebView; Google refuses
            // sign-in from user agents that say "wv".
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(Regex("Version/[\\d.]+ "), "")
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
    }

    private fun injectFilter(view: WebView) = view.evaluateJavascript(filterJs, null)

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            when (uri.scheme?.lowercase()) {
                "http", "https" -> Unit
                // The site's "open in the YouTube app" hand-offs: stay here instead.
                "intent", "vnd.youtube" -> return true
                else -> { openExternally(uri); return true }
            }
            Urls.watchUrlFor(uri)?.let { view.loadUrl(it); return true }
            if (!Urls.isInApp(uri.host)) { openExternally(uri); return true }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loadFailed = false
            showLoading(10)
            injectFilter(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectFilter(view)
            if (!loadFailed) {
                errorView.visibility = View.GONE
                markContentReady()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            injectFilter(view)
            hideLoading()
            if (loadFailed) showError() else errorView.visibility = View.GONE
            markContentReady()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            loadFailed = true
            failedUrl = request.url.toString()
            showError()
        }

        // Also fires for in-page (pushState) navigation, which shouldOverrideUrlLoading never sees.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            val target = url?.let { Urls.watchUrlFor(Uri.parse(it)) }
            if (target != null) {
                // replace() rather than loadUrl() so the Shorts entry leaves history and Back can't loop.
                view.evaluateJavascript("location.replace(${JSONObject.quote(target)})", null)
            } else {
                injectFilter(view)
            }
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            // Nothing on the phone handles this link; ignore the tap.
        }
    }

    /** Called from noshorts.js. Runs on a WebView thread, so hop to the UI thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun setPlaying(isPlaying: Boolean) = runOnUiThread { onPlaybackChanged(isPlaying) }

        @JavascriptInterface
        fun setPageColor(r: Int, g: Int, b: Int) = runOnUiThread { applyPageColor(Color.rgb(r, g, b)) }
    }

    // ------------------------------------------------------- Loading & errors

    private fun showLoading(percent: Int) {
        loadingBar.animate().cancel()
        loadingBar.alpha = 1f
        loadingBar.animate().scaleX(percent.coerceIn(5, 100) / 100f).setDuration(200).start()
    }

    private fun hideLoading() {
        if (loadingBar.alpha == 0f) return
        loadingBar.animate().cancel()
        loadingBar.animate().scaleX(1f).alpha(0f).setDuration(250)
            .withEndAction { loadingBar.scaleX = 0f }
            .start()
    }

    private fun showError() {
        hideLoading()
        errorView.visibility = View.VISIBLE
        markContentReady()
    }

    private fun retry() {
        errorView.visibility = View.GONE
        webView.loadUrl(failedUrl ?: webView.url ?: Urls.HOME)
    }

    private fun buildErrorView(): View {
        val pad = (32 * dp).toInt()
        val title = TextView(this).apply {
            setText(R.string.offline_title)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            setText(R.string.offline_body)
            textSize = 15f
            alpha = 0.7f
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
        }
        val retry = Button(this).apply {
            setText(R.string.retry)
            setOnClickListener { retry() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(resources.getColor(R.color.background, theme))
            isClickable = true
            visibility = View.GONE
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    // ------------------------------------------------------- Theme

    /** Match the status/navigation bars to the page, so YouTube's own light/dark setting looks seamless. */
    @Suppress("DEPRECATION")
    private fun applyPageColor(color: Int) {
        if (color == pageColor) return
        pageColor = color
        webView.setBackgroundColor(color)
        if (fullscreenView == null) root.setBackgroundColor(color)
        setLightSystemBars(Color.luminance(color) > 0.5f)
        if (Build.VERSION.SDK_INT < 35) window.navigationBarColor = color
    }

    @Suppress("DEPRECATION")
    private fun setLightSystemBars(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val decor = window.decorView
            decor.systemUiVisibility =
                if (light) decor.systemUiVisibility or flags
                else decor.systemUiVisibility and flags.inv()
        }
    }

    // ------------------------------------------------------- Playback & picture-in-picture

    private fun onPlaybackChanged(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pipSupported()) {
            try {
                setPictureInPictureParams(pipParams())
            } catch (e: IllegalStateException) {
                // PiP disabled for this app in system settings.
            }
        }
    }

    private fun pipSupported() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: slide smoothly into PiP when you swipe home while a video plays.
            builder.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && playing && pipSupported()) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (e: IllegalStateException) {
                // PiP disabled for this app in system settings.
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        loadingBar.visibility = if (isInPictureInPictureMode) View.INVISIBLE else View.VISIBLE
        webView.evaluateJavascript(
            "window.__noShorts && window.__noShorts.setPip($isInPictureInPictureMode)", null,
        )
    }

    // ------------------------------------------------------- Fullscreen video

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            root.setBackgroundColor(Color.BLACK)
            root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            webView.visibility = View.GONE
            setSystemBarsHidden(true)
            // Landscape for normal videos; vertical videos stay upright, like the YouTube app.
            webView.evaluateJavascript(VIDEO_IS_PORTRAIT_JS) { result ->
                if (fullscreenView != null && result != "true") {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }

        override fun onHideCustomView() = exitFullscreen()

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress < 100) showLoading(newProgress) else hideLoading()
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        root.removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webView.visibility = View.VISIBLE
        root.setBackgroundColor(pageColor)
        setSystemBarsHidden(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ------------------------------------------------------- System bars

    /** Draw behind the status/navigation bars everywhere and pad the content ourselves. */
    @Suppress("DEPRECATION")
    private fun setUpEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            val decor = window.decorView
            decor.systemUiVisibility = decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            val immersive = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val decor = window.decorView
            decor.systemUiVisibility =
                if (hidden) decor.systemUiVisibility or immersive
                else decor.systemUiVisibility and immersive.inv()
        }
    }

    // ------------------------------------------------------- Lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> exitFullscreen()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    // Pause on stop rather than on pause, so video keeps playing in picture-in-picture and split screen.
    override fun onStart() {
        super.onStart()
        webView.onResume()
    }

    override fun onStop() {
        if (playing) webView.evaluateJavascript(PAUSE_VIDEOS_JS, null)
        webView.onPause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        root.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val BRIDGE_NAME = "NoShortsAndroid"
        const val SPLASH_TIMEOUT_MS = 2500L
        val LOADING_BAR_COLOR = Color.rgb(0xFF, 0x00, 0x33)
        const val VIDEO_IS_PORTRAIT_JS =
            "(function(){var v=document.querySelector('video');return !!v && v.videoHeight > v.videoWidth;})()"
        const val PAUSE_VIDEOS_JS =
            "document.querySelectorAll('video').forEach(function(v){v.pause();})"
    }
}
