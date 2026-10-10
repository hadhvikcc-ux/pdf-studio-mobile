import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject var store: DocStore
    private enum Pick { case pdf, folder }
    @State private var pick: Pick?
    @State private var path: DocInfo?
    @State private var error: String?

    var body: some View {
        List {
            if store.docs.isEmpty {
                Text("empty_library").foregroundStyle(.secondary).padding(.vertical, 24)
            }
            ForEach(store.docs) { d in
                Button { path = d } label: {
                    VStack(alignment: .leading) {
                        Text(d.name).font(.headline)
                        Text(d.opened.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
                .swipeActions { Button("delete", role: .destructive) { store.delete(d.id) } }
            }
        }
        .navigationTitle("library")
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Menu("sync") {
                    Button("sync_choose_folder") { pick = .folder }
                    Button("sync_pull") { runSync { try store.syncPull() } }
                }
                Button("open_pdf") { pick = .pdf }
            }
        }
        // A single importer: stacking several .fileImporter modifiers on one view is unreliable.
        .fileImporter(
            isPresented: Binding(get: { pick != nil }, set: { if !$0 { pick = nil } }),
            allowedContentTypes: pick == .folder ? [.folder] : [.pdf]
        ) { result in
            let mode = pick
            switch result {
            case .success(let url):
                do {
                    if mode == .folder { try store.setSyncFolder(url) } else { path = try store.importFile(url) }
                } catch { self.error = error.localizedDescription }
            case .failure(let e): error = e.localizedDescription
            }
        }
        .navigationDestination(isPresented: Binding(get: { path != nil }, set: { if !$0 { path = nil } })) {
            if let p = path { ReaderView(info: p) }
        }
        .onChange(of: store.pendingOpen) { doc in
            if let doc { path = doc; store.pendingOpen = nil }
        }
        .alert("error", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("close", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    private func runSync(_ op: () throws -> Int) {
        do { _ = try op() } catch { self.error = error.localizedDescription }
    }
}
