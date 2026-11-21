package com.noshorts.app

import android.net.Uri

/** URL rules: which pages stay in the app, and how Shorts links get rewritten to the normal player. */
object Urls {
    const val HOME = "https://m.youtube.com/"

    private val SHORTS_PATH = Regex("^/shorts/([A-Za-z0-9_-]+)")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,}$")
    private val ANY_URL = Regex("https?://\\S+")

    fun isYouTube(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be" ||
            h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com")
    }

    /** YouTube itself plus the Google sign-in and consent pages it bounces through. Everything else opens in the browser. */
    fun isInApp(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return isYouTube(h) || h in GOOGLE_ACCOUNT_HOSTS || h.startsWith("accounts.google.")
    }

    private val GOOGLE_ACCOUNT_HOSTS = setOf("accounts.google.com", "consent.google.com", "myaccount.google.com")

    /**
     * If [uri] is a Shorts link (youtube.com/shorts/ID) or a youtu.be short link,
     * returns the regular watch-page URL for the same video; otherwise null.
     */
    fun watchUrlFor(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path ?: return null
        if (host == "youtu.be") {
            val id = path.trim('/').takeIf { VIDEO_ID.matches(it) } ?: return null
            return watch(id, uri.getQueryParameter("t"))
        }
        if (!isYouTube(host)) return null
        val id = SHORTS_PATH.find(path)?.groupValues?.get(1) ?: return null
        return watch(id, null)
    }

    /** The page to open for a launch, "open with" or "share" intent. */
    fun forIntent(data: Uri?, sharedText: String?): String {
        val uri = data ?: sharedText?.let { ANY_URL.find(it)?.value }?.let { Uri.parse(it) } ?: return HOME
        watchUrlFor(uri)?.let { return it }
        val web = uri.scheme == "https" || uri.scheme == "http"
        return if (web && isYouTube(uri.host)) uri.buildUpon().scheme("https").build().toString() else HOME
    }

    private fun watch(id: String, t: String?): String = buildString {
        append("https://m.youtube.com/watch?v=").append(id)
        if (!t.isNullOrBlank()) append("&t=").append(Uri.encode(t))
    }
}
