package com.yoriva.jobs
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
class JobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        JobProgress.attach(this)
        startInForeground(null)
        scope.launch {
            JobProgress.jobs.collectLatest { jobs ->
                val running = jobs.values.firstOrNull { it.running }
                if (running == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startInForeground(running)
                }
            }
        }
    }
    private fun startInForeground(job: JobProgress.Job?) {
        val ctx = applicationContext
        JobProgress.ensureChannel()
        val notification = job?.let { JobProgress.notificationFor(ctx, it) }
            ?: NotificationCompat.Builder(ctx, JobProgress.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Yoriva")
                .setContentText("Starting…")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(job),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationId(job), notification)
        }
    }
    private fun notificationId(job: JobProgress.Job?): Int =
        job?.let { JobProgress.idOf(it) } ?: JobService.SUMMARY_ID
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val SUMMARY_ID = 41_001
        fun start(context: Context) {
            val intent = Intent(context, JobService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, JobService::class.java)) }
        }
    }
}