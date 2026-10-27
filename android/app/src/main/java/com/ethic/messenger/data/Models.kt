package com.ethic.messenger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class User(
    val id: Long,
    val username: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val banner: String? = null,
    val role: String = "user",
    val dev: Boolean = false,
    val verified: Boolean = false,
    val badge: String? = null,
    val online: Boolean = false,
    val lastSeen: Long? = null,
    val email: String? = null,
    val support: Boolean = false,
) {
    val isStaff get() = role == "owner" || role == "dev"
}

@Serializable
data class ImageRef(val url: String, val w: Int? = null, val h: Int? = null)

@Serializable
data class FileRef(
    val url: String,
    val name: String = "файл",
    val size: Long = 0,
    val kind: String = "file", // voice | video | audio | file
    val duration: Double? = null,
)

@Serializable
data class ReplyRef(
    val id: Long,
    val userId: Long? = null,
    val text: String = "",
    val kind: String? = null,
    val fileName: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class Message(
    val id: Long,
    val chatId: Long,
    val userId: Long,
    val text: String = "",
    val image: ImageRef? = null,
    val file: FileRef? = null,
    val replyTo: ReplyRef? = null,
    val editedAt: Long? = null,
    val createdAt: Long = 0,
    // Local-only: messages being sent.
    @Transient val clientId: String? = null,
    @Transient val pending: Boolean = false,
    @Transient val progress: Float = -1f,
    @Transient val localUri: String? = null,
    @Transient val fresh: Boolean = false,
) {
    val kind: String? get() = if (image != null) "photo" else file?.kind
}

@Serializable
data class CallInfo(val participants: List<Long> = emptyList(), val video: Boolean = false, val startedAt: Long = 0)

@Serializable
data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val createdAt: Long = 0,
    val members: List<User> = emptyList(),
    val lastMessage: Message? = null,
    val unread: Int = 0,
    val othersRead: Long = 0,
    val call: CallInfo? = null,
    val request: RequestRef? = null,
) {
    val isGroup get() = type == "group"
    val sortKey get() = lastMessage?.createdAt ?: createdAt
}

@Serializable
data class RequestRef(val from: Long)

data class ChatMessages(val list: List<Message> = emptyList(), val hasMore: Boolean = false, val loaded: Boolean = false)

@Serializable
data class RingEvent(
    val chatId: Long,
    val from: User,
    val video: Boolean = false,
    val invite: Boolean = false,
    val title: String? = null,
)
