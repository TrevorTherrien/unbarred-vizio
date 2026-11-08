package io.github.trevortherrien.unbarred

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-demand "is there a newer release?" check against the GitHub Releases API. No background
 * polling, no auto-install — it fetches the latest release, compares versions, and hands back a
 * link the user opens themselves. Dependency-free: HttpURLConnection + org.json (both in Android).
 *
 * Set [REPO_OWNER]/[REPO_NAME] once the repo is published. Until then (or if there are no
 * releases) the check reports [Result.NotConfigured] instead of failing.
 */
object UpdateChecker {

    // Published GitHub repository (used for the on-demand release check).
    const val REPO_OWNER = "TrevorTherrien"
    const val REPO_NAME = "unbarred-vizio"

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?) : Result
        data class NotConfigured(val detail: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        if (REPO_OWNER.startsWith("your-") || REPO_OWNER.isBlank()) {
            return@withContext Result.NotConfigured("Set REPO_OWNER / REPO_NAME in UpdateChecker.kt")
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Unbarred-updatecheck")
            }
            when (val code = conn.responseCode) {
                200 -> parse(conn.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                404 -> Result.NotConfigured("No published releases for $REPO_OWNER/$REPO_NAME yet")
                403 -> Result.Error("GitHub rate limit reached — try again later")
                else -> Result.Error("GitHub returned HTTP $code")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String, currentVersion: String): Result {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
        val notes = json.optString("body").trim()
        val pageUrl = json.optString("html_url")
        val latest = tag.trimStart('v', 'V')

        var apkUrl: String? = null
        json.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url"); break
                }
            }
        }
        return if (isNewer(latest, currentVersion)) Result.Available(tag, notes, pageUrl, apkUrl)
        else Result.UpToDate
    }

    /** Dotted numeric compare; true if [latest] > [current]. Non-numeric suffixes are ignored. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
