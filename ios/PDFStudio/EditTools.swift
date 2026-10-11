import PDFKit
import UIKit

/// Content editing on top of PDFKit.
///
/// PDFKit exposes annotations, not page content, so existing text and images cannot be rewritten in place.
/// - Adding text uses standard FreeText annotations, which stay editable in other PDF readers.
/// - Editing a line of text covers the old line with a white box and writes the new text over it. THE OLD TEXT
///   STAYS IN THE FILE and can still be selected or extracted, so this is not a way to remove sensitive content.
/// - Inserting an image rebuilds the document through Core Graphics, which flattens existing annotations into the
///   page (they stay visible but are no longer editable).
enum EditTools {
    /// Font, weight, size and colour of a piece of text. `infer` reads them from a PDF font name so that new
    /// text can look like the text already in the document.
    struct TextStyle: Equatable {
        enum Family: String, CaseIterable { case sans, serif, mono }

        var family = Family.sans
        var bold = false
        var italic = false
        var size: CGFloat = 14
        var color: UIColor = .black

        private static let boldWords = ["bold", "black", "heavy", "semibold", "demibold", "extrabold"]
        private static let monoWords = ["courier", "mono", "consolas", "menlo", "typewriter", "lucidaconsole", "inconsolata"]
        private static let serifWords = [
            "times", "serif", "georgia", "garamond", "cambria", "palatino", "bookman", "minion", "century",
            "baskerville", "didot", "caslon", "charter", "roman", "constantia", "bookantiqua",
        ]

        /// "ABCDEF+TimesNewRomanPS-BoldMT" -> serif, bold.
        static func infer(fontName: String, size: CGFloat, color: UIColor = .black) -> TextStyle {
            let tail = fontName.split(separator: "+").last.map(String.init) ?? fontName
            let name = String(tail.lowercased().filter { $0 >= "a" && $0 <= "z" })
            var s = TextStyle(size: size, color: color)
            s.bold = boldWords.contains { name.contains($0) }
            s.italic = name.contains("italic") || name.contains("oblique")
            if monoWords.contains(where: { name.contains($0) }) {
                s.family = .mono
            } else if name.contains("sans") {
                s.family = .sans
            } else if serifWords.contains(where: { name.contains($0) }) {
                s.family = .serif
            }
            return s
        }

        var uiFont: UIFont {
            let familyName: String
            switch family {
            case .sans: familyName = "Helvetica"
            case .serif: familyName = "Times New Roman"
            case .mono: familyName = "Courier New"
            }
            var traits: UIFontDescriptor.SymbolicTraits = []
            if bold { traits.insert(.traitBold) }
            if italic { traits.insert(.traitItalic) }
            let base = UIFontDescriptor(fontAttributes: [.family: familyName])
            let descriptor = base.withSymbolicTraits(traits) ?? base
            return UIFont(descriptor: descriptor, size: size)
        }
    }

    /// Style of the text in a selection, read from the attributes PDFKit reports for it.
    static func style(of selection: PDFSelection, fallbackSize: CGFloat) -> TextStyle {
        guard let attributed = selection.attributedString, attributed.length > 0 else { return TextStyle(size: fallbackSize) }
        let attrs = attributed.attributes(at: 0, effectiveRange: nil)
        let font = attrs[.font] as? UIFont
        let color = attrs[.foregroundColor] as? UIColor ?? .black
        let size = (font?.pointSize ?? 0) > 1 ? font!.pointSize : fallbackSize
        return TextStyle.infer(fontName: font?.fontName ?? "", size: size, color: color)
    }

    struct Info: Equatable {
        var title = ""
        var author = ""
        var subject = ""
        var keywords = ""
    }

    static func freeText(_ text: String, in bounds: CGRect, style: TextStyle) -> PDFAnnotation {
        let a = PDFAnnotation(bounds: bounds, forType: .freeText, withProperties: nil)
        a.contents = text
        a.font = style.uiFont
        a.fontColor = style.color
        a.color = .clear
        a.alignment = .left
        let border = PDFBorder()
        border.lineWidth = 0
        a.border = border
        return a
    }

