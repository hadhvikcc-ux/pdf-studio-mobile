import PDFKit
import UIKit
import Vision
import CoreText

/// On-device OCR with Apple's Vision framework. Scanned pages get an invisible text layer so they
/// become searchable and selectable; the original page content is kept as vector/image data.
enum OCR {
    struct Line {
        let text: String
        /// Normalised (0...1) with the origin at the bottom-left, as Vision reports it (same as PDF space).
        let box: CGRect
    }

    /// Width in pixels pages are rendered at before recognition (~250 dpi on A4).
    private static let renderWidth: CGFloat = 2000

    static func recognize(_ image: CGImage) throws -> [Line] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        try VNImageRequestHandler(cgImage: image).perform([request])
        return (request.results ?? []).compactMap { obs in
            obs.topCandidates(1).first.map { Line(text: $0.string, box: obs.boundingBox) }
        }
    }

    /// 0-based indices of pages without extractable text (scans).
    static func pagesNeedingOCR(_ doc: PDFDocument) -> [Int] {
        (0..<doc.pageCount).filter {
            (doc.page(at: $0)?.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).count < 3
        }
    }

    /// Returns a copy of the document as PDF data with invisible text on every scanned page,
    /// plus the number of pages recognised. Returns nil when every page already has text.
    /// Limitations: annotations and per-page rotation are not carried over, so save annotations first.
    static func makeSearchable(_ doc: PDFDocument, progress: (Int, Int) -> Void) throws -> (data: Data, pages: Int)? {
        let needs = pagesNeedingOCR(doc)
        guard !needs.isEmpty else { return nil }

        var recognised: [Int: [Line]] = [:]
        for (k, index) in needs.enumerated() {
            progress(k, needs.count)
            guard let page = doc.page(at: index) else { continue }
            let box = page.bounds(for: .mediaBox)
            let scale = renderWidth / max(box.width, 1)
            let image = page.thumbnail(of: CGSize(width: box.width * scale, height: box.height * scale), for: .mediaBox)
            if let cg = image.cgImage { recognised[index] = try recognize(cg) }
        }
        progress(needs.count, needs.count)

        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        let data = renderer.pdfData { ctx in
            for i in 0..<doc.pageCount {
                guard let page = doc.page(at: i) else { continue }
                let box = page.bounds(for: .mediaBox)
                ctx.beginPage(withBounds: CGRect(origin: .zero, size: box.size), pageInfo: [:])
                let cg = ctx.cgContext
                if let ref = page.pageRef {
                    cg.saveGState()
                    // UIKit's PDF context is flipped; undo that for CGPDFPage drawing.
                    cg.translateBy(x: 0, y: box.height)
                    cg.scaleBy(x: 1, y: -1)
                    cg.drawPDFPage(ref)
                    cg.restoreGState()
                }
                for line in recognised[i] ?? [] { drawInvisible(line, in: cg, pageSize: box.size) }
            }
        }
        return (data, needs.count)
    }

    private static func drawInvisible(_ line: Line, in cg: CGContext, pageSize: CGSize) {
        // Convert from PDF-style (bottom-left) normalised coordinates to the flipped UIKit page space.
        let rect = CGRect(
            x: line.box.minX * pageSize.width,
            y: (1 - line.box.maxY) * pageSize.height,
            width: line.box.width * pageSize.width,
            height: line.box.height * pageSize.height
        )
        let font = CTFontCreateWithName("Helvetica" as CFString, max(rect.height * 0.85, 4), nil)
        let attributed = NSAttributedString(string: line.text, attributes: [.font: font])
        let ctLine = CTLineCreateWithAttributedString(attributed)
        let natural = CTLineGetTypographicBounds(ctLine, nil, nil, nil)
        guard natural > 0, rect.width > 0 else { return }

        cg.saveGState()
        cg.setTextDrawingMode(.invisible)
        // Stretch horizontally so the text spans the recognised line; d = -1 because the context is flipped.
        cg.textMatrix = CGAffineTransform(a: rect.width / natural, b: 0, c: 0, d: -1, tx: rect.minX, ty: rect.maxY - rect.height * 0.2)
        CTLineDraw(ctLine, cg)
        cg.restoreGState()
    }
}
