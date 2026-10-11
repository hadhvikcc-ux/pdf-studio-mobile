package com.pdfstudio.app.edit

import com.pdfstudio.app.pdf.TextLayer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/** Picks a font for new text that looks like the font the document already uses. */
object FontMatcher {

    fun styleOf(font: PDFont?, sizePt: Float, rgb: Int): RunStyle {
        val d = runCatching { font?.fontDescriptor }.getOrNull()
        val t = FontClassifier.classify(
            rawName = runCatching { font?.name }.getOrNull().orEmpty(),
            fixedPitch = d?.isFixedPitch == true, serifFlag = d?.isSerif == true,
            italicFlag = d?.isItalic == true, forceBold = d?.isForceBold == true,
            weight = d?.fontWeight ?: 0f
        )
        return RunStyle(t.family, t.bold, t.italic, sizePt, rgb)
    }

    /**
     * The PDF standard-14 font of the requested family is used whenever it can encode the text: it is exactly the
     * Helvetica / Times / Courier that most documents already use, and costs nothing in file size. Text outside
     * Latin-1 needs an embedded system font of the same family instead.
     */
    fun resolve(doc: PDDocument, style: RunStyle, text: String): PDFont {
        val std = standard(style)
        if (canEncode(std, text)) return std
        return embedded(doc, style) ?: std
    }

    fun canEncode(font: PDFont, text: String) = runCatching { font.encode(text) }.isSuccess

    private fun standard(s: RunStyle): PDFont = when (s.family) {
        Family.Sans -> when {
            s.bold && s.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            s.bold -> PDType1Font.HELVETICA_BOLD
            s.italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
        Family.Serif -> when {
            s.bold && s.italic -> PDType1Font.TIMES_BOLD_ITALIC
            s.bold -> PDType1Font.TIMES_BOLD
            s.italic -> PDType1Font.TIMES_ITALIC
            else -> PDType1Font.TIMES_ROMAN
        }
        Family.Mono -> when {
            s.bold && s.italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            s.bold -> PDType1Font.COURIER_BOLD
            s.italic -> PDType1Font.COURIER_OBLIQUE
            else -> PDType1Font.COURIER
        }
    }

    private fun candidates(s: RunStyle): List<String> = when (s.family) {
        Family.Sans -> when {
            s.bold && s.italic -> listOf("Roboto-BoldItalic.ttf", "NotoSans-BoldItalic.ttf")
            s.bold -> listOf("Roboto-Bold.ttf", "NotoSans-Bold.ttf", "DroidSans-Bold.ttf")
            s.italic -> listOf("Roboto-Italic.ttf", "NotoSans-Italic.ttf")
            else -> emptyList()
        } + listOf("Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf")
        Family.Serif -> when {
            s.bold && s.italic -> listOf("NotoSerif-BoldItalic.ttf")
            s.bold -> listOf("NotoSerif-Bold.ttf")
            s.italic -> listOf("NotoSerif-Italic.ttf")
            else -> emptyList()
        } + listOf("NotoSerif-Regular.ttf", "Roboto-Regular.ttf", "DroidSans.ttf")
        Family.Mono -> listOf("DroidSansMono.ttf", "CutiveMono.ttf", "Roboto-Regular.ttf", "DroidSans.ttf")
    }

    private fun embedded(doc: PDDocument, s: RunStyle): PDFont? {
        for (name in candidates(s)) {
            val f = File("/system/fonts/$name")
            if (f.exists()) {
                try { return PDType0Font.load(doc, f) } catch (_: Exception) { /* try the next one */ }
            }
        }
        return runCatching { TextLayer.loadFont(doc, requireEmbedded = false) }.getOrNull()
    }
}
