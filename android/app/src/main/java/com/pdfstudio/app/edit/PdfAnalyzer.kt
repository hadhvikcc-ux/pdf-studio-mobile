package com.pdfstudio.app.edit

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.IdentityHashMap

/** Finds the editable text lines and images on a page. Blocking; call from Dispatchers.IO. */
object PdfAnalyzer {

    fun analyze(file: File, pageIndex: Int): PageContent = PDDocument.load(file).use { doc ->
        val page = doc.getPage(pageIndex)
        val w = page.cropBox.width
        val h = page.cropBox.height
        PageContent(textRuns(doc, page, pageIndex, w, h), images(page, w, h))
    }

    /** Number of text-showing operators (Tj, TJ, ', ") in the page's own content, in order. */
    internal fun showOperatorCount(page: PDPage): Int {
        val parser = PDFStreamParser(page)
        parser.parse()
        return parser.tokens.count { it is Operator && it.name in PdfEditor.SHOW_OPERATORS }
    }

    private class Word(val text: String, val positions: List<TextPosition>)

    /**
     * Collects words into runs and remembers, for every glyph, which text operator of the page produced it
     * (so the editor can delete exactly those operators) and which fill colour it was drawn in.
     */
    private class Collector(val w: Float, val h: Float) : PDFTextStripper() {
        val runs = ArrayList<TextRun>()
        var pageOps = 0

        private var opIndex = -1
        private var formDepth = 0
        private var inAnnotations = false
        private val opOf = IdentityHashMap<TextPosition, Int>()
        private val colorOf = IdentityHashMap<TextPosition, Int>()
        private val line = ArrayList<Word>()
        private val glyphsPerOp = HashMap<Int, Int>()

        override fun showTextString(string: ByteArray) {
            if (formDepth == 0 && !inAnnotations) { opIndex++; pageOps = opIndex + 1 }
            super.showTextString(string)
        }

        override fun showTextStrings(array: COSArray) {
            if (formDepth == 0 && !inAnnotations) { opIndex++; pageOps = opIndex + 1 }
            super.showTextStrings(array)
        }

        override fun showForm(form: PDFormXObject) {
            formDepth++
            try { super.showForm(form) } finally { formDepth-- }
        }

        override fun showAnnotation(annotation: PDAnnotation) {
            inAnnotations = true
            super.showAnnotation(annotation)
        }

        override fun processTextPosition(text: TextPosition) {
            opOf[text] = if (formDepth == 0 && !inAnnotations) opIndex else -1
            colorOf[text] = runCatching { graphicsState.nonStrokingColor.toRGB() and 0xFFFFFF }.getOrDefault(0)
            super.processTextPosition(text)
        }

        // Called once per word; lines are delimited by writeLineSeparator.
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return
            val positions = textPositions.toList()
            line.add(Word(text, positions))
            positions.forEach { glyphsPerOp.merge(opOf[it] ?: -2, 1, Int::plus) }
        }

        override fun writeLineSeparator() {
            flushLine()
            super.writeLineSeparator()
        }

        fun flushLine() {
            // Split a line into runs wherever there is a wide gap (table columns, separate text blocks).
            var group = ArrayList<Word>()
            fun emit() {
                if (group.isEmpty()) return
                runs += buildRun(group)
                group = ArrayList()
            }
            for (word in line) {
                val prev = group.lastOrNull()?.positions?.last()
                if (prev != null) {
                    val gap = word.positions.first().xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                    if (gap > prev.fontSizeInPt * 3f) emit()
                }
                group.add(word)
            }
            emit()
            line.clear()
        }

        private fun buildRun(group: List<Word>): TextRun {
            val all = group.flatMap { it.positions }
            val first = all.first()
            val baseline = all.maxOf { it.yDirAdj }
            val height = all.maxOf { it.heightDir }
            val left = first.xDirAdj
            val right = all.last().let { it.xDirAdj + it.widthDirAdj }

            // An operator belongs to this run only if every glyph it drew on the page is in this run.
            val inRun = HashMap<Int, Int>()
            all.forEach { inRun.merge(opOf[it] ?: -2, 1, Int::plus) }
            val owned = inRun.filter { (op, n) -> op >= 0 && glyphsPerOp[op] == n }.keys.sorted()
            val ownedGlyphs = owned.sumOf { inRun.getValue(it) }

            return TextRun(
                text = group.joinToString(" ") { it.text }.trim(),
                left = left / w, top = (baseline - height) / h,
                right = right / w, bottom = (baseline + height * 0.25f) / h,
                baseline = baseline / h,
                style = FontMatcher.styleOf(first.font, first.fontSizeInPt, colorOf[first] ?: 0),
                ops = owned,
                removable = owned.isNotEmpty() && ownedGlyphs == all.size
            )
        }
    }

    private fun textRuns(doc: PDDocument, page: PDPage, pageIndex: Int, w: Float, h: Float): List<TextRun> {
        val collector = Collector(w, h)
        collector.startPage = pageIndex + 1
        collector.endPage = pageIndex + 1
        collector.getText(doc)
        collector.flushLine()

        // Operator numbering is only trustworthy if the engine saw exactly the operators in the page stream.
        val consistent = runCatching { showOperatorCount(page) == collector.pageOps }.getOrDefault(false)
        return collector.runs.filter { it.text.isNotBlank() }.map {
            if (consistent) it else it.copy(ops = emptyList(), removable = false)
        }
    }

    /** Reports images drawn directly on the page (not those nested inside form XObjects). */
    private fun images(page: PDPage, w: Float, h: Float): List<ImageBox> {
        val found = ArrayList<ImageBox>()
        val engine = object : PDFStreamEngine() {
            override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                if (operator.name == "Do" && operands.isNotEmpty()) {
                    val name = operands[0] as? COSName ?: return
                    val xobject = resources.getXObject(name)
                    if (xobject is PDImageXObject) {
                        val m = graphicsState.currentTransformationMatrix
                        val x = m.translateX
                        val y = m.translateY
                        val iw = m.scalingFactorX
                        val ih = m.scalingFactorY
                        found += ImageBox(
                            name.name,
                            left = (x - page.cropBox.lowerLeftX) / w,
                            top = 1f - (y - page.cropBox.lowerLeftY + ih) / h,
                            right = (x - page.cropBox.lowerLeftX + iw) / w,
                            bottom = 1f - (y - page.cropBox.lowerLeftY) / h
                        )
                    }
                } else {
                    super.processOperator(operator, operands)
                }
            }
        }
        engine.addOperator(Concatenate())
        engine.addOperator(SetGraphicsStateParameters())
        engine.addOperator(Save())
        engine.addOperator(Restore())
        engine.addOperator(SetMatrix())
        engine.processPage(page)
        return found
    }
}
