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
    val plus: Boolean = false,
    val nameStyle: String? = null,
    val bio: String? = null,
    val mute: Mute? = null,
    val theme: String? = null,
    val lastSeenPrivacy: String? = null,
) {
    val isStaff get() = role == "owner" || role == "dev"

    /** Muted by staff: reads everything, cannot write until it ends. */
    @Serializable
    data class Mute(val until: Long? = null, val reason: String? = null) {
        val active get() = until == null || until > System.currentTimeMillis()
    }
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
    val forwardFrom: ForwardRef? = null,
    val reactions: List<Reaction> = emptyList(),
    val expiresAt: Long? = null,
    val transcript: String? = null,
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
data class ForwardRef(val id: Long = 0, val name: String = "")

@Serializable
data class Reaction(val emoji: String, val users: List<Long> = emptyList())

@Serializable
data class Folder(val id: Long, val name: String, val chatIds: List<Long> = emptyList())

@Serializable
data class JoinInfo(val id: Long, val type: String = "group", val title: String? = null, val members: Int = 0, val joined: Boolean = false)

@Serializable
data class Device(val id: String, val name: String = "", val platform: String = "other", val app: String = "", val createdAt: Long = 0, val lastSeen: Long? = null, val current: Boolean = false)

@Serializable
data class LinkPreview(val url: String, val site: String = "", val title: String = "", val description: String? = null, val image: String? = null)

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
    val ownerId: Long? = null,
    val admins: List<Long> = emptyList(),
    val inviteCode: String? = null,
    val disappear: Int = 0,
) {
    /** Groups and channels both have a title and members. */
    val isGroup get() = type == "group" || type == "channel"
    val isChannel get() = type == "channel"
    val isSaved get() = type == "saved"
    fun isAdmin(userId: Long?) = type == "direct" || type == "saved" || ownerId == userId || userId in admins
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
