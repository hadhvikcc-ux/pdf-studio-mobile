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
    struct Info: Equatable {
        var title = ""
        var author = ""
        var subject = ""
        var keywords = ""
    }

    static func freeText(_ text: String, in bounds: CGRect, size: CGFloat) -> PDFAnnotation {
        let a = PDFAnnotation(bounds: bounds, forType: .freeText, withProperties: nil)
        a.contents = text
        a.font = UIFont.systemFont(ofSize: size)
        a.fontColor = .black
        a.color = .clear
        a.alignment = .left
        let border = PDFBorder()
        border.lineWidth = 0
        a.border = border
        return a
    }

    /// New text with its top-left corner at `point` (PDF page coordinates, origin bottom-left).
    @discardableResult
    static func addText(_ text: String, at point: CGPoint, size: CGFloat, on page: PDFPage) -> PDFAnnotation {
        let width = max(80, CGFloat(text.count) * size * 0.6)
        let box = CGRect(x: point.x, y: point.y - size * 1.4, width: width, height: size * 1.4)
        let a = freeText(text, in: box, size: size)
        page.addAnnotation(a)
        return a
    }

    /// Covers `bounds` with a white box and writes `text` over it.
    @discardableResult
    static func replaceLine(on page: PDFPage, bounds: CGRect, with text: String) -> (cover: PDFAnnotation, text: PDFAnnotation) {
        let pad: CGFloat = 1
        let area = bounds.insetBy(dx: -pad, dy: -pad)
        let cover = PDFAnnotation(bounds: area, forType: .square, withProperties: nil)
        cover.color = .white
        cover.interiorColor = .white
        let border = PDFBorder()
        border.lineWidth = 0
        cover.border = border
        page.addAnnotation(cover)

        let label = freeText(text, in: area, size: max(6, bounds.height * 0.8))
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
