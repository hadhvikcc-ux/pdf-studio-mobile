package com.pdfstudio.app.pdf

/** Pure helpers for parsing user-entered page lists. Pages are 1-based in input, 0-based in output. */
object RangeParser {

    /** "1-3,5" -> [0..2, 4..4]. Throws IllegalArgumentException on bad input or out-of-range pages. */
    fun ranges(text: String, pageCount: Int): List<IntRange> {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Empty range" }
        return parts.map { part ->
            val bounds = part.split('-').map { it.trim() }
            val start = bounds[0].toIntOrNull()
            val end = if (bounds.size == 1) start else bounds.getOrNull(1)?.toIntOrNull()
            require(bounds.size <= 2 && start != null && end != null) { "Invalid range: $part" }
            require(start in 1..end && end <= pageCount) { "Range out of bounds: $part (1-$pageCount)" }
            (start - 1)..(end - 1)
        }
    }

    /** "3,1,2" -> [2, 0, 1]. Pages may be omitted (deleted) but not repeated. */
    fun order(text: String, pageCount: Int): List<Int> {
        val pages = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
            it.toIntOrNull() ?: throw IllegalArgumentException("Invalid page: $it")
        }
        require(pages.isNotEmpty()) { "Empty order" }
        require(pages.all { it in 1..pageCount }) { "Pages must be within 1-$pageCount" }
        require(pages.toSet().size == pages.size) { "Duplicate pages" }
        return pages.map { it - 1 }
    }
}
