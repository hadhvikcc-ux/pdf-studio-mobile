import SwiftUI

@main
struct PDFStudioApp: App {
    @StateObject private var store = DocStore()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                LibraryView()
            }
            .environmentObject(store)
            // "Open in…" from Mail, Files and cloud apps.
            .onOpenURL { url in
                if let doc = try? store.importFile(url) { store.pendingOpen = doc }
            }
        }
    }
}
