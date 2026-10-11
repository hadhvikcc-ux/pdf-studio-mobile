package com.pdfstudio.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfstudio.app.edit.EditOp
import com.pdfstudio.app.edit.Family
import com.pdfstudio.app.edit.InfoFields
import com.pdfstudio.app.edit.PdfAnalyzer
import com.pdfstudio.app.edit.PdfEditor
import com.pdfstudio.app.edit.RunStyle
import com.pdfstudio.app.edit.TextRun
import com.pdfstudio.app.pdf.PdfTools
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Exercises the PDF editor against real PDFBox-generated documents on a device. */
@RunWith(AndroidJUnit4::class)
class PdfEditTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val roboto = File("/system/fonts/Roboto-Regular.ttf")

    @Before fun setUp() = PdfTools.init(ctx)

    private fun build(name: String, draw: (PDDocument, PDPageContentStream) -> Unit): File {
        val f = File(ctx.cacheDir, name)
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs -> draw(doc, cs) }
            doc.save(f)
        }
        return f
    }

    private fun PDPageContentStream.line(font: PDFont, size: Float, x: Float, y: Float, vararg parts: String) {
        beginText(); setFont(font, size); newLineAtOffset(x, y)
        parts.forEach { showText(it) }
        endText()
    }

    /** One page: a text line, a second distinct line, and a red square image. */
    private fun sample(): File = build("edit-src.pdf") { doc, cs ->
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        cs.line(PDType1Font.HELVETICA, 18f, 72f, 700f, "Hello Edit World")
        cs.line(PDType1Font.HELVETICA, 12f, 72f, 650f, "Second line")
        cs.drawImage(LosslessFactory.createFromImage(doc, bmp), 72f, 400f, 120f, 120f)
    }

    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    private fun run(f: File, text: String): TextRun =
        PdfAnalyzer.analyze(f, 0).runs.let { runs -> runs.firstOrNull { it.text == text } ?: error("no run '$text' in ${runs.map { it.text }}") }

    /** Name of the font the first glyph of the word containing [word] is drawn in. */
    private fun fontOf(f: File, word: String): String {
        var found = ""
        PDDocument.load(f).use { doc ->
            object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    if (found.isEmpty() && text.contains(word)) found = textPositions.first().font.name
                }
            }.getText(doc)
        }
        return found
    }

    private fun edit(src: File, name: String, vararg ops: EditOp) =
        File(ctx.cacheDir, name).also { dst -> PdfEditor.apply(src, dst, ops.toList()).also { lastResult = it } }

    private var lastResult = com.pdfstudio.app.edit.EditResult(0, 0, 0, 0)

    // ---- analysis ------------------------------------------------------------------------------

    @Test fun analyzerFindsTextAndImageAtTheRightPlace() {
        val c = PdfAnalyzer.analyze(sample(), 0)
        val r = c.runs.first { it.text == "Hello Edit World" }
        assertEquals(72f / 612f, r.left, 0.01f)
        assertEquals(1f - 700f / 792f, r.baseline, 0.01f)
        assertEquals(1, c.images.size)
        assertEquals(1f - 520f / 792f, c.images[0].top, 0.01f)
    }

    @Test fun detectsFontFamilyWeightAndColour() {
        val serif = build("serif.pdf") { _, cs -> cs.line(PDType1Font.TIMES_BOLD, 20f, 72f, 700f, "Chapter One") }
        val s = run(serif, "Chapter One").style
        assertEquals(Family.Serif, s.family)
        assertTrue(s.bold)
        assertEquals(20f, s.sizePt, 0.5f)

        val mono = build("mono.pdf") { _, cs -> cs.line(PDType1Font.COURIER, 11f, 72f, 700f, "code here") }
        assertEquals(Family.Mono, run(mono, "code here").style.family)

        val red = build("red.pdf") { _, cs ->
            cs.setNonStrokingColor(1f, 0f, 0f)
            cs.line(PDType1Font.HELVETICA_OBLIQUE, 14f, 72f, 700f, "Warning")
        }
        val r = run(red, "Warning").style
        assertEquals(0xFF0000, r.rgb)
        assertTrue(r.italic)
    }

    // ---- replacing text ------------------------------------------------------------------------

    @Test fun sameFontReplacementRemovesOldText() {
        val src = sample()
        val r = run(src, "Hello Edit World")
        val dst = edit(src, "t1.pdf", EditOp.ReplaceText(0, r, "Goodbye", r.style))
        assertEquals(1, lastResult.sameFont)
        val t = text(dst)
        assertTrue(t, t.contains("Goodbye"))
        assertFalse("old text must be gone from the file", t.contains("Hello Edit World"))
        assertTrue("other text untouched", t.contains("Second line"))
    }

    @Test fun lineSplitAcrossOperatorsIsFullyRemovedAndRetypedInTheSameFamily() {
        val src = build("split-ops.pdf") { _, cs -> cs.line(PDType1Font.TIMES_ROMAN, 14f, 72f, 700f, "Hello ", "World") }
        val r = run(src, "Hello World")
        assertTrue("both operators belong to the run", r.removable && r.ops.size == 2)
        val dst = edit(src, "t2.pdf", EditOp.ReplaceText(0, r, "Bye now", r.style))
        assertEquals(1, lastResult.retyped)
        val t = text(dst)
        assertTrue(t, t.contains("Bye now"))
        assertFalse(t, t.contains("Hello") || t.contains("World"))
        assertTrue("serif document gets a serif replacement, got ${fontOf(dst, "Bye")}", fontOf(dst, "Bye").startsWith("Times"))
    }

    @Test fun embeddedSubsetFontIsReplacedAndOldGlyphsAreGone() {
        assumeTrue(roboto.exists())
        val src = build("subset.pdf") { doc, cs -> cs.line(PDType0Font.load(doc, roboto), 16f, 72f, 700f, "Quarterly Report") }
        val r = run(src, "Quarterly Report")
        assertTrue(r.removable)
        // 'A', 'n', 'S', 'm' are not in the subset, so the original font cannot be reused.
        val dst = edit(src, "t3.pdf", EditOp.ReplaceText(0, r, "Annual Summary", r.style))
        assertEquals(1, lastResult.retyped)
        val t = text(dst)
        assertTrue(t, t.contains("Annual Summary"))
        assertFalse(t, t.contains("Quarterly") || t.contains("Report"))
        assertTrue("sans original gets Helvetica, got ${fontOf(dst, "Annual")}", fontOf(dst, "Annual").startsWith("Helvetica"))
    }

    @Test fun neighbouringTextDoesNotMove() {
        val src = build("neighbours.pdf") { _, cs ->
            cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(72f, 700f)
            cs.showText("Keep"); cs.newLineAtOffset(250f, 0f); cs.showText("Edit me please")
            cs.newLineAtOffset(-250f, -30f); cs.showText("Next line"); cs.endText()
        }
        val keepBefore = run(src, "Keep").left
        val nextBefore = run(src, "Next line").left
        val target = run(src, "Edit me please")
        val dst = edit(src, "t4.pdf", EditOp.ReplaceText(0, target, "Edited", target.style.copy(bold = true)))
        assertEquals(keepBefore, run(dst, "Keep").left, 0.002f)
        assertEquals(nextBefore, run(dst, "Next line").left, 0.002f)
        assertTrue(text(dst).contains("Edited"))
        assertFalse(text(dst).contains("Edit me please"))
    }

    @Test fun chosenStyleIsApplied() {
        val src = sample()
        val r = run(src, "Second line")
        val dst = edit(src, "t5.pdf", EditOp.ReplaceText(0, r, "Styled text", RunStyle(Family.Serif, bold = true, italic = true, sizePt = 20f, rgb = 0x0000FF)))
        assertEquals("Times-BoldItalic", fontOf(dst, "Styled"))
        val after = run(dst, "Styled text").style
        assertEquals(0x0000FF, after.rgb)
        assertEquals(20f, after.sizePt, 0.5f)
    }

    @Test fun addsTextInTheSuggestedStyle() {
        val src = sample()
        val dst = edit(src, "t6.pdf", EditOp.AddText(0, 0.2f, 0.5f, "Added note", RunStyle(Family.Mono, sizePt = 12f)))
        assertTrue(text(dst).contains("Added note"))
        assertTrue(fontOf(dst, "Added").startsWith("Courier"))
    }

    // ---- images and metadata -------------------------------------------------------------------

    @Test fun deletesImage() {
        val src = sample()
        val img = PdfAnalyzer.analyze(src, 0).images.single()
        val dst = edit(src, "t7.pdf", EditOp.DeleteImage(0, img))
        assertEquals(0, PdfAnalyzer.analyze(dst, 0).images.size)
        assertTrue("text survives", text(dst).contains("Hello Edit World"))
    }

    @Test fun replacesImage() {
        val src = sample()
        val old = PdfAnalyzer.analyze(src, 0).images.single()
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val dst = edit(src, "t8.pdf", EditOp.PlaceImage(0, old.left, old.top, old.right, old.bottom, png, replaces = old))
        val after = PdfAnalyzer.analyze(dst, 0).images
        assertEquals(1, after.size)
        assertEquals(old.left, after[0].left, 0.01f)
    }

    @Test fun metadataRoundTrip() {
        val src = sample()
        val dst = File(ctx.cacheDir, "meta.pdf")
        PdfEditor.writeInfo(src, dst, InfoFields("My Title", "Ada", "Subj", "a, b"))
        assertEquals(InfoFields("My Title", "Ada", "Subj", "a, b"), PdfEditor.readInfo(dst))
        assertNotNull(dst)
    }
}
