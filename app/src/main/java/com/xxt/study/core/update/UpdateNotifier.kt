package com.xxt.study.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 更新相关的通知。
 *
 * 存在的理由只有一个：[UpdateCenter] 允许"关掉弹窗继续下载"，
 * 那下载完成时用户很可能已经不在应用里了——没有通知，他根本不知道下好了。
 *
 * 不引 WorkManager：包只有 2 MB 出头，几秒就下完，
 * 为一次瞬时任务引一整套作业调度不划算（项目一贯原则：宁可少一个依赖）。
 */
class UpdateNotifier(
    private val context: Context,
    /**
     * 点通知要打开的意图。由容器注入，core 层不认识 MainActivity。
     */
    private val contentIntent: () -> Intent?,
) {

    fun notifyReady(versionName: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)

        val pending = contentIntent()?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // 系统自带的"下载完成"图标：单色、各版本都有，不用自备一套通知小图标
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("更新已下载")
            .setContentText("v$versionName 已就绪，点一下继续安装")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()

        // Android 13 起没授通知权限时 notify 会被静默丢弃，不崩——这里也不能因此中断流程
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    fun cancel() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID)
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "软件更新",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "新版本下载完成时提醒"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "update"
        const val NOTIFY_ID = 2001

        /** 通知点击后带这个 extra 回 Activity，让它自动把安装流程接着走完 */
        const val EXTRA_OPEN_UPDATE = "com.xxt.study.extra.OPEN_UPDATE"
    }
}
