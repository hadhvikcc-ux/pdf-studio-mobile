package com.pdfstudio.app

import com.pdfstudio.app.edit.Family
import com.pdfstudio.app.edit.FontClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontClassifierTest {
    private fun c(name: String, fixed: Boolean = false, serif: Boolean = false, italic: Boolean = false, bold: Boolean = false, weight: Float = 0f) =
        FontClassifier.classify(name, fixed, serif, italic, bold, weight)

    @Test fun subsetPrefixIsIgnored() {
        val t = c("ABCDEF+TimesNewRomanPS-BoldMT")
        assertEquals(Family.Serif, t.family)
        assertTrue(t.bold)
        assertFalse(t.italic)
    }

    @Test fun arialIsSans() = assertEquals(Family.Sans, c("ArialMT").family)

    @Test fun helveticaObliqueIsItalicSans() {
        val t = c("Helvetica-Oblique")
        assertEquals(Family.Sans, t.family)
        assertTrue(t.italic)
    }

    @Test fun courierIsMono() = assertEquals(Family.Mono, c("Courier-Bold").family)

    @Test fun fixedPitchFlagWinsOverName() = assertEquals(Family.Mono, c("SomethingOdd", fixed = true).family)

    @Test fun sansSerifNameIsNotSerif() = assertEquals(Family.Sans, c("DejaVuSansSerif-Regular").family)

    @Test fun descriptorFlagsMakeUnnamedFontSerifBoldItalic() {
        val t = c("F1", serif = true, italic = true, weight = 700f)
        assertEquals(Family.Serif, t.family)
        assertTrue(t.bold)
        assertTrue(t.italic)
    }

    @Test fun calibriDefaultsToSans() = assertEquals(Family.Sans, c("Calibri").family)

    @Test fun georgiaIsSerif() = assertEquals(Family.Serif, c("Georgia-Italic").family)
}
