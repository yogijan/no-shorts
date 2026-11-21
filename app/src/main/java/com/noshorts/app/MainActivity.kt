package com.noshorts.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * YouTube's mobile site in a WebView, with every Shorts surface removed:
 *  - assets/noshorts.js hides Shorts shelves, the Shorts tab, Shorts chips and any video card linking to a Short;
 *  - any navigation to /shorts/ID (tap, deep link, back button) is rewritten to the normal player, /watch?v=ID.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var filterJs: String

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filterJs = assets.open("noshorts.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

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

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
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

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = injectFilter(view)

        override fun onPageCommitVisible(view: WebView, url: String?) = injectFilter(view)

        override fun onPageFinished(view: WebView, url: String?) = injectFilter(view)

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
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() = exitFullscreen()
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        root.removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webView.visibility = View.VISIBLE
        root.background = null
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

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
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
}
