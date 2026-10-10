import XCTest
@testable import PDFStudio

final class PageRangesTests: XCTestCase {
    func testRanges() throws {
        XCTAssertEqual(try PageRanges.ranges("1-3, 5", pageCount: 10), [[0, 1, 2], [4]])
    }

    func testRangeOutOfBounds() {
        XCTAssertThrowsError(try PageRanges.ranges("1-11", pageCount: 10))
    }

    func testReversedRange() {
        XCTAssertThrowsError(try PageRanges.ranges("5-2", pageCount: 10))
    }

    func testOrderWithDeletion() throws {
        XCTAssertEqual(try PageRanges.order("3,1", pageCount: 3), [2, 0])
    }

    func testDuplicateOrder() {
        XCTAssertThrowsError(try PageRanges.order("1,1", pageCount: 3))
    }

    func testGarbage() {
        XCTAssertThrowsError(try PageRanges.order("a,b", pageCount: 3))
    }
}
