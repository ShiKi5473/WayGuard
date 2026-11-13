package com.wayguard.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.wayguard.MainActivity

/** Notification helper for background / deferred app update prompts. */
object AppUpdateNotifier {

    private const val CHANNEL_ID = "app_update"
    private const val NOTIF_ID = 2001
    const val ACTION_OPEN_UPDATE = "com.wayguard.action.OPEN_UPDATE"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App 版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "GitHub 新版本下載完成或需要確認安裝時的提醒"
            }
        )
    }

    fun notifyInstallReady(context: Context, version: String, message: String) {
        post(
            context = context,
            title = "WayGuard v$version 更新已就緒",
            text = message,
        )
    }

    fun notifyUpdated(context: Context, version: String) {
        post(
            context = context,
            title = "WayGuard 已更新至 v$version",
            text = "點擊開啟應用程式",
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_UPDATE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }
}

