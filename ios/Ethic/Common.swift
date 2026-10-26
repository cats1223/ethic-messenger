import SwiftUI
import UIKit

let blue = Color(red: 0.114, green: 0.608, blue: 0.941)
let green = Color(red: 0.137, green: 0.647, blue: 0.353)
let red = Color(red: 0.929, green: 0.259, blue: 0.271)
let surface1 = Color(white: 0.06)
let surface2 = Color(white: 0.11)
let surface3 = Color(white: 0.16)
let muted = Color(white: 0.6)

private func rgb(_ v: Int) -> Color {
    let r = Double((v >> 16) & 0xFF) / 255
    let g = Double((v >> 8) & 0xFF) / 255
    let b = Double(v & 0xFF) / 255
    return Color(red: r, green: g, blue: b)
}
private let avatarColors: [Color] = [0x5865F2, 0x3BA55C, 0xFAA61A, 0xED4245, 0xEB459E, 0x1ABC9C, 0x9B59B6, 0x607D8B].map(rgb)
func colorOf(_ id: Int64) -> Color { avatarColors[Int(abs(id) % Int64(avatarColors.count))] }
func initials(_ name: String) -> String {
    let parts = name.split(separator: " ").prefix(2)
    let s = parts.compactMap { $0.first.map(String.init) }.joined().uppercased()
    return s.isEmpty ? "?" : s
}
func hexColor(_ s: String) -> Color? {
    guard s.hasPrefix("#"), s.count == 7, let v = Int(s.dropFirst(), radix: 16) else { return nil }
    return rgb(v)
}

struct AvatarView: View {
    let user: User?
    var size: CGFloat = 44
    var showOnline = false
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if let url = API.abs(user?.avatar) {
                    AsyncImage(url: url) { img in img.resizable().scaledToFill() } placeholder: { surface3 }
                } else {
                    ZStack {
                        colorOf(user?.id ?? 0)
                        Text(initials(user?.displayName ?? "?")).font(.system(size: size * 0.38, weight: .bold)).foregroundStyle(.white)
                    }
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            if showOnline, user?.isOnline == true {
                Circle().fill(green).frame(width: size * 0.26, height: size * 0.26)
                    .overlay(Circle().stroke(.black, lineWidth: size * 0.05))
            }
        }
    }
}

struct ChatAvatar: View {
    let chat: Chat
    var size: CGFloat = 52
    var body: some View {
        if chat.isGroup {
            ZStack {
                colorOf(chat.id)
                if let t = chat.title, !t.isEmpty {
                    Text(initials(t)).font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(.white)
                } else { Image(systemName: "person.3.fill").foregroundStyle(.white) }
            }
            .frame(width: size, height: size).clipShape(Circle())
        } else {
            AvatarView(user: Store.shared.other(chat), size: size, showOnline: true)
        }
    }
}

/// Blue circle with a white tick.
struct Verified: View {
    var size: CGFloat = 15
    var body: some View {
        Image(systemName: "checkmark.seal.fill")
            .symbolRenderingMode(.palette)
            .foregroundStyle(.white, blue)
            .font(.system(size: size))
    }
}

struct NameLine: View {
    let name: String
    let user: User?
    var font: Font = .body.weight(.semibold)
    var body: some View {
        HStack(spacing: 4) {
            Text(name).font(font).lineLimit(1)
            if user?.isVerified == true { Verified() }
        }
    }
}

struct BadgeLabel: View {
    let user: User?
    var body: some View {
        if let b = user?.badge {
            Text(b).font(.caption2.weight(.bold)).foregroundStyle(.black)
                .padding(.horizontal, 6).padding(.vertical, 1)
                .background(.white, in: RoundedRectangle(cornerRadius: 6))
        }
    }
}