    /// New text with its top-left corner at `point` (PDF page coordinates, origin bottom-left).
    @discardableResult
    static func addText(_ text: String, at point: CGPoint, style: TextStyle, on page: PDFPage) -> PDFAnnotation {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        let longest = lines.map(\.count).max() ?? text.count
        let width = max(80, CGFloat(longest) * style.size * 0.6)
        let height = style.size * 1.4 * CGFloat(max(lines.count, 1))
        let box = CGRect(x: point.x, y: point.y - height, width: width, height: height)
        let a = freeText(text, in: box, style: style)
        page.addAnnotation(a)
        return a
    }

    /// Covers `bounds` with a white box and writes `text` over it.
    @discardableResult
    static func replaceLine(on page: PDFPage, bounds: CGRect, with text: String, style: TextStyle) -> (cover: PDFAnnotation, text: PDFAnnotation) {
        let pad: CGFloat = 1
        let area = bounds.insetBy(dx: -pad, dy: -pad)
        let cover = PDFAnnotation(bounds: area, forType: .square, withProperties: nil)
        cover.color = .white
        cover.interiorColor = .white
        let border = PDFBorder()
        border.lineWidth = 0
        cover.border = border
        page.addAnnotation(cover)

        let label = freeText(text, in: area, style: style)
        page.addAnnotation(label)
        return (cover, label)
    }

    /// Rebuilds the document with `image` drawn on page `index`, centred on `point` (PDF coordinates),
    /// 40% of the page width. Existing annotations are flattened into the pages.
    static func insertImage(_ image: UIImage, in doc: PDFDocument, pageIndex: Int, centre point: CGPoint) -> Data {
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        return renderer.pdfData { ctx in
            for i in 0..<doc.pageCount {
                guard let page = doc.page(at: i) else { continue }
                let box = page.bounds(for: .mediaBox)
                ctx.beginPage(withBounds: CGRect(origin: .zero, size: box.size), pageInfo: [:])
                let cg = ctx.cgContext
                cg.saveGState()
                // UIKit's PDF context is flipped; PDFPage.draw expects PDF coordinates.
                cg.translateBy(x: 0, y: box.height)
                cg.scaleBy(x: 1, y: -1)
                page.draw(with: .mediaBox, to: cg)
                cg.restoreGState()
                if i == pageIndex, image.size.width > 0 {
                    let w = box.width * 0.4
                    let h = w * image.size.height / image.size.width
                    let rect = CGRect(x: point.x - box.minX - w / 2, y: box.height - (point.y - box.minY) - h / 2, width: w, height: h)
                    image.draw(in: rect)
                }
            }
        }
    }

    static func readInfo(_ doc: PDFDocument) -> Info {
        let a = doc.documentAttributes ?? [:]
        let keywords = (a[PDFDocumentAttribute.keywordsAttribute] as? [String])?.joined(separator: ", ") ?? ""
        return Info(
            title: a[PDFDocumentAttribute.titleAttribute] as? String ?? "",
            author: a[PDFDocumentAttribute.authorAttribute] as? String ?? "",
            subject: a[PDFDocumentAttribute.subjectAttribute] as? String ?? "",
            keywords: keywords
        )
    }

    static func writeInfo(_ info: Info, to doc: PDFDocument) {
        var a = doc.documentAttributes ?? [:]
        func set(_ key: PDFDocumentAttribute, _ value: Any?) { if let value { a[key] = value } else { a.removeValue(forKey: key) } }
        set(.titleAttribute, info.title.isEmpty ? nil : info.title)
        set(.authorAttribute, info.author.isEmpty ? nil : info.author)
        set(.subjectAttribute, info.subject.isEmpty ? nil : info.subject)
        let words = info.keywords.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        set(.keywordsAttribute, words.isEmpty ? nil : words)
        doc.documentAttributes = a
    }
}
