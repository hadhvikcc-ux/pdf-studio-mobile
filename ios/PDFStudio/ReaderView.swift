import SwiftUI
import PDFKit
import UniformTypeIdentifiers
import PhotosUI

/// Owns the PDFView so SwiftUI can drive it (navigation, selection, annotations, taps).
final class PDFController: NSObject, ObservableObject, UIGestureRecognizerDelegate {
    let view = PDFView()
    /// Called with the tap location in the PDFView's coordinate space.
    var onTap: ((CGPoint) -> Void)?

    override init() {
        super.init()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.maxScaleFactor = 6
        view.minScaleFactor = view.scaleFactorForSizeToFit
        view.backgroundColor = .systemBackground
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        view.addGestureRecognizer(tap)
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) { onTap?(g.location(in: view)) }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    var pageIndex: Int {
        guard let doc = view.document, let p = view.currentPage else { return 0 }
        return doc.index(for: p)
    }
}

struct PDFKitView: UIViewRepresentable {
    let controller: PDFController
    func makeUIView(context: Context) -> PDFView { controller.view }
    func updateUIView(_ uiView: PDFView, context: Context) {}
}

struct ReaderView: View {
    let info: DocInfo
    @EnvironmentObject var store: DocStore
    @StateObject private var pdf = PDFController()

    private enum Sheet: Identifiable { case search, pages, tools, versions, sync, info, share([Any])
        var id: String {
            switch self {
            case .search: return "search"
            case .pages: return "pages"
            case .tools: return "tools"
            case .versions: return "versions"
            case .sync: return "sync"
            case .info: return "info"
            case .share: return "share"
            }
        }
    }
    private enum Input: String, Identifiable { case split, reorder, protect; var id: String { rawValue } }

    @State private var sheet: Sheet?
    @State private var input: Input?
    @State private var inputText = ""
    @State private var locked = false
    @State private var password = ""
    @State private var bookmarks: Set<Int> = []
    @State private var drawing = false
    @State private var stroke: [CGPoint] = []
    @State private var paged = false
    @State private var night = false
    @State private var mergePicking = false
    @State private var message: String?
    @State private var version = 1
    @State private var dirty = false
    @State private var busy = false
    @State private var editing = false
    @State private var textTarget: TextTarget?
    @State private var pickingPhoto = false
    @State private var photoItem: PhotosPickerItem?
    @State private var imageSpot: (page: Int, point: CGPoint)?
    @State private var infoDraft = EditTools.Info()

