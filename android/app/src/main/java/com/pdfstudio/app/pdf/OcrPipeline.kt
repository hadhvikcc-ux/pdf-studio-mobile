package com.pdfstudio.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OcrPipeline {
    /** Longest side is not capped; width in pixels the page is rendered at before recognition (~250 dpi on A4). */
    private const val OCR_WIDTH = 2000

    /** 0-based indices of pages that carry no extractable text (i.e. scans). */
    fun pagesWithoutText(src: File): List<Int> = PDDocument.load(src).use { doc ->
        val stripper = PDFTextStripper()
        (1..doc.numberOfPages).filter { p ->
            stripper.startPage = p
            stripper.endPage = p
            stripper.getText(doc).trim().length < 3
        }.map { it - 1 }
    }

    /**
     * Writes [dst] as a copy of [src] with an invisible text layer on every page that has no text.
     * Returns the number of pages recognised (0 when the document is already searchable; [dst] is then not written).
     */
    suspend fun makeSearchable(src: File, dst: File, engine: OcrEngine, onProgress: (done: Int, total: Int) -> Unit): Int {
        val needs = withContext(Dispatchers.IO) { pagesWithoutText(src) }
        if (needs.isEmpty()) return 0
        val recognised = HashMap<Int, Triple<List<OcrLine>, Int, Int>>()
        PdfSession(src).use { session ->
            needs.forEachIndexed { k, page ->
                onProgress(k, needs.size)
                val bmp = session.render(page, OCR_WIDTH)
                try {
                    recognised[page] = Triple(engine.recognize(bmp), bmp.width, bmp.height)
                } finally {
                    bmp.recycle()
                }
            }
        }
        withContext(Dispatchers.IO) { PdfTools.addTextLayer(src, dst, recognised) }
        onProgress(needs.size, needs.size)
        return needs.size
    }
}
