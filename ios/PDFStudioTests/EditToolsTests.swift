import XCTest
import PDFKit
@testable import PDFStudio

final class EditToolsTests: XCTestCase {
    private func blankDocument() throws -> PDFDocument {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
            ctx.beginPage()
            ("Hello Edit World" as NSString).draw(at: CGPoint(x: 100, y: 100), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
        }
        return try XCTUnwrap(PDFDocument(data: data))
    }

    func testAddTextSurvivesSaveAndReload() throws {
        let doc = try blankDocument()
        let page = try XCTUnwrap(doc.page(at: 0))
        EditTools.addText("Added note", at: CGPoint(x: 100, y: 500), size: 14, on: page)

        let reloaded = try XCTUnwrap(PDFDocument(data: try XCTUnwrap(doc.dataRepresentation())))
        let contents = reloaded.page(at: 0)?.annotations.compactMap(\.contents) ?? []
        XCTAssertTrue(contents.contains("Added note"), "\(contents)")
    }

    func testReplaceLineAddsCoverAndText() throws {
        let doc = try blankDocument()
        let page = try XCTUnwrap(doc.page(at: 0))
        EditTools.replaceLine(on: page, bounds: CGRect(x: 100, y: 710, width: 220, height: 30), with: "Goodbye")

        XCTAssertEqual(page.annotations.count, 2)
        let reloaded = try XCTUnwrap(PDFDocument(data: try XCTUnwrap(doc.dataRepresentation())))
        let contents = reloaded.page(at: 0)?.annotations.compactMap(\.contents) ?? []
        XCTAssertTrue(contents.contains("Goodbye"), "\(contents)")
    }

    func testInsertImageKeepsPagesAndText() throws {
        let doc = try blankDocument()
        let image = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 40, height: 40))
        }
        let data = EditTools.insertImage(image, in: doc, pageIndex: 0, centre: CGPoint(x: 300, y: 400))
        let out = try XCTUnwrap(PDFDocument(data: data))
        XCTAssertEqual(out.pageCount, 1)
        XCTAssertTrue((out.string ?? "").contains("Hello"), out.string ?? "<no text>")
    }

    func testInfoRoundTrip() throws {
        let doc = try blankDocument()
        let info = EditTools.Info(title: "My Title", author: "Ada", subject: "Subj", keywords: "a, b")
        EditTools.writeInfo(info, to: doc)
        let reloaded = try XCTUnwrap(PDFDocument(data: try XCTUnwrap(doc.dataRepresentation())))
        XCTAssertEqual(EditTools.readInfo(reloaded), info)
    }
}
