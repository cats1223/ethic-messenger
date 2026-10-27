import Foundation

struct User: Codable, Identifiable, Hashable {
    let id: Int64
    var username: String = ""
    var displayName: String = ""
    var avatar: String?
    var banner: String?
    var role: String?
    var verified: Bool?
    var badge: String?
    var online: Bool?
    var lastSeen: Double?
    var email: String?

    var isStaff: Bool { role == "owner" || role == "dev" }
    var isVerified: Bool { verified ?? false }
    var isOnline: Bool { online ?? false }
}

struct ImageRef: Codable, Hashable {
    var url: String
    var w: Int?
    var h: Int?
}

struct FileRef: Codable, Hashable {
    var url: String
    var name: String?
    var size: Int64?
    var kind: String?
    var duration: Double?
}

struct ReplyRef: Codable, Hashable {
    var id: Int64
    var userId: Int64?
    var text: String?
    var kind: String?
    var fileName: String?
    var deleted: Bool?
}

struct Message: Codable, Identifiable, Hashable {
    var id: Int64
    var chatId: Int64
    var userId: Int64
    var text: String = ""
    var image: ImageRef?
    var file: FileRef?
    var replyTo: ReplyRef?
    var editedAt: Double?
    var createdAt: Double = 0
    // Local only: messages being sent.
    var clientId: String?
    var pending: Bool?
    var localImage: Data?

    enum CodingKeys: String, CodingKey { case id, chatId, userId, text, image, file, replyTo, editedAt, createdAt }

    var kind: String? { image != nil ? "photo" : file?.kind }
    var key: String { clientId ?? String(id) }
    var isPending: Bool { pending ?? false }
}

struct CallInfo: Codable, Hashable {
    var participants: [Int64] = []
    var video: Bool?
    var startedAt: Double?
}

struct Chat: Codable, Identifiable, Hashable {
    let id: Int64
    var type: String
    var title: String?
    var createdAt: Double = 0
    var members: [User] = []
    var lastMessage: Message?
    var unread: Int = 0
    var othersRead: Int64 = 0
    var call: CallInfo?
    var request: RequestRef?

    var isGroup: Bool { type == "group" }
    var sortKey: Double { lastMessage?.createdAt ?? createdAt }
}

struct RequestRef: Codable, Hashable { var from: Int64 }

struct RingEvent: Codable, Identifiable, Hashable {
    var chatId: Int64
    var from: User
    var video: Bool?
    var invite: Bool?
    var title: String?
    var id: Int64 { chatId }
}

func previewText(_ m: Message?) -> String {
    guard let m else { return "Нет сообщений" }
    if !m.text.isEmpty { return m.text }
    return kindText(m.kind, m.file?.name)
}

func kindText(_ kind: String?, _ name: String?) -> String {
    switch kind {
    case "photo": return "Фото"
    case "voice": return "Голосовое сообщение"
    case "video": return "Видео"
    case nil: return ""
    default: return name ?? "Файл"
    }
}
