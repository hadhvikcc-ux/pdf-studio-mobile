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
        EditTools.addText("Added note", at: CGPoint(x: 100, y: 500), style: .init(), on: page)

        let reloaded = try XCTUnwrap(PDFDocument(data: try XCTUnwrap(doc.dataRepresentation())))
        let contents = reloaded.page(at: 0)?.annotations.compactMap(\.contents) ?? []
        XCTAssertTrue(contents.contains("Added note"), "\(contents)")
    }

    func testReplaceLineAddsCoverAndText() throws {
        let doc = try blankDocument()
        let page = try XCTUnwrap(doc.page(at: 0))
        EditTools.replaceLine(on: page, bounds: CGRect(x: 100, y: 710, width: 220, height: 30), with: "Goodbye", style: .init())

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

    func testStyleInferredFromFontName() {
        let timesBold = EditTools.TextStyle.infer(fontName: "ABCDEF+TimesNewRomanPS-BoldMT", size: 12)
        XCTAssertEqual(timesBold.family, .serif)
        XCTAssertTrue(timesBold.bold)
        XCTAssertFalse(timesBold.italic)

        let arial = EditTools.TextStyle.infer(fontName: "ArialMT", size: 12)
        XCTAssertEqual(arial.family, .sans)

        let oblique = EditTools.TextStyle.infer(fontName: "Helvetica-Oblique", size: 12)
        XCTAssertTrue(oblique.italic)

        XCTAssertEqual(EditTools.TextStyle.infer(fontName: "Courier-Bold", size: 12).family, .mono)
        XCTAssertEqual(EditTools.TextStyle.infer(fontName: "DejaVuSansSerif", size: 12).family, .sans)
    }

    func testUIFontFollowsStyle() {
        var style = EditTools.TextStyle(family: .serif, bold: true, italic: false, size: 18)
        let font = style.uiFont
        XCTAssertEqual(font.pointSize, 18, accuracy: 0.1)
        XCTAssertTrue(font.fontDescriptor.symbolicTraits.contains(.traitBold))
        XCTAssertTrue(font.familyName.lowercased().contains("times"), font.familyName)
        style.family = .mono
        XCTAssertTrue(style.uiFont.familyName.lowercased().contains("courier"), style.uiFont.familyName)
    }

    func testStyleReadFromSelection() throws {
        let doc = try blankDocument()
        let page = try XCTUnwrap(doc.page(at: 0))
        // "Hello Edit World" is drawn at (100,100) from the top with a 24pt system font.
        let selection = try XCTUnwrap(page.selectionForLine(at: CGPoint(x: 120, y: 842 - 112)))
        let style = EditTools.style(of: selection, fallbackSize: 10)
        XCTAssertEqual(style.size, 24, accuracy: 1.5)
    }
}
