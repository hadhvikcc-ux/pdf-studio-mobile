package com.pdfstudio.app.pdf

import android.content.Context
import com.pdfstudio.app.data.Annot
import com.pdfstudio.app.data.Tool
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

data class SearchHit(val page: Int, val snippet: String)

/**
 * Document-level operations (search, merge, split, reorder, watermark, encryption, annotation
 * burn-in). All functions are blocking: call them from Dispatchers.IO. Each writes a new file and
 * never modifies its input.
 */
object PdfTools {

    fun init(ctx: Context) = PDFBoxResourceLoader.init(ctx.applicationContext)

    private inline fun <T> PDDocument.useDoc(block: (PDDocument) -> T): T = try { block(this) } finally { close() }

    fun search(file: File, query: String, password: String = ""): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return PDDocument.load(file, password).useDoc { doc ->
            val stripper = PDFTextStripper()
            (1..doc.numberOfPages).mapNotNull { p ->
                stripper.startPage = p
                stripper.endPage = p
                val text = stripper.getText(doc).replace(Regex("\\s+"), " ")
                val i = text.indexOf(query, ignoreCase = true)
                if (i < 0) null
                else SearchHit(p - 1, text.substring((i - 30).coerceAtLeast(0), (i + query.length + 50).coerceAtMost(text.length)))
            }
        }
    }

    fun merge(files: List<File>, dst: File) {
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = dst.absolutePath
        m.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
    }

    /** Copies the given 0-based pages, in order, into a new document (covers split, reorder, delete). */
    fun pages(src: File, order: List<Int>, dst: File) {
        PDDocument.load(src).useDoc { doc ->
            PDDocument().useDoc { out ->
                order.forEach { out.importPage(doc.getPage(it)) }
                out.save(dst)
            }
        }
    }

    fun watermark(src: File, text: String, dst: File) {
        PDDocument.load(src).useDoc { doc ->
            val font = PDType1Font.HELVETICA_BOLD
            val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.2f }
            for (page in doc.pages) {
                val box = page.cropBox
                val size = box.width / (text.length.coerceAtLeast(4) * 0.5f)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setGraphicsStateParameters(gs)
                    cs.setNonStrokingColor(128, 128, 128)
                    cs.beginText()
                    cs.setFont(font, size.coerceIn(24f, 120f))
                    cs.setTextMatrix(
                        Matrix.getRotateInstance(Math.toRadians(45.0), box.lowerLeftX + box.width * 0.2f, box.lowerLeftY + box.height * 0.3f)
                    )
                    cs.showText(text)
                    cs.endText()
                }
            }
            doc.save(dst)
        }
    }

    fun protect(src: File, password: String, dst: File) {
        PDDocument.load(src).useDoc { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            doc.save(dst)
        }
    }

    fun decrypt(src: File, password: String, dst: File) {
        PDDocument.load(src, password).useDoc { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(dst)
        }
    }

    /** Draws annotations into the page content as vector graphics so text stays selectable. */
    fun burnAnnotations(src: File, annots: List<Annot>, dst: File) {
        PDDocument.load(src).useDoc { doc ->
            annots.groupBy { it.page }.forEach { (pageIndex, list) ->
                val page = doc.getPage(pageIndex)
                val box = page.cropBox
                fun px(x: Float) = box.lowerLeftX + x * box.width
                fun py(y: Float) = box.upperRightY - y * box.height
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in list) {
                        val r = (a.color shr 16) and 0xFF
                        val g = (a.color shr 8) and 0xFF
                        val b = a.color and 0xFF
                        cs.saveGraphicsState()
                        when (a.tool) {
                            Tool.Highlight -> {
                                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.35f })
                                cs.setNonStrokingColor(r, g, b)
                                val x0 = px(minOf(a.pts[0], a.pts[2])); val x1 = px(maxOf(a.pts[0], a.pts[2]))
                                val y0 = py(maxOf(a.pts[1], a.pts[3])); val y1 = py(minOf(a.pts[1], a.pts[3]))
                                cs.addRect(x0, y0, x1 - x0, y1 - y0)
                                cs.fill()
                            }
                            Tool.Ink -> {
                                cs.setStrokingColor(r, g, b)
                                cs.setLineWidth(2f)
                                cs.moveTo(px(a.pts[0]), py(a.pts[1]))
                                var i = 2
                                while (i + 1 < a.pts.size) { cs.lineTo(px(a.pts[i]), py(a.pts[i + 1])); i += 2 }
                                cs.stroke()
                            }
                            Tool.None -> Unit
                        }
                        cs.restoreGraphicsState()
                    }
                }
            }
            doc.save(dst)
        }
    }
}
