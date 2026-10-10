package com.pdfstudio.app.edit

import android.graphics.BitmapFactory
import com.pdfstudio.app.pdf.TextLayer
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Applies [EditOp]s to a PDF and writes the result to a new file; the input is never modified.
 *
 * Text replacement has two tiers:
 *  1. In place: when exactly one text-showing operator on the page decodes to the old text, its string is
 *     swapped for the new one, so the old text is genuinely gone from the file.
 *  2. Cover and overwrite: otherwise (several matches, subset fonts that lack the new glyphs, text split
 *     across operators) a white box is drawn over the old text and the new text on top. The OLD TEXT STAYS IN
 *     THE FILE and can still be selected or extracted. Do not rely on this tier to remove sensitive content.
 */
object PdfEditor {

    fun apply(src: File, dst: File, ops: List<EditOp>): EditResult {
        var covered = 0
        // Identity-based: two identical ops on the same text are still two ops.
        val doneInPlace = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EditOp, Boolean>())
        PDDocument.load(src).use { doc ->
            val font = TextLayer.loadFont(doc, requireEmbedded = false)
            // Stream rewrites first, then drawing, so appended content is never lost by a rewrite.
            for (op in ops) {
                when (op) {
                    is EditOp.ReplaceText -> if (replaceInStream(doc, doc.getPage(op.page), op.run.text, op.newText)) doneInPlace.add(op)
                    is EditOp.DeleteImage -> removeImage(doc, doc.getPage(op.page), op.image.name)
                    is EditOp.PlaceImage -> op.replaces?.let { removeImage(doc, doc.getPage(op.page), it.name) }
                    is EditOp.AddText -> Unit
                }
            }
            for (op in ops) {
                val page = doc.getPage(op.page)
                val box = page.cropBox
                fun px(x: Float) = box.lowerLeftX + x * box.width
                fun py(y: Float) = box.upperRightY - y * box.height
                when (op) {
                    is EditOp.ReplaceText -> {
                        if (op !in doneInPlace) {
                            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                cs.setNonStrokingColor(255, 255, 255)
                                val x = px(op.run.left) - 1f
                                val y = py(op.run.bottom)
                                cs.addRect(x, y, (op.run.right - op.run.left) * box.width + 2f, (op.run.bottom - op.run.top) * box.height)
                                cs.fill()
                                drawText(cs, font, op.newText, px(op.run.left), py(op.run.baseline), op.run.fontSizePt)
                            }
                            covered++
                        }
                    }
                    is EditOp.AddText -> PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        drawText(cs, font, op.text, px(op.x), py(op.y) - op.sizePt, op.sizePt)
                    }
                    is EditOp.PlaceImage -> {
                        val image = toImage(doc, op.bytes)
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.drawImage(image, px(op.left), py(op.bottom), (op.right - op.left) * box.width, (op.bottom - op.top) * box.height)
                        }
                    }
                    is EditOp.DeleteImage -> Unit
                }
            }
            doc.save(dst)
        }
        return EditResult(ops.size, doneInPlace.size, covered)
    }

    private fun drawText(cs: PDPageContentStream, font: PDFont, text: String, x: Float, baseline: Float, size: Float) {
        val clean = text.filter { !it.isISOControl() && runCatching { font.encode(it.toString()) }.isSuccess }
        if (clean.isBlank()) return
        cs.setNonStrokingColor(0, 0, 0)
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, baseline)
        cs.showText(clean)
        cs.endText()
    }

    private fun toImage(doc: PDDocument, bytes: ByteArray): PDImageXObject {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2400) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Unsupported image")
        return try {
            if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.85f)
        } finally {
            bmp.recycle()
        }
    }

    // ---- content stream editing ----------------------------------------------------------------

    private fun tokensOf(page: PDPage): MutableList<Any> {
        val parser = PDFStreamParser(page)
        parser.parse()
        return parser.tokens.toMutableList<Any>()
    }

    private fun writeTokens(doc: PDDocument, page: PDPage, tokens: List<Any>) {
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(stream)
    }

    private fun decode(font: PDFont, bytes: ByteArray): String {
        val sb = StringBuilder()
        val input = ByteArrayInputStream(bytes)
        while (input.available() > 0) sb.append(font.toUnicode(font.readCode(input)) ?: "")
        return sb.toString()
    }

    private class Hit(val index: Int, val isArray: Boolean, val font: PDFont)

    private fun replaceInStream(doc: PDDocument, page: PDPage, old: String, new: String): Boolean {
        val resources = page.resources ?: return false
        val tokens = runCatching { tokensOf(page) }.getOrNull() ?: return false
        var font: PDFont? = null
        val hits = ArrayList<Hit>()
        for ((i, t) in tokens.withIndex()) {
            if (t !is Operator) continue
            when (t.name) {
                "Tf" -> font = (tokens.getOrNull(i - 2) as? COSName)?.let { runCatching { resources.getFont(it) }.getOrNull() }
                "Tj" -> {
                    val s = tokens.getOrNull(i - 1) as? COSString
                    val f = font
                    if (s != null && f != null && runCatching { decode(f, s.bytes) }.getOrNull() == old) hits += Hit(i, false, f)
                }
                "TJ" -> {
                    val a = tokens.getOrNull(i - 1) as? COSArray
                    val f = font
                    if (a != null && f != null) {
                        val joined = runCatching {
                            a.filterIsInstance<COSString>().joinToString("") { decode(f, it.bytes) }
                        }.getOrNull()
                        if (joined == old) hits += Hit(i, true, f)
                    }
                }
            }
        }
        // More than one match means we cannot tell which one the user picked.
        if (hits.size != 1) return false
        val hit = hits[0]
        val encoded = try { hit.font.encode(new) } catch (_: Exception) { return false }
        val replacement = COSString(encoded)
        tokens[hit.index - 1] = if (hit.isArray) COSArray().apply { add(replacement) } else replacement
        writeTokens(doc, page, tokens)
        return true
    }

    private fun removeImage(doc: PDDocument, page: PDPage, name: String) {
        val tokens = tokensOf(page)
        var changed = false
        var i = 1
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Operator && t.name == "Do" && (tokens[i - 1] as? COSName)?.name == name) {
                tokens.removeAt(i)
                tokens.removeAt(i - 1)
                changed = true
                i--
            } else {
                i++
            }
        }
        if (changed) writeTokens(doc, page, tokens)
    }

    // ---- metadata ------------------------------------------------------------------------------

    fun readInfo(file: File): InfoFields = PDDocument.load(file).use { doc ->
        val i = doc.documentInformation
        InfoFields(i.title.orEmpty(), i.author.orEmpty(), i.subject.orEmpty(), i.keywords.orEmpty())
    }

    /** Updates the document information dictionary. Any embedded XMP packet is left untouched. */
    fun writeInfo(src: File, dst: File, fields: InfoFields) {
        PDDocument.load(src).use { doc ->
            doc.documentInformation.apply {
                title = fields.title.ifBlank { null }
                author = fields.author.ifBlank { null }
                subject = fields.subject.ifBlank { null }
                keywords = fields.keywords.ifBlank { null }
            }
            doc.save(dst)
        }
    }
}
