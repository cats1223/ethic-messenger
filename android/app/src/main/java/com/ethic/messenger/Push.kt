package com.ethic.messenger

import com.ethic.messenger.call.CallManager
import com.ethic.messenger.data.Api
import com.ethic.messenger.data.Repo
import com.ethic.messenger.data.RingEvent
import com.ethic.messenger.data.User
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/** Firebase push: wakes the app for messages and incoming calls when it is closed. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.register(token) }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "message" -> {
                // While the app is open the socket already delivered it.
                if (Repo.foreground) return
                Notifications.pushMessage(d["chatId"]?.toLongOrNull() ?: return, d["title"].orEmpty(), d["body"].orEmpty())
            }
            "call" -> {
                val chatId = d["chatId"]?.toLongOrNull() ?: return
                val from = User(d["fromId"]?.toLongOrNull() ?: 0, displayName = d["fromName"].orEmpty(), avatar = d["fromAvatar"]?.ifEmpty { null })
                CallManager.ringFromPush(RingEvent(chatId, from, video = d["video"] == "1", title = d["title"]?.ifEmpty { null }))
            }
        }
    }
}

object Push {
    fun register(token: String? = null) {
        if (Api.token == null) return
        if (token != null) { send(token); return }
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { send(it) } }
    }

    private fun send(token: String) {
        Repo.prefs.edit().putString("pushToken", token).apply()
        Repo.scope.launch { runCatching { Api.call("/api/push/token", "POST", mapOf("token" to token)) } }
    }

    /** On logout: this phone stops getting this account's notifications. */
    suspend fun unregister() {
        val t = Repo.prefs.getString("pushToken", null) ?: return
        runCatching { Api.call("/api/push/token/remove", "POST", mapOf("token" to t)) }
    }
}
