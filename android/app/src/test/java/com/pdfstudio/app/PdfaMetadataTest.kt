package com.pdfstudio.app

import com.pdfstudio.app.pdf.PdfaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class PdfaMetadataTest {
    private fun parse(xmp: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xmp.toByteArray(Charsets.UTF_8)))

    @Test fun declaresPdfA2b() {
        val doc = parse(PdfaMetadata.xmp("Report", Date(0)))
        val ns = "http://www.aiim.org/pdfa/ns/id/"
        assertEquals("2", doc.getElementsByTagNameNS(ns, "part").item(0).textContent)
        assertEquals("B", doc.getElementsByTagNameNS(ns, "conformance").item(0).textContent)
    }

    @Test fun escapesTitleAndStaysWellFormed() {
        val xmp = PdfaMetadata.xmp("Q&A <draft> \"v2\"\u0001", Date(0))
        val title = parse(xmp).getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0) as Element
        assertEquals("Q&A <draft> \"v2\"", title.textContent.trim())
    }

    @Test fun startsWithXpacketHeaderWithBom() {
        assertTrue(PdfaMetadata.xmp("x", Date(0)).startsWith("<?xpacket begin=\"﻿\""))
    }
}
