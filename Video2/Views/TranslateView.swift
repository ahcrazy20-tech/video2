import SwiftUI
import AVFoundation
// MARK: - شاشة مهام الترجمة
struct TranslateView: View {
    @EnvironmentObject var translations: TranslationManager
    @EnvironmentObject var library: LibraryStore
    @State private var showNewJob = false
    var body: some View {
        NavigationStack {
            Group {
                if translations.jobs.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "captions.bubble")
                            .font(.system(size: 48))
                            .foregroundStyle(V2Theme.gold)
                        Text("لا توجد مهام ترجمة")
                            .font(.title3.bold())
                        Text("اختر فيديو من المكتبة وسيقوم التطبيق بتفريغ كلامه وترجمته إلى ملف ترجمة كامل يظهر فوق المشغّل — حتى الفيديوهات الطويلة جداً.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 28)
                        Button {
                            showNewJob = true
                        } label: {
                            Label("ترجمة فيديو جديد", systemImage: "plus.circle.fill")
                                .font(.headline)
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 6)
                    }
                } else {
                    List {
                        ForEach(translations.jobs) { job in
                            TranslationJobRow(job: job)
                        }
                        .onDelete { indexSet in
                            for i in indexSet.sorted(by: >) {
                                translations.delete(translations.jobs[i].id)
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .background(V2Theme.bg)
            .navigationTitle("الترجمة")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showNewJob = true
                    } label: {
                        Label("مهمة جديدة", systemImage: "plus")
                    }
                    .disabled(library.videos.isEmpty)
                }
            }
            .sheet(isPresented: $showNewJob) {
                NewTranslationView(preselected: nil)
                    .environmentObject(translations)
                    .environmentObject(library)
            }
        }
    }
}
