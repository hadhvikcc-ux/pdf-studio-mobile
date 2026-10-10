package com.pdfstudio.app

import com.pdfstudio.app.pdf.RangeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeParserTest {
    @Test fun parsesRangesAndSinglePages() {
        assertEquals(listOf(0..2, 4..4), RangeParser.ranges("1-3, 5", 10))
    }

    @Test fun rejectsOutOfBoundsRange() {
        assertThrows(IllegalArgumentException::class.java) { RangeParser.ranges("1-11", 10) }
    }

    @Test fun rejectsReversedRange() {
        assertThrows(IllegalArgumentException::class.java) { RangeParser.ranges("5-2", 10) }
    }

    @Test fun parsesOrderWithDeletion() {
        assertEquals(listOf(2, 0), RangeParser.order("3,1", 3))
    }

    @Test fun rejectsDuplicateOrder() {
        assertThrows(IllegalArgumentException::class.java) { RangeParser.order("1,1", 3) }
    }

    @Test fun rejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) { RangeParser.order("a,b", 3) }
    }
}
