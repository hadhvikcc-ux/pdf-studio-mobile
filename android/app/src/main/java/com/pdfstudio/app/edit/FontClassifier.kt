package com.pdfstudio.app.edit

/** Works out family / weight / slant from a PDF font's name and descriptor flags. Pure, so it is unit tested on the JVM. */
object FontClassifier {
    data class Traits(val family: Family, val bold: Boolean, val italic: Boolean)

    private val boldWords = listOf("bold", "black", "heavy", "semibold", "demibold", "extrabold")
    private val monoWords = listOf("courier", "mono", "consolas", "menlo", "typewriter", "lucidaconsole", "inconsolata")
    private val serifWords = listOf(
        "times", "serif", "georgia", "garamond", "cambria", "palatino", "bookman", "minion", "century",
        "baskerville", "didot", "caslon", "charter", "roman", "constantia", "bookantiqua"
    )

    fun classify(
        rawName: String, fixedPitch: Boolean = false, serifFlag: Boolean = false,
        italicFlag: Boolean = false, forceBold: Boolean = false, weight: Float = 0f
    ): Traits {
        // "ABCDEF+TimesNewRomanPS-BoldMT" -> "timesnewromanpsboldmt"
        val name = rawName.substringAfter('+').lowercase().filter { it in 'a'..'z' }
        val bold = forceBold || weight >= 700f || boldWords.any { it in name }
        val italic = italicFlag || "italic" in name || "oblique" in name
        val family = when {
            fixedPitch || monoWords.any { it in name } -> Family.Mono
            "sans" in name -> Family.Sans
            serifFlag || serifWords.any { it in name } -> Family.Serif
            else -> Family.Sans
        }
        return Traits(family, bold, italic)
    }
}
