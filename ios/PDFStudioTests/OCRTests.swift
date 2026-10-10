import XCTest
import PDFKit
@testable import PDFStudio

final class OCRTests: XCTestCase {
    /// A one-page "scan": an image of text with no text layer.
    private func scannedDocument() throws -> PDFDocument {
        let size = CGSize(width: 1240, height: 1754)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            ("Invoice Hello World" as NSString).draw(
                at: CGPoint(x: 100, y: 200),
                withAttributes: [.font: UIFont.systemFont(ofSize: 96), .foregroundColor: UIColor.black]
            )
        }
        let doc = PDFDocument()
        doc.insert(try XCTUnwrap(PDFPage(image: image)), at: 0)
        return doc
    }

    func testScanNeedsOCRAndBecomesSearchable() throws {
        let doc = try scannedDocument()
        XCTAssertEqual(OCR.pagesNeedingOCR(doc), [0])

        let result = try XCTUnwrap(try OCR.makeSearchable(doc) { _, _ in })
        XCTAssertEqual(result.pages, 1)

        let out = try XCTUnwrap(PDFDocument(data: result.data))
        XCTAssertTrue((out.string ?? "").localizedCaseInsensitiveContains("Hello"), out.string ?? "<no text>")
        // Nothing left to recognise on the result.
        XCTAssertTrue(OCR.pagesNeedingOCR(out).isEmpty)
        XCTAssertNil(try OCR.makeSearchable(out) { _, _ in })
    }
}
