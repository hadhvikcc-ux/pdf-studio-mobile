package com.pdfstudio.app.edit

import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Applies [EditOp]s to a PDF and writes the result to a new file; the input is never modified.
 *
 * Replacing a line of text is attempted in three tiers, best first:
 *  1. **Same font.** When the line is a single text operator and the user kept the detected style, the string is
 *     swapped inside the original font (only possible if that font still has the glyphs). The old text is gone.
 *  2. **Re-typeset.** The text operators that drew the line are replaced by an invisible move of the same width, so
 *     the rest of the page does not shift, and the new text is drawn at the same spot in the closest standard font
 *     (Helvetica / Times / Courier, with bold and italic) or, for non-Latin text, a system font. The old text is
 *     really removed from the file, even if its font was a subset.
 *  3. **Cover.** If the glyphs cannot be separated from neighbouring text (shared operators, `'` / `"` operators,
 *     Type 3 fonts, unusual streams) a box in the sampled background colour is painted over the old text and the new
 *     text drawn on top. THE OLD TEXT STAYS IN THE FILE and can still be selected or extracted.
 */
object PdfEditor {
    internal val SHOW_OPERATORS = setOf("Tj", "TJ", "'", "\"")

    private class ShowOp(val tokenIndex: Int, val name: String, val font: PDFont?)

    private enum class Outcome { SameFont, Retyped, Covered }

    fun apply(src: File, dst: File, ops: List<EditOp>): EditResult {
        val outcomes = Collections.newSetFromMap(IdentityHashMap<EditOp, Boolean>())
        val retyped = Collections.newSetFromMap(IdentityHashMap<EditOp, Boolean>())
        var sameFont = 0
        var covered = 0
        PDDocument.load(src).use { doc ->
            // Pass 1: rewrite page content streams (remove old text / images). Drawing comes after so that
            // appended content is never lost by a rewrite.
            for ((pageIndex, list) in ops.groupBy { it.page }) {
                val page = doc.getPage(pageIndex)
                val tokens = runCatching { tokensOf(page) }.getOrNull()
                var changed = false
                val showOps = if (tokens != null) showOps(tokens, page.resources) else emptyList()
                for (op in list) {
                    when (op) {
                        is EditOp.ReplaceText -> {
                            val outcome = if (tokens == null) Outcome.Covered else replaceText(tokens, showOps, op)
                            if (outcome != Outcome.Covered) changed = true
                            when (outcome) {
                                Outcome.SameFont -> { sameFont++; outcomes.add(op) }
                                Outcome.Retyped -> retyped.add(op)
                                Outcome.Covered -> covered++
                            }
                        }
                        is EditOp.DeleteImage -> if (tokens != null) changed = removeImage(tokens, op.image.name) || changed
                        is EditOp.PlaceImage -> op.replaces?.let { if (tokens != null) changed = removeImage(tokens, it.name) || changed }
                        is EditOp.AddText -> Unit
                    }
                }
                if (changed && tokens != null) writeTokens(doc, page, tokens)
            }

            // Pass 2: draw new content.
            for (op in ops) {
                val page = doc.getPage(op.page)
                val box = page.cropBox
                fun px(x: Float) = box.lowerLeftX + x * box.width
                fun py(y: Float) = box.upperRightY - y * box.height
                fun stream() = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                when (op) {
                    is EditOp.ReplaceText -> if (op !in outcomes) {
                        stream().use { cs ->
                            if (op !in retyped) {
                                val r = op.run
                                cs.setNonStrokingColor(((op.cover shr 16) and 0xFF) / 255f, ((op.cover shr 8) and 0xFF) / 255f, (op.cover and 0xFF) / 255f)
                                cs.addRect(px(r.left) - 1f, py(r.bottom), (r.right - r.left) * box.width + 2f, (r.bottom - r.top) * box.height)
                                cs.fill()
                            }
                            drawText(cs, doc, op.style, op.newText, px(op.run.left), py(op.run.baseline))
                        }
                    }
                    is EditOp.AddText -> stream().use { cs ->
                        drawText(cs, doc, op.style, op.text, px(op.x), py(op.y) - op.style.sizePt * 0.8f)
                    }
                    is EditOp.PlaceImage -> {
                        val image = toImage(doc, op.bytes)
                        stream().use { cs ->
                            cs.drawImage(image, px(op.left), py(op.bottom), (op.right - op.left) * box.width, (op.bottom - op.top) * box.height)
                        }
                    }
                    is EditOp.DeleteImage -> Unit
                }
            }
            doc.save(dst)
        }
        return EditResult(ops.size, sameFont, retyped.size, covered)
    }

