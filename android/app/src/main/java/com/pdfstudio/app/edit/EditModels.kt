package com.pdfstudio.app.edit

/**
 * A line of text on a page. All coordinates are normalised to 0..1 against the page's crop box with the
 * origin at the top-left, so they do not depend on render resolution.
 */
data class TextRun(
    val text: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    /** y of the text baseline. */
    val baseline: Float,
    val fontSizePt: Float
)

/** A top-level image drawn on the page; [name] is its resource name in the page's XObject dictionary. */
data class ImageBox(val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

data class PageContent(val runs: List<TextRun>, val images: List<ImageBox>)

/** One pending change. Nothing touches the file until the user saves; then all ops become one new version. */
sealed interface EditOp {
    val page: Int

    /** New text with its top-left corner at ([x], [y]). */
    data class AddText(override val page: Int, val x: Float, val y: Float, val text: String, val sizePt: Float) : EditOp

    data class ReplaceText(override val page: Int, val run: TextRun, val newText: String) : EditOp

    data class DeleteImage(override val page: Int, val image: ImageBox) : EditOp

    /** Draws [bytes] (JPEG/PNG) inside the box; with [replaces] set the old image is removed first. */
    data class PlaceImage(
        override val page: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val bytes: ByteArray,
        val replaces: ImageBox? = null
    ) : EditOp
}

data class EditResult(val applied: Int, val replacedInPlace: Int, val covered: Int)

data class InfoFields(val title: String, val author: String, val subject: String, val keywords: String)
