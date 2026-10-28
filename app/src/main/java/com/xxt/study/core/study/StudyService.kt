package com.xxt.study.core.study

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.xxt.study.R

/**
 * 刷课保活服务。
 *
 * 它**不执行业务**，只做两件事：
 * 1. 把自己挂成前台服务，让系统别在息屏时把进程回收（一次刷课动辄几小时）
 * 2. 顺带持有 WakeLock，避免 CPU 睡下去把协程的 delay 拖成几倍
 *
 * 业务在 [StudyController] 里，因为前台服务被系统重建时不该把任务从零点重跑一遍。
 */
class StudyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "正在刷课" }
        startForeground(NOTIFICATION_ID, build(this, text))
        // 被系统杀掉后不要重启：任务状态在内存里，重启也没法续，不如让用户自己决定
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "xxt_study_progress"
        private const val NOTIFICATION_ID = 0x5701
        private const val EXTRA_TEXT = "text"
        private const val WAKE_TAG = "xxt:study"

        private var wakeLock: PowerManager.WakeLock? = null

        fun start(context: Context, text: String = "正在刷课") {
            val intent = Intent(context, StudyService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StudyService::class.java))
        }

        /** 更新通知文案；失败就算了，通知不是功能的一部分 */
        fun update(context: Context, text: String) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, build(context, text))
            }
        }

        fun acquireWakeLock(context: Context) {
            if (wakeLock?.isHeld == true) return
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                    setReferenceCounted(false)
                    // 6 小时上限：真跑超了说明有任务卡死，这时候放开锁让它自然停下更合理
                    acquire(6 * 60 * 60 * 1000L)
                }
            }
        }

        fun releaseWakeLock() {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "刷课进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示当前课程与任务点进度"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun build(context: Context, text: String): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_study)
                .setContentTitle("学习通刷课")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
    }
}
