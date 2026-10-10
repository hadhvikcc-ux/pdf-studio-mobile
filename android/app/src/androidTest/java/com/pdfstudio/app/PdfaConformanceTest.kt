package com.pdfstudio.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfstudio.app.pdf.OcrEngine
import com.pdfstudio.app.pdf.OcrPipeline
import com.pdfstudio.app.pdf.PdfTools
import com.pdfstudio.app.pdf.PdfaExporter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on an emulator in CI. The exported file is left in the app's external files dir so the workflow
 * can pull it and validate it with veraPDF.
 */
@RunWith(AndroidJUnit4::class)
class PdfaConformanceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** A one-page "scan": an image of text with no text layer. */
    private fun scannedPdf(): File {
        val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = Color.BLACK; textSize = 96f; isAntiAlias = true }
        Canvas(bmp).apply { drawColor(Color.WHITE); drawText("Invoice Hello World", 100f, 300f, paint) }
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawBitmap(bmp, null, Rect(0, 0, 595, 842), null)
        doc.finishPage(page)
        val f = File(ctx.cacheDir, "scan.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /**
     * Gradle uninstalls the app when the tests finish, taking its files with it. Copy the export somewhere the
     * adb shell can read it so the workflow can pull it afterwards and validate it with veraPDF.
     */
    private fun publishForCi(f: File) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${f.absolutePath} /data/local/tmp/pdfa-out.pdf")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // wait for the command to finish
    }

    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    @Test fun ocrMakesScanSearchable() = runBlocking {
        PdfTools.init(ctx)
        val src = scannedPdf()
        assertEquals(listOf(0), OcrPipeline.pagesWithoutText(src))
        OcrEngine().use { engine ->
            val out = File(ctx.cacheDir, "ocr-out.pdf")
            assertEquals(1, OcrPipeline.makeSearchable(src, out, engine) { _, _ -> })
            assertTrue(text(out), text(out).contains("Hello", ignoreCase = true))
            // Already searchable now: nothing left to do.
            assertEquals(0, OcrPipeline.makeSearchable(out, File(ctx.cacheDir, "again.pdf"), engine) { _, _ -> })
        }
    }

    @Test fun exportsPdfA() = runBlocking {
        PdfTools.init(ctx)
        val src = scannedPdf()
        val dst = File(ctx.getExternalFilesDir(null), "pdfa-out.pdf")
        OcrEngine().use { engine ->
            PdfaExporter.export(src, dst, "Conformance test", engine, { ctx.assets.open("sRGB.icc") }) { _, _ -> }
        }
        assertTrue(dst.length() > 0)
        assertTrue(text(dst), text(dst).contains("Hello", ignoreCase = true))
        publishForCi(dst)
    }
}
