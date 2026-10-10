package com.pdfstudio.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Thin wrapper over [PdfRenderer]; the renderer allows one open page at a time, hence the mutex. */
class PdfSession(file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()

    val pageCount: Int = renderer.pageCount

    /** Height / width of the first page; used only as a placeholder size before a page renders. */
    val aspect: Float = if (pageCount == 0) 1.4f else renderer.openPage(0).use { it.height.toFloat() / it.width }

    /** Page size in PDF points (1/72 inch). */
    suspend fun size(index: Int): Pair<Int, Int> = lock.withLock {
        renderer.openPage(index).use { it.width to it.height }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            renderer.openPage(index).use { page ->
                val h = (widthPx * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }
}