struct BannerView: View {
    let user: User?
    var body: some View {
        if let b = user?.banner, let c = hexColor(b) {
            c
        } else if let b = user?.banner, let url = API.abs(b) {
            AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { surface3 }
        } else {
            LinearGradient(colors: [colorOf(user?.id ?? 0), surface2], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
}

extension View {
    /// Liquid Glass on iOS 26, a blurred material before that.
    func glass(tint: Color? = nil) -> some View { glass(Capsule(), tint: tint) }

    @ViewBuilder
    func glass<S: Shape>(_ shape: S, tint: Color? = nil) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(tint.map { .regular.tint($0).interactive() } ?? .regular.interactive(), in: shape)
        } else {
            self.background(tint ?? Color.clear).background(.ultraThinMaterial, in: shape)
        }
    }
}

// MARK: formatting
private let ru = Locale(identifier: "ru_RU")
private func fmt(_ f: String, _ ms: Double) -> String {
    let d = DateFormatter(); d.locale = ru; d.dateFormat = f
    return d.string(from: Date(timeIntervalSince1970: ms / 1000))
}
func timeOf(_ ms: Double) -> String { fmt("HH:mm", ms) }
func shortWhen(_ ms: Double) -> String {
    let date = Date(timeIntervalSince1970: ms / 1000)
    let cal = Calendar.current
    if cal.isDateInToday(date) { return timeOf(ms) }
    if cal.isDateInYesterday(date) { return "вчера" }
    if Date().timeIntervalSince(date) < 6 * 86400 { return fmt("EE", ms) }
    return fmt("d MMM", ms)
}
func dayLabel(_ ms: Double) -> String {
    let date = Date(timeIntervalSince1970: ms / 1000)
    if Calendar.current.isDateInToday(date) { return "Сегодня" }
    if Calendar.current.isDateInYesterday(date) { return "Вчера" }
    return fmt("d MMMM yyyy", ms)
}
func sameDay(_ a: Double, _ b: Double) -> Bool {
    Calendar.current.isDate(Date(timeIntervalSince1970: a / 1000), inSameDayAs: Date(timeIntervalSince1970: b / 1000))
}
func lastSeenText(_ u: User?) -> String {
    guard let u else { return "" }
    if u.isOnline { return "в сети" }
    guard let ls = u.lastSeen else { return "не в сети" }
    let ago = Int((Date().timeIntervalSince1970 * 1000 - ls) / 60000)
    if ago < 1 { return "был(а) только что" }
    if ago < 60 { return "был(а) \(ago) мин назад" }
    if Calendar.current.isDateInToday(Date(timeIntervalSince1970: ls / 1000)) { return "был(а) в \(timeOf(ls))" }
    return "был(а) \(shortWhen(ls)) в \(timeOf(ls))"
}
func sizeText(_ b: Int64) -> String {
    if b < 1024 { return "\(b) Б" }
    if b < 1024 * 1024 { return "\(b / 1024) КБ" }
    return String(format: "%.1f МБ", Double(b) / 1024 / 1024)
}
func durText(_ s: Double?) -> String { let t = Int(s ?? 0); return String(format: "%d:%02d", t / 60, t % 60) }

/// Scales a picked image down and saves it as JPEG. Returns (file, width, height).
func prepareImage(_ data: Data, maxSide: CGFloat = 2560, aspect: CGFloat? = nil) -> (URL, Int, Int)? {
    guard var img = UIImage(data: data) else { return nil }
    if let aspect {
        let w = img.size.width, h = img.size.height
        var cw = w, ch = w / aspect
        if ch > h { ch = h; cw = h * aspect }
        let rect = CGRect(x: (w - cw) / 2, y: (h - ch) / 2, width: cw, height: ch)
        img = UIGraphicsImageRenderer(size: rect.size).image { _ in img.draw(at: CGPoint(x: -rect.minX, y: -rect.minY)) }
    }
    let scale = min(1, maxSide / max(img.size.width, img.size.height))
    let size = CGSize(width: (img.size.width * scale).rounded(), height: (img.size.height * scale).rounded())
    let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
    let out = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
    guard let jpg = out.jpegData(compressionQuality: 0.88) else { return nil }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("img-\(UUID().uuidString).jpg")
    try? jpg.write(to: url)
    return (url, Int(size.width), Int(size.height))
}
