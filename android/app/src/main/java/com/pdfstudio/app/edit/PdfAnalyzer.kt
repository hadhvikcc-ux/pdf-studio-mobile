package com.pdfstudio.app.edit

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** Finds the editable text lines and images on a page. Blocking; call from Dispatchers.IO. */
object PdfAnalyzer {

    fun analyze(file: File, pageIndex: Int): PageContent = PDDocument.load(file).use { doc ->
        val page = doc.getPage(pageIndex)
        val w = page.cropBox.width
        val h = page.cropBox.height
        PageContent(textRuns(doc, pageIndex, w, h), images(page, w, h))
    }

    private class Word(val text: String, val pos: List<TextPosition>)

    private fun textRuns(doc: PDDocument, pageIndex: Int, w: Float, h: Float): List<TextRun> {
        val runs = ArrayList<TextRun>()
        val line = ArrayList<Word>()

        fun flushLine() {
            // Split a line into runs wherever there is a wide gap (table columns, separate text blocks).
            var group = ArrayList<Word>()
            fun emit() {
                if (group.isEmpty()) return
                val all = group.flatMap { it.pos }
                val size = all.first().fontSizeInPt
                val baseline = all.maxOf { it.yDirAdj }
                val height = all.maxOf { it.heightDir }
                val left = all.first().xDirAdj
                val right = all.last().let { it.xDirAdj + it.widthDirAdj }
                runs += TextRun(
                    text = group.joinToString(" ") { it.text }.trim(),
                    left = left / w, top = (baseline - height) / h,
                    right = right / w, bottom = (baseline + height * 0.25f) / h,
                    baseline = baseline / h, fontSizePt = size
                )
                group = ArrayList()
            }
            for (word in line) {
                val prev = group.lastOrNull()?.pos?.last()
                if (prev != null) {
                    val gap = word.pos.first().xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                    if (gap > prev.fontSizeInPt * 3f) emit()
                }
                group.add(word)
            }
            emit()
            line.clear()
        }

        val stripper = object : PDFTextStripper() {
            // Called once per word; lines are delimited by writeLineSeparator.
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                if (text.isNotBlank() && textPositions.isNotEmpty()) line.add(Word(text, textPositions.toList()))
            }

            override fun writeLineSeparator() {
                flushLine()
                super.writeLineSeparator()
            }
        }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)
        flushLine()
        return runs.filter { it.text.isNotBlank() }
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
