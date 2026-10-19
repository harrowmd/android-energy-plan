package com.manytwo.energyplan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Runs a save as a foreground service, so it finishes even if the user
 * leaves the app or the screen goes off. (Saving to a cloud folder takes
 * 20-40 s, and Android stops a backgrounded app that keeps talking to the
 * cloud provider - which broke a save on 2026-10-19.) Progress shows as a
 * notification; when it's done, the visible activity gets the result, or a
 * notification says so if the app is in the background.
 */
class SaveService : Service() {

    companion object {
        private const val CHANNEL = "saving"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2

        /** True from the moment a save is requested until it has finished. */
        @Volatile
        var running = false
            private set

        private var pending: Plan? = null

        /** Set by the activity while it's visible; called on the main thread with the files written. */
        var listener: ((Result<List<String>>, Plan) -> Unit)? = null

        fun start(ctx: Context, plan: Plan) {
            pending = plan
            running = true
            ctx.startForegroundService(Intent(ctx, SaveService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val plan = pending
        pending = null
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Saving plans", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID_PROGRESS, notification("Saving ${plan?.name ?: "plan"}…", null, ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (plan == null) {
            // Restarted without a plan (shouldn't happen: START_NOT_STICKY) - nothing to do.
            finish()
            return START_NOT_STICKY
        }

        thread(name = "save") {
            val start = System.currentTimeMillis()
            val result = runCatching {
                PlanStorage.save(this, plan) { done, total ->
                    nm.notify(ID_PROGRESS, notification("Saving ${plan.name}…", "$done of $total files", ongoing = true, progress = done to total))
                }
            }
            result.onSuccess { AppLog.i("Save finished in ${System.currentTimeMillis() - start} ms") }
                .onFailure { AppLog.e("Save failed", it) }
            // Written last, so it records how the save went - even a failed one.
            val log = runCatching { PlanStorage.saveLog(this, plan) }.onFailure { AppLog.e("Could not write log file", it) }
            val final = result.map { it + listOfNotNull(log.getOrNull()?.let { "${PlanStorage.baseName(plan)}.txt" }) }
            main.post {
                running = false
                val l = listener
                if (l != null) {
                    l(final, plan)
                } else {
                    // The app isn't on screen: say how it went.
                    val text = final.fold({ "Saved ${it.size} files" }, { "Save failed: ${it.message}" })
                    nm.notify(ID_DONE, notification("${plan.name}: $text", PlanStorage.locationLabel(this), ongoing = false))
                    AppLog.i("Save finished in the background: $text")
                }
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(title: String, text: String?, ongoing: Boolean, progress: Pair<Int, Int>? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (progress != null) setProgress(progress.second, progress.first, false) }
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(open)
            .build()
    }
}
