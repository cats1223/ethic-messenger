package com.ethic.messenger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ethic.messenger.data.Chat
import com.ethic.messenger.data.Message
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.RingEvent

object Notifications {
    const val CH_CALL = "call"
    const val CH_INCOMING = "incoming"
    const val CH_MESSAGES = "messages"
    const val ACTION_DECLINE = "com.ethic.messenger.DECLINE"
    private const val ID_INCOMING = 7002
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Текущий звонок", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        // The app rings itself (ring stream), so the channel stays silent.
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun allowed() = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(extra: Intent.() -> Unit = {}) = PendingIntent.getActivity(
        app, (System.nanoTime() % 100000).toInt(),
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK).apply(extra),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun incoming(ev: RingEvent) {
        if (!allowed()) return
        val who = Repo.chats.value[ev.chatId]?.let { if (it.isGroup) it.title else null } ?: ev.title ?: ev.from.displayName
        val open = openIntent { putExtra("incoming", true) }
        val decline = openIntent { action = ACTION_DECLINE }
        val n = NotificationCompat.Builder(app, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(who)
            .setContentText(if (ev.video) "Входящий видеозвонок" else "Входящий звонок")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "Отклонить", decline)
            .addAction(0, "Ответить", open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(ID_INCOMING, n) }
    }

    fun cancelIncoming() = NotificationManagerCompat.from(app).cancel(ID_INCOMING)

    fun message(m: Message, chat: Chat) {
        if (!allowed()) return
        val title = if (chat.isGroup) "${chat.title}: ${Repo.userName(m.userId)}" else Repo.userName(m.userId)
        val text = m.text.ifBlank {
            when (m.kind) { "photo" -> "Фото"; "voice" -> "Голосовое сообщение"; "video" -> "Видео"; null -> ""; else -> m.file?.name ?: "Файл" }
        }
        val n = NotificationCompat.Builder(app, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent { putExtra("chatId", chat.id) })
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(chat.id.toInt(), n) }
    }

    fun cancelChat(chatId: Long) = NotificationManagerCompat.from(app).cancel(chatId.toInt())
}
