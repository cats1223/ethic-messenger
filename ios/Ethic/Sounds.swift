import AVFoundation
import AudioToolbox

/// Synthesized sounds, the same shapes as on the website and in the Android app.
struct Note {
    var at: Double, freq: Double, dur: Double
    var wave = "sine", vol = 0.15, to: Double? = nil, attack = 0.015
}

struct Ringtone: Identifiable {
    let id: String, name: String, period: Double, notes: [Note]?
}

@MainActor
final class Sounds {
    static let shared = Sounds()
    private let rate = 44100.0

    let ringtones: [Ringtone] = [
        Ringtone(id: "classic", name: "Классика", period: 2.0, notes: [Note(at: 0, freq: 880, dur: 0.22), Note(at: 0.25, freq: 660, dur: 0.22)]),
        Ringtone(id: "marimba", name: "Маримба", period: 2.4, notes: [659, 784, 988, 1319, 988, 1319].enumerated().map {
            Note(at: Double($0.offset) * 0.13, freq: Double($0.element), dur: 0.35, wave: "triangle", vol: 0.2, attack: 0.005) }),
        Ringtone(id: "soft", name: "Нежный", period: 2.6, notes: [523, 659, 784].map { Note(at: 0, freq: Double($0), dur: 1.1, vol: 0.07, attack: 0.25) }),
        Ringtone(id: "digital", name: "Цифровой", period: 1.6, notes: (0..<4).map { Note(at: Double($0) * 0.12, freq: 1250, dur: 0.07, wave: "square", vol: 0.05, attack: 0.003) }),
        Ringtone(id: "retro", name: "Старый телефон", period: 3.0, notes: (0..<20).map { Note(at: Double($0) * 0.05, freq: $0 % 2 == 1 ? 480 : 440, dur: 0.05, wave: "square", vol: 0.04, attack: 0.002) }),
        Ringtone(id: "none", name: "Без звука", period: 2.0, notes: []),
    ]

    var ringtoneId: String {
        get { UserDefaults.standard.string(forKey: "ringtone") ?? "classic" }
        set { UserDefaults.standard.set(newValue, forKey: "ringtone") }
    }
    var messageSounds: Bool {
        get { UserDefaults.standard.object(forKey: "msgSound") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "msgSound") }
    }

    private var cache: [String: Data] = [:]
    private var oneShots: [AVAudioPlayer] = []
    private var looping: AVAudioPlayer?
    private var vibrateTask: Task<Void, Never>?

    private func wav(_ key: String, _ notes: [Note], _ length: Double) -> Data {
        if let d = cache[key] { return d }
        let n = Int(length * rate)
        var buf = [Double](repeating: 0, count: n)
        for note in notes {
            let start = Int(note.at * rate), len = Int((note.dur + 0.05) * rate)
            var phase = 0.0
            for i in 0..<len {
                let idx = start + i
                if idx >= n { break }
                let t = Double(i) / rate
                let f = note.to.map { note.freq * exp(log($0 / note.freq) * min(1, t / note.dur)) } ?? note.freq
                phase += 2 * .pi * f / rate
                let s: Double
                switch note.wave {
                case "square": s = sin(phase) >= 0 ? 1 : -1
                case "triangle": s = 2 / .pi * asin(sin(phase))
                default: s = sin(phase)
                }
                let env: Double
                if t < note.attack { env = 0.0001 * pow(note.vol / 0.0001, t / note.attack) }
                else if t < note.dur { env = note.vol * pow(0.0001 / note.vol, (t - note.attack) / (note.dur - note.attack)) }
                else { env = 0 }
                buf[idx] += s * env
            }
        }
        var data = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8)); u32(UInt32(36 + n * 2)); data.append(contentsOf: Array("WAVEfmt ".utf8))
        u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        data.append(contentsOf: Array("data".utf8)); u32(UInt32(n * 2))
        for v in buf { u16(UInt16(bitPattern: Int16(max(-1, min(1, v * 2.2)) * 32767))) }
        cache[key] = data
        return data
    }

    private func play(_ key: String, _ notes: [Note], _ length: Double) {
        guard let p = try? AVAudioPlayer(data: wav(key, notes, length)) else { return }
        oneShots.removeAll { !$0.isPlaying }
        oneShots.append(p)
        p.play()
    }

    func sent() { if messageSounds { play("sent", [Note(at: 0, freq: 320, dur: 0.14, vol: 0.07, to: 1400, attack: 0.01)], 0.25) } }
    func received() {
        if messageSounds { play("recv", [Note(at: 0, freq: 988, dur: 0.12, wave: "triangle", vol: 0.12, attack: 0.004), Note(at: 0.09, freq: 1319, dur: 0.18, wave: "triangle", vol: 0.1, attack: 0.004)], 0.35) }
    }
    func join() { play("join", [Note(at: 0, freq: 587, dur: 0.16, wave: "triangle", vol: 0.14, attack: 0.005), Note(at: 0.11, freq: 880, dur: 0.24, wave: "triangle", vol: 0.14, attack: 0.005)], 0.4) }
    func leave() { play("leave", [Note(at: 0, freq: 880, dur: 0.16, wave: "triangle", vol: 0.12, attack: 0.005), Note(at: 0.11, freq: 523, dur: 0.26, wave: "triangle", vol: 0.12, attack: 0.005)], 0.42) }

    private func loop(_ key: String, _ notes: [Note], _ period: Double) {
        stop()
        guard !notes.isEmpty, let p = try? AVAudioPlayer(data: wav(key, notes, period)) else { return }
        p.numberOfLoops = -1
        p.play()
        looping = p
    }

    /// Incoming call: ringtone + vibration.
    func ring(_ id: String? = nil, preview: Bool = false) {
        let r = ringtones.first { $0.id == (id ?? ringtoneId) } ?? ringtones[0]
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        loop("ring-\(r.id)", r.notes ?? [], r.period)
        if !preview {
            vibrateTask = Task {
                while !Task.isCancelled {
                    AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                    try? await Task.sleep(nanoseconds: 1_400_000_000)
                }
            }
        }
    }

    func ringback() { loop("ringback", [Note(at: 0, freq: 425, dur: 1.0, vol: 0.06, attack: 0.02)], 4.0) }

    func stop() {
        looping?.stop(); looping = nil
        vibrateTask?.cancel(); vibrateTask = nil
    }
}