    var body: some View {
        ZStack {
            PDFKitView(controller: pdf)
                .inverted(night)
                .ignoresSafeArea(edges: .bottom)
                .accessibilityLabel(info.name)
            if drawing { inkOverlay }
            if editing {
                Text("edit_hint").font(.footnote).padding(8).background(.regularMaterial, in: Capsule())
                    .frame(maxHeight: .infinity, alignment: .top).padding(.top, 8).allowsHitTesting(false)
            }
            if busy { ProgressView().controlSize(.large).padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) }
        }
        .navigationTitle(info.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { sheet = .search } label: { Image(systemName: "magnifyingglass") }
                    .accessibilityLabel(Text("search"))
                Menu {
                    Button(LocalizedStringKey(bookmarks.contains(pdf.pageIndex) ? "remove_bookmark" : "add_bookmark")) { toggleBookmark() }
                    Button("thumbnails_bookmarks") { sheet = .pages }
                    Button("tools") { sheet = .tools }
                    Button("edit_properties") { infoDraft = pdf.view.document.map(EditTools.readInfo) ?? EditTools.Info(); sheet = .info }
                    Button("versions") { sheet = .versions }
                    Button("sync") { sheet = .sync }
                    Toggle("night_mode", isOn: $night)
                    Toggle("paged_swipe", isOn: $paged)
                } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel(Text("tools"))
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Button("highlight") { highlightSelection() }
                Button(LocalizedStringKey(drawing ? "tool_none" : "tool_ink")) { drawing.toggle() }
                Button("add_note") { addNote() }
                Button(LocalizedStringKey(editing ? "edit_done" : "edit")) { editing.toggle(); if editing { drawing = false } }
                Spacer()
                if dirty { Button("save_annotations") { saveAnnotations() } }
            }
        }
        .onAppear {
            load()
            pdf.onTap = { handleTap($0) }
        }
        .onChange(of: paged) { on in
            pdf.view.displayMode = on ? .singlePage : .singlePageContinuous
            pdf.view.usePageViewController(on)
        }
        .sheet(item: $sheet) { sheetContent($0) }
        .sheet(item: $input) { op in inputSheet(op) }
        .sheet(item: $textTarget) { t in textSheet(t) }
        .photosPicker(isPresented: $pickingPhoto, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await MainActor.run {
                    photoItem = nil
                    if let data, let image = UIImage(data: data) { insertImage(image) }
                }
            }
        }
        .fileImporter(isPresented: $mergePicking, allowedContentTypes: [.pdf]) { r in
            if case .success(let url) = r { merge(url) }
        }
        .alert("password_required", isPresented: $locked) {
            SecureField("password", text: $password)
            Button("unlock") { unlock() }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("close", role: .cancel) {}
        }
    }

    // MARK: Loading

    private func load() {
        store.touch(info.id)
        let cur = store.current(info.id)
        version = cur.number
        bookmarks = store.bookmarks(info.id)
        guard let doc = PDFDocument(url: cur.url) else { message = String(localized: "error_open"); return }
        pdf.view.document = doc
        locked = doc.isLocked
        dirty = false
    }

    private func unlock() {
        guard let doc = pdf.view.document, doc.unlock(withPassword: password) else { locked = true; return }
        // Store a decrypted copy as the next version so later edits do not need the password.
        let next = store.nextVersion(info.id)
        if doc.write(to: next.url) { load() }
    }

    // MARK: Bookmarks

    private func toggleBookmark() { bookmarks = store.toggleBookmark(info.id, page: pdf.pageIndex) }

    // MARK: Annotations (real PDF annotations, so they open in other readers)

    private func highlightSelection() {
        guard let sel = pdf.view.currentSelection else { return }
        for line in sel.selectionsByLine() {
            for page in line.pages {
                let a = PDFAnnotation(bounds: line.bounds(for: page), forType: .highlight, withProperties: nil)
                a.color = UIColor.yellow.withAlphaComponent(0.4)
                page.addAnnotation(a)
            }
        }
        pdf.view.clearSelection()
        dirty = true
    }

    private func addNote() {
        guard let page = pdf.view.currentPage else { return }
        let box = page.bounds(for: .mediaBox)
        let a = PDFAnnotation(bounds: CGRect(x: box.minX + 24, y: box.maxY - 48, width: 24, height: 24), forType: .text, withProperties: nil)
        a.contents = String(localized: "note_default")
        a.color = .systemYellow
        page.addAnnotation(a)
        dirty = true
    }

    private var inkOverlay: some View {
        Color.clear
            .ignoresSafeArea(edges: .bottom)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { stroke.append($0.location) }
                    .onEnded { _ in commitStroke() }
            )
            .overlay(Path { p in
                guard let f = stroke.first else { return }
                p.move(to: f); stroke.dropFirst().forEach { p.addLine(to: $0) }
            }.stroke(Color.red, lineWidth: 3))
    }

    private func commitStroke() {
        defer { stroke = [] }
        guard stroke.count > 1, let first = stroke.first,
              let page = pdf.view.page(for: first, nearest: true) else { return }
        let pts = stroke.map { pdf.view.convert($0, to: page) }
        let path = UIBezierPath()
        let xs = pts.map(\.x), ys = pts.map(\.y)
        let bounds = CGRect(x: xs.min()! - 2, y: ys.min()! - 2, width: xs.max()! - xs.min()! + 4, height: ys.max()! - ys.min()! + 4)
        path.move(to: CGPoint(x: pts[0].x - bounds.minX, y: pts[0].y - bounds.minY))
        pts.dropFirst().forEach { path.addLine(to: CGPoint(x: $0.x - bounds.minX, y: $0.y - bounds.minY)) }
        let a = PDFAnnotation(bounds: bounds, forType: .ink, withProperties: nil)
        a.color = .red
        a.border = PDFBorder()
        a.border?.lineWidth = 2
        a.add(path)
        page.addAnnotation(a)
        dirty = true
    }

    private func saveAnnotations() {
        guard let doc = pdf.view.document else { return }
        let next = store.nextVersion(info.id)
        if doc.write(to: next.url) {
            message = String(format: String(localized: "saved_version"), next.number)
            load()
        }
    }

    // MARK: Page tools (each creates a new version)

    private func commit(_ doc: PDFDocument) {
        let next = store.nextVersion(info.id)
        if doc.write(to: next.url) {
            store.clearBookmarks(info.id)
            message = String(format: String(localized: "saved_version"), next.number)
            load()
        } else { message = String(localized: "error_open") }
    }

    /// Adds an invisible text layer to scanned pages. Runs off the main thread; Vision is slow on big scans.
    private func runOCR() {
        guard let doc = copyDocument() else { return }
        busy = true
        Task.detached(priority: .userInitiated) {
            let outcome = Result { try OCR.makeSearchable(doc) { _, _ in } }
            await MainActor.run {
                busy = false
                switch outcome {
                case .failure(let e): message = e.localizedDescription
                case .success(nil): message = String(localized: "ocr_nothing")
                case .success(let r?):
                    let next = store.nextVersion(info.id)
                    do {
                        try r.data.write(to: next.url)
                        message = String(format: String(localized: "ocr_done"), r.pages, next.number)
                        load()
                    } catch { message = error.localizedDescription }
                }
            }
        }
    }

    private func copyDocument() -> PDFDocument? { PDFDocument(url: store.current(info.id).url) }

    private func merge(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let base = copyDocument(), let other = PDFDocument(url: url) else { return }
        for i in 0..<other.pageCount { if let p = other.page(at: i)?.copy() as? PDFPage { base.insert(p, at: base.pageCount) } }
        commit(base)
    }

    private func apply(_ op: Input, _ text: String) {
        guard let src = copyDocument() else { return }
        do {
            switch op {
            case .reorder:
                let order = try PageRanges.order(text, pageCount: src.pageCount)
                let out = PDFDocument()
                for (i, idx) in order.enumerated() { if let p = src.page(at: idx)?.copy() as? PDFPage { out.insert(p, at: i) } }
                commit(out)
            case .split:
                let groups = try PageRanges.ranges(text, pageCount: src.pageCount)
                for (n, g) in groups.enumerated() {
                    let out = PDFDocument()
                    for (i, idx) in g.enumerated() { if let p = src.page(at: idx)?.copy() as? PDFPage { out.insert(p, at: i) } }
                    let base = (info.name as NSString).deletingPathExtension
                    try store.importData(name: "\(base)_part\(n + 1).pdf") { dst in _ = out.write(to: dst) }
                }
                message = String(format: String(localized: "split_saved"), groups.count)
            case .protect:
                let next = store.nextVersion(info.id)
                let ok = src.write(to: next.url, withOptions: [.userPasswordOption: text, .ownerPasswordOption: text])
                if ok { message = String(format: String(localized: "saved_version"), next.number); load() }
            }
        } catch { message = error.localizedDescription }
    }

    // MARK: Content editing

    private func handleTap(_ viewPoint: CGPoint) {
        guard editing, !drawing, let page = pdf.view.page(for: viewPoint, nearest: false) else { return }
        let pt = pdf.view.convert(viewPoint, to: page)
        if let annot = page.annotation(at: pt), annot.type?.contains("FreeText") == true {
            var style = EditTools.TextStyle.infer(fontName: annot.font?.fontName ?? "", size: annot.font?.pointSize ?? 14, color: annot.fontColor ?? .black)
            if style.size < 1 { style.size = 14 }
            textTarget = TextTarget(page: page, kind: .annotation(annot), initial: annot.contents ?? "", style: style)
        } else if let line = page.selectionForLine(at: pt), let text = line.string?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
            let bounds = line.bounds(for: page)
            // Start from the document's own font so the replacement looks like the original.
            textTarget = TextTarget(page: page, kind: .line(bounds), initial: text, style: EditTools.style(of: line, fallbackSize: max(6, bounds.height * 0.8)))
        } else {
            textTarget = TextTarget(page: page, kind: .new(pt), initial: "", style: EditTools.TextStyle())
        }
    }

    private func applyText(_ t: TextTarget, _ text: String, style: EditTools.TextStyle) {
        switch t.kind {
        case .annotation(let a):
            a.contents = text
            a.font = style.uiFont
            a.fontColor = style.color
        case .line(let bounds):
            EditTools.replaceLine(on: t.page, bounds: bounds, with: text, style: style)
        case .new(let point):
            EditTools.addText(text, at: point, style: style, on: t.page)
        }
        dirty = true
        // Save straight away so the user sees the result in a new version instead of hunting for a save button.
        saveAnnotations()
        if case .line = t.kind { message = String(localized: "edit_covered_note") }
    }

    private func insertImage(_ image: UIImage) {
        guard let spot = imageSpot, let doc = pdf.view.document else { return }
        let data = EditTools.insertImage(image, in: doc, pageIndex: spot.page, centre: spot.point)
        let next = store.nextVersion(info.id)
        do {
            try data.write(to: next.url)
            message = String(format: String(localized: "saved_version"), next.number)
            load()
        } catch { message = error.localizedDescription }
        imageSpot = nil
    }

    private func saveInfo() {
        guard let doc = pdf.view.document else { return }
        EditTools.writeInfo(infoDraft, to: doc)
        let next = store.nextVersion(info.id)
        sheet = nil
        if doc.write(to: next.url) {
            message = String(format: String(localized: "saved_version"), next.number)
            load()
        } else { message = String(localized: "error_open") }
    }

    private func textSheet(_ t: TextTarget) -> some View {
        TextEditSheet(
            target: t.kind, initial: t.initial, initialStyle: t.style,
            onApply: { text, style in textTarget = nil; applyText(t, text, style: style) },
            onAddImage: {
                if case .new(let p) = t.kind, let doc = pdf.view.document {
                    imageSpot = (doc.index(for: t.page), p)
                    textTarget = nil
                    pickingPhoto = true
                }
            },
            onCancel: { textTarget = nil }
        )
        .presentationDetents([.medium, .large])
    }

    // MARK: Sheets

    @ViewBuilder private func sheetContent(_ s: Sheet) -> some View {
        switch s {
        case .search: SearchSheet(pdf: pdf) { sheet = nil }
        case .pages: PagesSheet(pdf: pdf, bookmarks: bookmarks) { sheet = nil }
        case .versions:
            NavigationStack {
                List(Array(store.versions(info.id).reversed()), id: \.number) { v in
                    HStack {
                        Text(String(format: String(localized: "version_n"), v.number))
                        if v.number == version { Text("current").foregroundStyle(.secondary) }
                        Spacer()
                        if v.number != version {
                            Button("restore") { _ = try? store.restore(info.id, version: v.number); sheet = nil; load() }
                        }
                    }
                }
                .navigationTitle("versions")
            }
        case .tools:
            NavigationStack {
                List {
                    Button("merge") { sheet = nil; mergePicking = true }
                    Button("split") { sheet = nil; input = .split }
                    Button("reorder") { sheet = nil; input = .reorder }
                    Button("protect") { sheet = nil; input = .protect }
                    Button("ocr") { sheet = nil; runOCR() }
                    Button("share_pdf") { sheet = .share([store.current(info.id).url]) }
                    Button("share_png") { sharePagePNG() }
                }
                .navigationTitle("tools")
            }
        case .sync:
            NavigationStack {
                List {
                    if !store.hasSyncFolder { Text("sync_none").foregroundStyle(.secondary) }
                    Button("sync_push") {
                        sheet = nil
                        do { message = String(format: String(localized: "sync_done"), try store.syncPush(info.id)) } catch { message = error.localizedDescription }
                    }
                    Button("sync_pull") {
                        sheet = nil
                        do { message = String(format: String(localized: "sync_done"), try store.syncPull()) } catch { message = error.localizedDescription }
                    }
                }
                .navigationTitle("sync")
            }
        case .info:
            NavigationStack {
                Form {
                    TextField("meta_title", text: $infoDraft.title)
                    TextField("meta_author", text: $infoDraft.author)
                    TextField("meta_subject", text: $infoDraft.subject)
                    TextField("meta_keywords", text: $infoDraft.keywords)
                }
                .navigationTitle("edit_properties")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("cancel") { sheet = nil } }
                    ToolbarItem(placement: .confirmationAction) { Button("apply") { saveInfo() } }
                }
            }
        case .share(let items): ActivityView(items: items)
        }
    }

    private func inputSheet(_ op: Input) -> some View {
        NavigationStack {
            Form {
                if op == .protect { SecureField("password", text: $inputText) }
                else { TextField(LocalizedStringKey(op == .split ? "ranges_hint" : "order_hint"), text: $inputText) }
            }
            .navigationTitle(LocalizedStringKey(op.rawValue))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("cancel") { input = nil; inputText = "" } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("apply") { let t = inputText; input = nil; inputText = ""; apply(op, t) }.disabled(inputText.isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func sharePagePNG() {
        guard let page = pdf.view.currentPage else { return }
        let img = page.thumbnail(of: CGSize(width: 1600, height: 1600 * page.bounds(for: .mediaBox).height / max(page.bounds(for: .mediaBox).width, 1)), for: .mediaBox)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("page_\(pdf.pageIndex + 1).png")
        try? img.pngData()?.write(to: url)
        sheet = .share([url])
    }
}

private extension View {
    @ViewBuilder func inverted(_ on: Bool) -> some View { if on { self.colorInvert() } else { self } }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

private struct SearchSheet: View {
    let pdf: PDFController
    let close: () -> Void
    @State private var q = ""
    @State private var results: [PDFSelection] = []

    var body: some View {
        NavigationStack {
            List(Array(results.enumerated()), id: \.offset) { _, sel in
                Button {
                    pdf.view.setCurrentSelection(sel, animate: true)
                    pdf.view.go(to: sel)
                    close()
                } label: {
                    let page = sel.pages.first.flatMap { pdf.view.document?.index(for: $0) } ?? 0
                    Text("\(page + 1): \(sel.string ?? "")")
                }
            }
            .overlay { if results.isEmpty && !q.isEmpty { Text("no_results").foregroundStyle(.secondary) } }
            .searchable(text: $q, prompt: Text("search_hint"))
            .onSubmit(of: .search) {
                results = pdf.view.document?.findString(q, withOptions: .caseInsensitive) ?? []
            }
            .navigationTitle("search")
        }
    }
}

private struct PagesSheet: View {
    let pdf: PDFController
    let bookmarks: Set<Int>
    let close: () -> Void
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack {
                Picker("", selection: $tab) {
                    Text("thumbnails").tag(0)
                    Text("bookmarks").tag(1)
                }.pickerStyle(.segmented).padding(.horizontal)
                let count = pdf.view.document?.pageCount ?? 0
                ScrollView {
                    if tab == 0 {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
                            ForEach(0..<count, id: \.self) { i in
                                Button { go(i) } label: {
                                    VStack {
                                        if let p = pdf.view.document?.page(at: i) {
                                            Image(uiImage: p.thumbnail(of: CGSize(width: 120, height: 160), for: .mediaBox))
                                                .resizable().scaledToFit()
                                        }
                                        Text("\(i + 1)").font(.caption)
                                    }
                                }
                                .accessibilityLabel(Text("Page \(i + 1)"))
                            }
                        }.padding()
                    } else {
                        if bookmarks.isEmpty { Text("no_bookmarks").foregroundStyle(.secondary).padding() }
                        ForEach(bookmarks.sorted(), id: \.self) { i in
                            Button("Page \(i + 1)") { go(i) }.padding(.vertical, 8)
                        }
                    }
                }
            }
            .navigationTitle("thumbnails_bookmarks")
        }
    }

    private func go(_ i: Int) {
        if let p = pdf.view.document?.page(at: i) { pdf.view.go(to: p) }
        close()
    }
}

