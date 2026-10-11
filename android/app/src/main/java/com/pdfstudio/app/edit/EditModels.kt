package com.pdfstudio.app.edit

enum class Family { Sans, Serif, Mono }

/** How a piece of text looks. [rgb] is 0xRRGGBB. */
data class RunStyle(
    val family: Family = Family.Sans,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizePt: Float = 14f,
    val rgb: Int = 0x000000
)

/**
 * A line (or a block of a line) of text on a page. Coordinates are normalised to 0..1 against the page's
 * crop box with the origin at the top-left, so they do not depend on render resolution.
 */
data class TextRun(
    val text: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    /** y of the text baseline. */
    val baseline: Float,
    /** Font, size and colour detected from the document. */
    val style: RunStyle,
    /** Indices (in page content order) of the text-showing operators whose glyphs all belong to this run. */
    val ops: List<Int> = emptyList(),
    /** True when [ops] account for every glyph of the run, so the original text can be removed from the file. */
    val removable: Boolean = false
)

/** A top-level image drawn on the page; [name] is its resource name in the page's XObject dictionary. */
data class ImageBox(val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

data class PageContent(val runs: List<TextRun>, val images: List<ImageBox>)

/** One pending change. Nothing touches the file until it is applied; then it becomes one new version. */
sealed interface EditOp {
    val page: Int

    /** New text with its top-left corner at ([x], [y]). */
    data class AddText(override val page: Int, val x: Float, val y: Float, val text: String, val style: RunStyle) : EditOp

    /** [cover] (0xRRGGBB) is the background used if the old text cannot be removed and has to be painted over. */
    data class ReplaceText(
        override val page: Int, val run: TextRun, val newText: String, val style: RunStyle, val cover: Int = 0xFFFFFF
    ) : EditOp

    data class DeleteImage(override val page: Int, val image: ImageBox) : EditOp

    /** Draws [bytes] (JPEG/PNG) inside the box; with [replaces] set the old image is removed first. */
    data class PlaceImage(
        override val page: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val bytes: ByteArray,
        val replaces: ImageBox? = null
    ) : EditOp
}

/**
 * [sameFont]: text swapped inside the original font. [retyped]: original removed from the page, new text set in the
 * closest available font. [covered]: original could not be removed and was painted over (it stays in the file).
 */
data class EditResult(val applied: Int, val sameFont: Int, val retyped: Int, val covered: Int)

data class InfoFields(val title: String, val author: String, val subject: String, val keywords: String)
