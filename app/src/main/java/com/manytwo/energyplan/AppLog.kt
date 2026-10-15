package com.manytwo.energyplan

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling text log kept in app-private storage, so user actions,
 * errors and crashes survive a restart. A copy is written next to the JSON on
 * every save (Documents/EnergyPlan/<name>-log.txt) and can be shared from the
 * menu. Everything is mirrored to logcat under the tag "EnergyPlan".
 */
object AppLog {
    private const val TAG = "EnergyPlan"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_BYTES = 256 * 1024

    private var file: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "energyplan.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            e("CRASH in thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
        i(
            "=== App start: Energy Plan ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, built ${BuildConfig.BUILD_DATE}) " +
                "on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ==="
        )
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        write("I", msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        write("W", msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        write("E", if (t == null) msg else "$msg\n${stackTrace(t)}")
    }

    @Synchronized
    fun text(): String = file?.takeIf { it.exists() }?.readText() ?: ""

    @Synchronized
    private fun write(level: String, msg: String) {
        val f = file ?: return
        try {
            f.appendText("${stamp.format(Date())} $level $msg\n")
            if (f.length() > MAX_BYTES) {
                // Keep the newest part, starting at a line boundary.
                val t = f.readText()
                val cut = t.indexOf('\n', t.length - KEEP_BYTES).let { if (it < 0) 0 else it + 1 }
                f.writeText("--- older log lines trimmed ---\n" + t.substring(cut))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not write log file", e)
        }
    }

    private fun stackTrace(t: Throwable) = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
}

fun Room.describe() =
    "'$name' ${fmtM(width)}×${fmtM(depth)}×${fmtM(height)} m at (${fmtM(x)}, ${fmtM(y)})"
