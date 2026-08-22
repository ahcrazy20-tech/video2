import SwiftUI

struct DownloadsView: View {
    @EnvironmentObject var downloads: DownloadManager
    @EnvironmentObject var lang: LanguageStore

    var body: some View {
        NavigationStack {
            List {
                if downloads.jobs.isEmpty {
                    Text(lang.t("dl.empty"))
                        .foregroundStyle(.secondary)
                }
                ForEach(downloads.jobs) { job in
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(job.media.title).font(.headline)
                            Text(status(job)).font(.caption).foregroundStyle(color(job.state))
                            if job.state == .running || (job.state == .paused && job.progress > 0) {
                                ProgressView(value: job.progress)
                                    .tint(V2Theme.mint)
                            }
                            if let err = job.errorMessage {
                                Text(err).font(.caption2).foregroundStyle(V2Theme.gold)
                            }
                            Text(job.media.extractionMethod).font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(spacing: 10) {
                            if job.state.isBusy {
                                Button {
                                    downloads.cancel(jobID: job.id)
                                } label: {
                                    Image(systemName: "pause.circle.fill")
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(lang.t("dl.pause"))
                            } else if job.state == .paused || job.state == .failed {
                                Button {
                                    downloads.resume(jobID: job.id)
                                } label: {
                                    Image(systemName: "play.circle.fill")
                                        .foregroundStyle(V2Theme.mint)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(lang.t("dl.resume"))
                            }
                            if job.state != .running {
                                Button(role: .destructive) {
                                    downloads.remove(jobID: job.id)
                                } label: {
                                    Image(systemName: "trash")
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(lang.t("dl.remove"))
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle(lang.t("tab.downloads"))
        }
    }

    private func status(_ job: DownloadJob) -> String {
        switch job.state {
        case .queued: return lang.t("dl.queued")
        case .running: return "\(lang.t("dl.running")) \(Int(job.progress * 100))%"
        case .paused: return lang.t("dl.paused")
        case .failed: return lang.t("dl.failed")
        case .completed: return lang.t("dl.done")
        case .blockedDRM: return lang.t("dl.drm")
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