    // ---- text ----------------------------------------------------------------------------------

    private fun drawText(cs: PDPageContentStream, doc: PDDocument, style: RunStyle, text: String, x: Float, baseline: Float) {
        val lines = text.split('\n')
        val font = FontMatcher.resolve(doc, style, text.replace("\n", " "))
        cs.setNonStrokingColor(((style.rgb shr 16) and 0xFF) / 255f, ((style.rgb shr 8) and 0xFF) / 255f, (style.rgb and 0xFF) / 255f)
        lines.forEachIndexed { i, raw ->
            val line = raw.filter { !it.isISOControl() && runCatching { font.encode(it.toString()) }.isSuccess }
            if (line.isBlank()) return@forEachIndexed
            cs.beginText()
            cs.setFont(font, style.sizePt)
            cs.newLineAtOffset(x, baseline - i * style.sizePt * 1.2f)
            cs.showText(line)
            cs.endText()
        }
    }

    private fun replaceText(tokens: MutableList<Any>, showOps: List<ShowOp>, op: EditOp.ReplaceText): Outcome {
        val run = op.run
        val targets = run.ops.mapNotNull { showOps.getOrNull(it) }

        // Tier 1: same font, one operator, style untouched.
        if (op.style == run.style && run.removable && targets.size == 1 && run.ops.size == 1) {
            val t = targets[0]
            val font = t.font
            // Composite (Type 0) fonts are excluded: their glyph ids cannot be derived from Unicode reliably.
            if (font != null && font !is PDType0Font && t.name in setOf("Tj", "TJ")) {
                val encoded = runCatching { font.encode(op.newText) }.getOrNull()
                if (encoded != null && !op.newText.contains('\n')) {
                    val s = COSString(encoded)
                    if (t.name == "Tj") tokens[t.tokenIndex - 1] = s else tokens[t.tokenIndex - 1] = COSArray().apply { add(s) }
                    return Outcome.SameFont
                }
            }
        }

        // Tier 2: remove the old glyphs but keep their advance so following text does not move.
        if (run.removable && targets.size == run.ops.size && targets.all { it.name == "Tj" || it.name == "TJ" }) {
            val advances = targets.map { t ->
                val font = t.font
                if (font == null || font is PDType3Font) null else advance(font, tokens[t.tokenIndex - 1])
            }
            if (advances.all { it != null }) {
                targets.forEachIndexed { i, t ->
                    tokens[t.tokenIndex - 1] = COSArray().apply { add(COSFloat(-advances[i]!!)) }
                    tokens[t.tokenIndex] = Operator.getOperator("TJ")
                }
                return Outcome.Retyped
            }
        }
        return Outcome.Covered
    }

    /** Horizontal advance of a Tj string / TJ array in thousandths of the font size (word and character spacing ignored). */
    private fun advance(font: PDFont, operand: Any): Float? = runCatching {
        var total = 0f
        fun add(s: COSString) {
            val input = ByteArrayInputStream(s.bytes)
            while (input.available() > 0) total += font.getWidth(font.readCode(input))
        }
        when (operand) {
            is COSString -> add(operand)
            is COSArray -> for (e in operand) when (e) {
                is COSString -> add(e)
                is COSNumber -> total -= e.floatValue()
                else -> Unit
            }
            else -> return@runCatching null
        }
        total
    }.getOrNull()

    private fun showOps(tokens: List<Any>, resources: PDResources?): List<ShowOp> {
        val result = ArrayList<ShowOp>()
        var font: PDFont? = null
        val saved = ArrayDeque<PDFont?>()
        for ((i, t) in tokens.withIndex()) {
            if (t !is Operator) continue
            when (t.name) {
                "q" -> saved.addLast(font)
                "Q" -> if (saved.isNotEmpty()) font = saved.removeLast()
                "Tf" -> font = (tokens.getOrNull(i - 2) as? COSName)?.let { name -> runCatching { resources?.getFont(name) }.getOrNull() }
                in SHOW_OPERATORS -> result += ShowOp(i, t.name, font)
            }
        }
        return result
    }

    // ---- images --------------------------------------------------------------------------------

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

    private fun removeImage(tokens: MutableList<Any>, name: String): Boolean {
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
        return changed
    }

    // ---- content stream plumbing ---------------------------------------------------------------

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
