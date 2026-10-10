package com.pdfstudio.app.data

data class DocInfo(val id: String, val name: String, val opened: Long)

enum class Tool { None, Ink, Highlight, Edit }

/**
 * A freehand stroke or highlight on one page. Coordinates are normalised to 0..1 with the origin at
 * the top-left of the page, so they are independent of render resolution.
 * Ink: [pts] is x0,y0,x1,y1,... Highlight: [pts] is x0,y0,x1,y1 (two opposite corners).
 */
data class Annot(val page: Int, val tool: Tool, val color: Int, val pts: List<Float>)
