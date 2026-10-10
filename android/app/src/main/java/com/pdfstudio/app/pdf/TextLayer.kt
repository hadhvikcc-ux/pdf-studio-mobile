package com.pdfstudio.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/** Draws OCR results as invisible (render mode 3) text so a scanned page becomes searchable and selectable. */
object TextLayer {
    private val systemFonts = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf"
    )

    /**
     * Loads an embedded (subset) font from the device. PDF/A requires every font to be embedded, so
     * [requireEmbedded] fails instead of falling back to the non-embedded standard Helvetica.
     */
    fun loadFont(doc: PDDocument, requireEmbedded: Boolean): PDFont {
        for (path in systemFonts) {
            val f = File(path)
            if (f.exists()) {
                try { return PDType0Font.load(doc, f) } catch (_: Exception) { /* try the next font */ }
            }
        }
        check(!requireEmbedded) { "No embeddable system font found on this device" }
        return PDType1Font.HELVETICA
    }

    /** Characters the font cannot encode are dropped; null when nothing is left. */
    private fun encodable(text: String, font: PDFont): String? {
        val clean = text.filter { !it.isISOControl() }
        if (clean.isBlank()) return null
        return try {
            font.encode(clean)
            clean
        } catch (_: IllegalArgumentException) {
            clean.filter { ch -> runCatching { font.encode(ch.toString()) }.isSuccess }.takeIf { it.isNotBlank() }
        }
    }

    /** [imgW] x [imgH] is the size in pixels of the bitmap the lines were recognised on. */
    fun draw(cs: PDPageContentStream, font: PDFont, lines: List<OcrLine>, imgW: Int, imgH: Int, box: PDRectangle) {
        val sx = box.width / imgW
        val sy = box.height / imgH
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (l in lines) {
            val text = encodable(l.text, font) ?: continue
            val heightPt = (l.bottom - l.top) * sy
            val size = (heightPt * 0.85f).coerceIn(4f, 72f)
            val natural = font.getStringWidth(text) / 1000f * size
            if (natural <= 0f) continue
            // Stretch the glyphs horizontally so the text spans exactly the recognised line box.
            val scaleX = ((l.right - l.left) * sx / natural).coerceIn(0.2f, 5f)
            val x = box.lowerLeftX + l.left * sx
            val y = box.upperRightY - l.bottom * sy + heightPt * 0.2f
            cs.setFont(font, size)
            cs.setTextMatrix(Matrix(scaleX, 0f, 0f, 1f, x, y))
            cs.showText(text)
        }
        cs.endText()
    }
}
