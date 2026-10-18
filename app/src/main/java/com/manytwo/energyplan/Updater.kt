package com.manytwo.energyplan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub releases: finds the latest release (or the one for
 * this version), downloads its m21energyplan.apk and hands it to Android's
 * installer. Releases are signed with the same key as the installed app, so
 * they install over it, keeping the plan and settings.
 */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases"
    private const val APK = "m21energyplan.apk"

    class Release(val tag: String, val apkUrl: String, val notes: String) {
        val version get() = tag.removePrefix("v")
    }

    /** The newest release. Network call: run off the main thread. */
    fun latest(): Release = fetch("$API/latest")

    /** The release for [version], e.g. "1.8"; null if it isn't on GitHub (an unreleased build). */
    fun forVersion(version: String): Release? = try {
        fetch("$API/tags/v$version")
    } catch (_: FileNotFoundException) {
        null
    }

    /** Compares dotted versions numerically: 1.10 is newer than 1.9. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun fetch(url: String): Release {
        val json = JSONObject(get(url).bufferedReader().use { it.readText() })
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == APK }
            ?: throw IOException("Release ${json.getString("tag_name")} has no $APK")
        return Release(json.getString("tag_name"), apk.getString("browser_download_url"), json.optString("body"))
    }

    private fun get(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "EnergyPlan/${BuildConfig.VERSION_NAME}")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        if (c.responseCode == 404) throw FileNotFoundException(url)
        if (c.responseCode !in 200..299) throw IOException("GitHub answered ${c.responseCode}")
        return c.inputStream
    }

    /** Downloads the release's APK into the app's cache, reporting progress 0-100. */
    fun download(ctx: Context, release: Release, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, APK)
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "EnergyPlan/${BuildConfig.VERSION_NAME}")
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        if (c.responseCode !in 200..299) throw IOException("Download failed: ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        return file
    }

    /**
     * Opens Android's installer for [apk]. Returns false (after opening the
     * setting) when the user still has to allow this app to install apps.
     */
    fun install(activity: Activity, apk: File): Boolean {
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}
