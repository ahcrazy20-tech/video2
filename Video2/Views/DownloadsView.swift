import SwiftUI

struct DownloadsView: View {
    @EnvironmentObject var downloads: DownloadManager

    var body: some View {
        NavigationStack {
            List {
                if downloads.jobs.isEmpty {
                    Text("لا توجد مهام تحميل بعد.")
                        .foregroundStyle(.secondary)
                }
                ForEach(downloads.jobs) { job in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(job.media.title).font(.headline)
                        Text(statusAR(job)).font(.caption).foregroundStyle(color(job.state))
                        if job.state == .running {
                            ProgressView(value: job.progress)
                                .tint(V2Theme.mint)
                        }
                        if let err = job.errorMessage {
                            Text(err).font(.caption2).foregroundStyle(V2Theme.gold)
                        }
                        Text(job.media.extractionMethod).font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("التحميلات")
        }
    }

    private func statusAR(_ job: DownloadJob) -> String {
        switch job.state {
        case .queued: return "في الانتظار"
        case .running: return "جارٍ التحميل \(Int(job.progress * 100))٪"
        case .paused: return "متوقف"
        case .failed: return "فشل"
        case .completed: return "اكتمل — في المكتبة"
        case .blockedDRM: return "محظور بسبب DRM"
        }
    }

    private func color(_ s: DownloadState) -> Color {
        switch s {
        case .completed: return V2Theme.mint
        case .blockedDRM, .failed: return V2Theme.gold
        case .running: return V2Theme.accent
        default: return .secondary
        }
    }
}
