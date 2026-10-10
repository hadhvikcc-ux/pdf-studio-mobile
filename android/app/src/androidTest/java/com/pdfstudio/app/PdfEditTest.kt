package com.pdfstudio.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfstudio.app.edit.EditOp
import com.pdfstudio.app.edit.InfoFields
import com.pdfstudio.app.edit.PdfAnalyzer
import com.pdfstudio.app.edit.PdfEditor
import com.pdfstudio.app.pdf.PdfTools
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Exercises the PDF editor against real PDFBox-generated documents on a device. */
@RunWith(AndroidJUnit4::class)
class PdfEditTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() = PdfTools.init(ctx)

    /** One page: a text line, a second distinct line, and a red square image. */
    private fun sample(): File {
        val f = File(ctx.cacheDir, "edit-src.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val image = LosslessFactory.createFromImage(doc, bmp)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f); cs.newLineAtOffset(72f, 700f); cs.showText("Hello Edit World"); cs.endText()
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(72f, 650f); cs.showText("Second line"); cs.endText()
                cs.drawImage(image, 72f, 400f, 120f, 120f)
            }
            doc.save(f)
        }
        return f
    }

    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    @Test fun analyzerFindsTextAndImage() {
        val c = PdfAnalyzer.analyze(sample(), 0)
        assertTrue(c.runs.toString(), c.runs.any { it.text == "Hello Edit World" })
        assertEquals(1, c.images.size)
        val img = c.images[0]
        // 120x120pt image at (72,400) on a 612x792 page.
        assertEquals(72f / 612f, img.left, 0.01f)
        assertEquals(1f - 520f / 792f, img.top, 0.01f)
    }

    @Test fun replacesTextInPlaceAndRemovesOldText() {
        val src = sample()
        val run = PdfAnalyzer.analyze(src, 0).runs.first { it.text == "Hello Edit World" }
        val dst = File(ctx.cacheDir, "edit-out.pdf")
        val r = PdfEditor.apply(src, dst, listOf(EditOp.ReplaceText(0, run, "Goodbye")))
        assertEquals(1, r.replacedInPlace)
        assertEquals(0, r.covered)
        val t = text(dst)
        assertTrue(t, t.contains("Goodbye"))
        assertFalse("old text must be gone from the file", t.contains("Hello Edit World"))
        assertTrue("other text untouched", t.contains("Second line"))
    }

    @Test fun addsText() {
        val src = sample()
        val dst = File(ctx.cacheDir, "edit-add.pdf")
        PdfEditor.apply(src, dst, listOf(EditOp.AddText(0, 0.2f, 0.5f, "Added note", 14f)))
        assertTrue(text(dst).contains("Added note"))
    }

    @Test fun deletesImage() {
        val src = sample()
        val img = PdfAnalyzer.analyze(src, 0).images.single()
        val dst = File(ctx.cacheDir, "edit-noimg.pdf")
        PdfEditor.apply(src, dst, listOf(EditOp.DeleteImage(0, img)))
        assertEquals(0, PdfAnalyzer.analyze(dst, 0).images.size)
        assertTrue("text survives", text(dst).contains("Hello Edit World"))
    }

    @Test fun replacesImage() {
        val src = sample()
        val old = PdfAnalyzer.analyze(src, 0).images.single()
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val dst = File(ctx.cacheDir, "edit-newimg.pdf")
        PdfEditor.apply(src, dst, listOf(EditOp.PlaceImage(0, old.left, old.top, old.right, old.bottom, png, replaces = old)))
        val after = PdfAnalyzer.analyze(dst, 0).images
        assertEquals(1, after.size)
        assertEquals(old.left, after[0].left, 0.01f)
    }

    @Test fun metadataRoundTrip() {
        val src = sample()
        val dst = File(ctx.cacheDir, "edit-meta.pdf")
        PdfEditor.writeInfo(src, dst, InfoFields("My Title", "Ada", "Subj", "a, b"))
        assertEquals(InfoFields("My Title", "Ada", "Subj", "a, b"), PdfEditor.readInfo(dst))
    }
}
