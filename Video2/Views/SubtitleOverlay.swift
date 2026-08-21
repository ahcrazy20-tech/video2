import SwiftUI
import AVFoundation

// MARK: - طبقة الترجمة فوق المشغّل

struct SubtitleOverlay: View {
    let text: String
    let fontSize: Int

    /// كل سطر في Text منفصل — يمنع تشقلب اتجاه النص عند خلط عربي/لاتيني (bidi)
    private var lines: [String] {
        text.components(separatedBy: "\n").filter { !$0.isEmpty }
    }

    var body: some View {
        VStack(spacing: 6) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(size: CGFloat(fontSize), weight: .semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .lineSpacing(4)
                    .foregroundColor(.white)
                    .environment(\.layoutDirection, startsRTL(line) ? .rightToLeft : .leftToRight)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.black.opacity(0.62))
        )
        .shadow(radius: 4)
        .padding(.horizontal, 24)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.12), value: text)
    }

    private func startsRTL(_ s: String) -> Bool {
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x0600...0x08FF, 0xFB50...0xFDFF, 0xFE70...0xFEFF:
                return true
            case 0x0041...0x005A, 0x0061...0x007A, 0x0030...0x0039:
                return false
            default:
                continue
            }
        }
        return false
    }
}

// MARK: - محرك مزامنة النص مع الزمن

enum SubtitleOverlayRenderer {

    static func cueText(_ cues: [SubCue], at time: Double) -> String? {
        guard !cues.isEmpty else { return nil }
        var lo = 0, hi = cues.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let c = cues[mid]
            if time < c.start { hi = mid - 1 }
            else if time >= c.end { lo = mid + 1 }
            else { return c.text }
        }
        return nil
    }

    static func text(original: [SubCue], translated: [SubCue],
                     mode: SubtitleDisplayMode, time: Double) -> String? {
        switch mode {
        case .off:
            return nil
        case .original:
            return cueText(original, at: time)
        case .translated:
            return cueText(translated, at: time)
        case .bilingual:
            let tr = cueText(translated, at: time)
            let or = cueText(original, at: time)
            if let tr = tr, let o = or { return tr + "\n" + o }
            return tr ?? or
        }
    }
}
