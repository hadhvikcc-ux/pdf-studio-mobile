package com.pdfstudio.app.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Calendar

/**
 * Exports an archival PDF/A-2b copy.
 *
 * Phone-side tooling cannot guarantee that arbitrary source PDFs have embedded fonts, tagged colour
 * spaces and no forbidden features, so every page is rasterised to a JPEG (RGB, covered by the sRGB
 * output intent) and rebuilt. Text stays searchable by laying OCR output on top as invisible text in
 * an embedded font. The trade-off is a larger file and no vector text/links from the source.
 */
object PdfaExporter {
    /** ~200 dpi on A4. */
    private const val RASTER_WIDTH = 1654

    /**
     * @param ocr when non-null the pages are recognised and the text kept searchable.
     * @param sRgbProfile opens the bundled sRGB ICC profile (called once).
     */
    suspend fun export(
        src: File,
        dst: File,
        title: String,
        ocr: OcrEngine?,
        sRgbProfile: () -> InputStream,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            PdfSession(src).use { session ->
                val font = if (ocr != null) TextLayer.loadFont(doc, requireEmbedded = true) else null
                for (i in 0 until session.pageCount) {
                    onProgress(i, session.pageCount)
                    val (wPt, hPt) = session.size(i)
                    val bmp = session.render(i, RASTER_WIDTH)
                    val lines = ocr?.recognize(bmp).orEmpty()
                    val bw = bmp.width
                    val bh = bmp.height
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                    bmp.recycle()
                    withContext(Dispatchers.IO) {
                        val page = PDPage(PDRectangle(wPt.toFloat(), hPt.toFloat()))
                        doc.addPage(page)
                        val image = PDImageXObject.createFromByteArray(doc, jpeg, "page$i")
                        PDPageContentStream(doc, page).use { cs ->
                            cs.drawImage(image, 0f, 0f, wPt.toFloat(), hPt.toFloat())
                            if (font != null && lines.isNotEmpty()) TextLayer.draw(cs, font, lines, bw, bh, page.cropBox)
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) {
                applyPdfaMetadata(doc, title, sRgbProfile)
                doc.save(dst)
            }
            onProgress(doc.numberOfPages, doc.numberOfPages)
        } finally {
            doc.close()
        }
    }

    private fun applyPdfaMetadata(doc: PDDocument, title: String, sRgbProfile: () -> InputStream) {
        val now = Calendar.getInstance()
        doc.documentInformation.apply {
            this.title = title
            producer = PdfaMetadata.PRODUCER
            creator = PdfaMetadata.PRODUCER
            creationDate = now
            modificationDate = now
        }
        val metadata = PDMetadata(doc)
        metadata.importXMPMetadata(PdfaMetadata.xmp(title, now.time).toByteArray(Charsets.UTF_8))
        doc.documentCatalog.metadata = metadata

        sRgbProfile().use { icc ->
            val intent = PDOutputIntent(doc, icc)
            intent.info = "sRGB"
            intent.outputCondition = "sRGB"
            intent.outputConditionIdentifier = "sRGB"
            intent.registryName = "http://www.color.org"
            doc.documentCatalog.addOutputIntent(intent)
        }
    }
}
