package com.ethic.messenger.call

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ethic.messenger.MainActivity
import com.ethic.messenger.Notifications
import com.ethic.messenger.R

/**
 * Runs for the whole call. Android only lets an app keep the microphone (and camera)
 * in the background while such a service is running, and shows the ongoing-call notification.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            CallManager.leave()
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra("title") ?: "Звонок"
        val video = intent?.getBooleanExtra("video", false) == true
        val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flagsPi)
        val hang = PendingIntent.getService(this, 1, Intent(this, CallService::class.java).setAction(ACTION_HANGUP), flagsPi)
        val n = NotificationCompat.Builder(this, Notifications.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText("Идёт звонок · Ethic Messenger")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, "Завершить", hang)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) {
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        runCatching { ServiceCompat.startForeground(this, ID, n, type) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_HANGUP = "com.ethic.messenger.HANGUP"
        private const val ID = 7001

        fun start(ctx: Context, title: String, video: Boolean) {
            val i = Intent(ctx, CallService::class.java).putExtra("title", title).putExtra("video", video)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CallService::class.java)) }
    }
}
