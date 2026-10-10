import Foundation

/// Pure helpers for parsing user-entered page lists. Input is 1-based, output 0-based.
enum PageRanges {
    struct ParseError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// "1-3, 5" -> [[0,1,2],[4]]
    static func ranges(_ text: String, pageCount: Int) throws -> [[Int]] {
        let parts = text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !parts.isEmpty else { throw ParseError(message: "Empty range") }
        return try parts.map { part in
            let b = part.split(separator: "-").map { $0.trimmingCharacters(in: .whitespaces) }
            guard b.count == 1 || b.count == 2, let s = Int(b[0]), let e = Int(b.last!) else {
                throw ParseError(message: "Invalid range: \(part)")
            }
            guard s >= 1, s <= e, e <= pageCount else { throw ParseError(message: "Range out of bounds: \(part) (1-\(pageCount))") }
            return Array((s - 1)...(e - 1))
        }
    }

    /// "3,1,2" -> [2,0,1]. Pages may be omitted (deleted) but not repeated.
    static func order(_ text: String, pageCount: Int) throws -> [Int] {
        let parts = text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !parts.isEmpty else { throw ParseError(message: "Empty order") }
        let pages = try parts.map { p -> Int in
            guard let n = Int(p) else { throw ParseError(message: "Invalid page: \(p)") }
            return n
        }
        guard pages.allSatisfy({ $0 >= 1 && $0 <= pageCount }) else { throw ParseError(message: "Pages must be within 1-\(pageCount)") }
        guard Set(pages).count == pages.count else { throw ParseError(message: "Duplicate pages") }
        return pages.map { $0 - 1 }
    }
}
