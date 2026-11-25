package com.yoriva.jobs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yoriva.MainActivity
import com.yoriva.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object JobProgress {
    const val CHANNEL_ID = "jobs"
    const val CHANNEL_ALERTS = "job_alerts"
    private const val BRAND_COLOR = 0xFF5D7DF2.toInt()
    private const val PROGRESS_THROTTLE_MS = 250L
    private const val RESULT_ID_BASE = 42_000

    enum class State { RUNNING, DONE, FAILED }

    data class Job(
        val id: String,
        val title: String,
        val detail: String = "",
        val done: Long = -1,
        val total: Long = -1,
        val state: State = State.RUNNING,
        val message: String = "",
        val apkPath: String? = null,
    ) {
        val running: Boolean get() = state == State.RUNNING
        val percent: Int
            get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    }

    private data class EmitStamp(
        val timeMs: Long,
        val percent: Int,
        val detail: String,
        val title: String,
    )

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    private val lastEmit = ConcurrentHashMap<String, EmitStamp>()

    @Volatile
    private var context: Context? = null

    fun attach(appContext: Context) {
        context = appContext.applicationContext
        ensureChannel()
    }

    fun ensureChannel() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Active tasks",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing downloads and APK patching progress"
                    setShowBadge(false)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ALERTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Task results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Completed builds and download or patch errors"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    fun begin(appContext: Context, id: String, title: String, detail: String = "") {
        val appCtx = appContext.applicationContext
        context = appCtx
        ensureChannel()
        cancelResultNotification(appCtx, id)
        val job = Job(id = id, title = title, detail = detail)
        lastEmit[id] = EmitStamp(SystemClock.elapsedRealtime(), -1, detail, title)
        _jobs.value = _jobs.value + (id to job)
        JobService.start(appCtx)
    }

    fun progress(appContext: Context, id: String, done: Long, total: Long, detail: String? = null) {
        context = appContext.applicationContext
        val current = _jobs.value[id] ?: return
        if (!current.running) return
        val nextDetail = detail ?: current.detail
        val next = current.copy(done = done, total = total, detail = nextDetail)
        val now = SystemClock.elapsedRealtime()
        val prev = lastEmit[id]
        val pct = next.percent
        val shouldEmit = prev == null ||
            nextDetail != prev.detail && (now - prev.timeMs) >= 120L ||
            pct >= 100 && prev.percent < 100 ||
            (now - prev.timeMs) >= PROGRESS_THROTTLE_MS ||
            (pct >= 0 && kotlin.math.abs(pct - prev.percent) >= 3 && (now - prev.timeMs) >= 100L)
        if (!shouldEmit) return
        lastEmit[id] = EmitStamp(now, pct, nextDetail, next.title)
        _jobs.value = _jobs.value + (id to next)
    }

    fun detail(appContext: Context, id: String, detail: String, title: String? = null) {
        context = appContext.applicationContext
        val current = _jobs.value[id] ?: return
        if (!current.running) return
        val nextTitle = title ?: current.title
        if (current.detail == detail && current.title == nextTitle) return
        val next = current.copy(title = nextTitle, detail = detail)
        lastEmit[id] = EmitStamp(SystemClock.elapsedRealtime(), next.percent, detail, nextTitle)
        _jobs.value = _jobs.value + (id to next)
    }

    fun finish(
        appContext: Context,
        id: String,
        message: String = "",
        ok: Boolean = true,
        apkPath: String? = null,
        notifyResult: Boolean = true,
    ) {
        val appCtx = appContext.applicationContext
        context = appCtx
        ensureChannel()
        val current = _jobs.value[id] ?: return
        lastEmit.remove(id)
        val finished = current.copy(
            state = if (ok) State.DONE else State.FAILED,
            message = message,
            apkPath = apkPath ?: current.apkPath,
        )
        _jobs.value = _jobs.value + (id to finished)
        if (_jobs.value.values.none { it.running }) {
            JobService.stop(appCtx)
        }
        if ((notifyResult || !ok) && canPostNotifications(appCtx)) {
            NotificationManagerCompat.from(appCtx).notify(
                resultIdOf(id),
                resultNotificationFor(appCtx, finished),
            )
        }
    }

    fun clear(id: String) {
        lastEmit.remove(id)
        _jobs.value = _jobs.value - id
        context?.let { cancelResultNotification(it, id) }
    }

    fun currentRunning(): Job? = _jobs.value.values.lastOrNull { it.running }

    fun ongoingNotificationFor(ctx: Context, job: Job?): Notification {
        val title = job?.title?.ifBlank { "Yoriva" } ?: "Yoriva"
        val body = job?.detail?.ifBlank { job.message.ifBlank { "Working…" } } ?: "Starting…"
        val pct = job?.percent ?: -1
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yoriva)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(if (pct >= 0) "$pct%" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchAppIntent(ctx, JobService.FOREGROUND_ID))
            .apply {
                if (pct >= 0) {
                    setProgress(100, pct, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun resultNotificationFor(ctx: Context, job: Job): Notification {
        val ok = job.state != State.FAILED
        val title = if (ok) job.title else "${job.title} failed"
        val summary = job.message.ifBlank { job.detail.ifBlank { if (ok) "Completed" else "Unknown error" } }
        return NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_yoriva)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (ok) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(launchAppIntent(ctx, resultIdOf(job.id)))
            .build()
    }

    private fun launchAppIntent(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            requestCode,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun resultIdOf(id: String): Int =
        RESULT_ID_BASE + (id.hashCode() and 0x7FFF)

    private fun cancelResultNotification(ctx: Context, id: String) {
        NotificationManagerCompat.from(ctx).cancel(resultIdOf(id))
    }

    private fun canPostNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