/// What the user tapped in Edit mode.
private enum TextKind { case annotation(PDFAnnotation), line(CGRect), new(CGPoint) }

private struct TextTarget: Identifiable {
    let id = UUID()
    let page: PDFPage
    let kind: TextKind
    let initial: String
    let style: EditTools.TextStyle
}

private struct TextEditSheet: View {
    let target: TextKind
    let initial: String
    let initialStyle: EditTools.TextStyle
    let onApply: (String, EditTools.TextStyle) -> Void
    let onAddImage: () -> Void
    let onCancel: () -> Void
    @State private var text = ""
    @State private var style = EditTools.TextStyle()

    var body: some View {
        NavigationStack {
            Form {
                TextField("edit_text_hint", text: $text, axis: .vertical)
                    .font(Font(style.uiFont as CTFont))
                Section {
                    Picker("edit_font", selection: $style.family) {
                        Text("font_sans").tag(EditTools.TextStyle.Family.sans)
                        Text("font_serif").tag(EditTools.TextStyle.Family.serif)
                        Text("font_mono").tag(EditTools.TextStyle.Family.mono)
                    }
                    .pickerStyle(.segmented)
                    Toggle("font_bold", isOn: $style.bold)
                    Toggle("font_italic", isOn: $style.italic)
                    Stepper(value: $style.size, in: 6...96) { Text("edit_font_size") + Text(": \(Int(style.size))") }
                }
                if case .new = target {
                    Button("edit_add_image") { onAddImage() }
                }
                if case .line = target {
                    Text("edit_covered_note").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(LocalizedStringKey(title))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("apply") { onApply(text, style) }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .onAppear {
            text = initial
            style = initialStyle
        }
    }

    private var title: String {
        if case .new = target { return "edit_add_text" }
        return "edit_text"
    }
}
