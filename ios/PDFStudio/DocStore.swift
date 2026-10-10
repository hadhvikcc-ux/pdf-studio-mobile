import Foundation
import PDFKit

struct DocInfo: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var opened: Date
}

/// Local, offline-first store. Each document lives in Documents/docs/<id>/ as immutable versions
/// v1.pdf, v2.pdf, ... Edits create the next version, which gives history and restore for free.
final class DocStore: ObservableObject {
    @Published private(set) var docs: [DocInfo] = []
    @Published var pendingOpen: DocInfo?

    private let root: URL
    private let defaults = UserDefaults.standard

    init() {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("docs", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        load()
    }

    private func dir(_ id: String) -> URL { root.appendingPathComponent(id, isDirectory: true) }

    private func load() {
        let data = defaults.data(forKey: "recents") ?? Data()
        let list = (try? JSONDecoder().decode([DocInfo].self, from: data)) ?? []
        docs = list.filter { FileManager.default.fileExists(atPath: dir($0.id).path) }.sorted { $0.opened > $1.opened }
    }

    private func save() {
        defaults.set(try? JSONEncoder().encode(docs), forKey: "recents")
    }

    // MARK: Library

    @discardableResult
    func importFile(_ url: URL) throws -> DocInfo {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try importData(name: url.lastPathComponent) { dst in try FileManager.default.copyItem(at: url, to: dst) }
    }

    @discardableResult
    func importData(name: String, write: (URL) throws -> Void) throws -> DocInfo {
        let id = UUID().uuidString
        try FileManager.default.createDirectory(at: dir(id), withIntermediateDirectories: true)
        try write(dir(id).appendingPathComponent("v1.pdf"))
        let info = DocInfo(id: id, name: name, opened: Date())
        docs.insert(info, at: 0)
        save()
        return info
    }

    func touch(_ id: String) {
        if let i = docs.firstIndex(where: { $0.id == id }) { docs[i].opened = Date(); save() }
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: dir(id))
        defaults.removeObject(forKey: "bm_\(id)")
        docs.removeAll { $0.id == id }
        save()
    }

    func name(_ id: String) -> String { docs.first { $0.id == id }?.name ?? "document.pdf" }

    // MARK: Versions

    struct Version: Identifiable {
        let number: Int
        let url: URL
        var id: Int { number }
    }

    func versions(_ id: String) -> [Version] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir(id), includingPropertiesForKeys: nil)) ?? []
        return files.compactMap { f -> Version? in
            let n = f.lastPathComponent
            guard n.hasPrefix("v"), n.hasSuffix(".pdf"), let v = Int(n.dropFirst().dropLast(4)) else { return nil }
            return Version(number: v, url: f)
        }.sorted { $0.number < $1.number }
    }

    func current(_ id: String) -> Version { versions(id).last! }

    func nextVersion(_ id: String) -> (number: Int, url: URL) {
        let n = current(id).number + 1
        return (n, dir(id).appendingPathComponent("v\(n).pdf"))
    }

    func restore(_ id: String, version: Int) throws -> Int {
        let next = nextVersion(id)
        guard let src = versions(id).first(where: { $0.number == version }) else { return next.number - 1 }
        try FileManager.default.copyItem(at: src.url, to: next.url)
        return next.number
    }

    // MARK: Bookmarks

    func bookmarks(_ id: String) -> Set<Int> { Set(defaults.array(forKey: "bm_\(id)") as? [Int] ?? []) }

    func toggleBookmark(_ id: String, page: Int) -> Set<Int> {
        var s = bookmarks(id)
        if s.contains(page) { s.remove(page) } else { s.insert(page) }
        defaults.set(Array(s), forKey: "bm_\(id)")
        return s
    }

    func clearBookmarks(_ id: String) { defaults.removeObject(forKey: "bm_\(id)") }

    // MARK: Cloud folder sync (Files providers: iCloud Drive, Google Drive, OneDrive, Dropbox)

    var hasSyncFolder: Bool { defaults.data(forKey: "sync_bookmark") != nil }

    func setSyncFolder(_ url: URL) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        defaults.set(try url.bookmarkData(), forKey: "sync_bookmark")
    }

    private func withSyncFolder<T>(_ body: (URL) throws -> T) throws -> T? {
        guard let data = defaults.data(forKey: "sync_bookmark") else { return nil }
        var stale = false
        let url = try URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale)
        guard url.startAccessingSecurityScopedResource() else { return nil }
        defer { url.stopAccessingSecurityScopedResource() }
        return try body(url)
    }

    /// Uploads the current version of a document. Returns the number of files written.
    func syncPush(_ id: String) throws -> Int {
        let src = current(id).url
        let dstName = name(id)
        return try withSyncFolder { folder in
            let dst = folder.appendingPathComponent(dstName)
            var err: NSError?
            var copyError: Error?
            NSFileCoordinator().coordinate(writingItemAt: dst, options: .forReplacing, error: &err) { url in
                do {
                    try? FileManager.default.removeItem(at: url)
                    try FileManager.default.copyItem(at: src, to: url)
                } catch { copyError = error }
            }
            if let e = err ?? copyError { throw e }
            return 1
        } ?? 0
    }

    /// Imports PDFs not yet in the library. Existing names are imported as "<name> (remote)" rather than overwritten.
    func syncPull() throws -> Int {
        let existing = Set(docs.map(\.name))
        return try withSyncFolder { folder in
            let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            var n = 0
            for f in files where f.pathExtension.lowercased() == "pdf" {
                var label = f.lastPathComponent
                if existing.contains(label) { label = f.deletingPathExtension().lastPathComponent + " (remote).pdf" }
                var err: NSError?
                var copyError: Error?
                NSFileCoordinator().coordinate(readingItemAt: f, options: [], error: &err) { url in
                    do { try importData(name: label) { dst in try FileManager.default.copyItem(at: url, to: dst) }; n += 1 }
                    catch { copyError = error }
                }
                if let e = err ?? copyError { throw e }
            }
            return n
        } ?? 0
    }
}
