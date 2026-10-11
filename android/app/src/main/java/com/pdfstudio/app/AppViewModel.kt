package com.pdfstudio.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfstudio.app.data.Annot
import com.pdfstudio.app.data.DocInfo
import com.pdfstudio.app.data.DocRepo
import com.pdfstudio.app.edit.EditOp
import com.pdfstudio.app.edit.ImageBox
import com.pdfstudio.app.edit.InfoFields
import com.pdfstudio.app.edit.PageContent
import com.pdfstudio.app.edit.PdfAnalyzer
import com.pdfstudio.app.edit.PdfEditor
import com.pdfstudio.app.pdf.OcrEngine
import com.pdfstudio.app.pdf.OcrPipeline
import com.pdfstudio.app.pdf.PdfSession
import com.pdfstudio.app.pdf.PdfaExporter
import com.pdfstudio.app.pdf.PdfTools
import com.pdfstudio.app.pdf.RangeParser
import com.pdfstudio.app.pdf.SearchHit
import com.pdfstudio.app.sync.FolderSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A user-visible message; [args] are substituted into string resource [res]. */
data class Msg(val res: Int, val args: List<Any> = emptyList(), val raw: String? = null)

enum class ToolOp { Split, Reorder, Watermark, Protect }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    val repo = DocRepo(app)
    private val sync = FolderSync(app, repo)

    var docs by mutableStateOf(repo.list()); private set
    var openDoc by mutableStateOf<DocInfo?>(null); private set
    var session by mutableStateOf<PdfSession?>(null); private set
    var version by mutableStateOf(1); private set
    var bookmarks by mutableStateOf<Set<Int>>(emptySet()); private set
    var hits by mutableStateOf<List<SearchHit>>(emptyList()); private set
    var message by mutableStateOf<Msg?>(null)
    var needsPassword by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    /** (done, total) while OCR or PDF/A export is running. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    val annots = mutableStateListOf<Annot>()
    /** Pending content edits (text, images); applied together by [saveEdits]. */
    val edits = mutableStateListOf<EditOp>()
    private val contentCache = HashMap<Pair<Int, Int>, PageContent>()

    private var ocr: OcrEngine? = null
    private fun engine(): OcrEngine = ocr ?: OcrEngine().also { ocr = it }

    init { PdfTools.init(app) }

    private fun fail(e: Throwable) {
        message = Msg(R.string.error_generic, raw = e.message ?: e.javaClass.simpleName)
    }

    private fun refresh() { docs = repo.list() }

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { withContext(Dispatchers.IO) { block() } } catch (e: Throwable) { fail(e) } finally { busy = false; progress = null }
    }

    // ---- library -------------------------------------------------------------------------------

    fun importAndOpen(uri: Uri) = launchIo {
        val info = repo.import(uri)
        withContext(Dispatchers.Main) { refresh(); open(info) }
    }

    fun deleteDoc(id: String) { repo.delete(id); repo.clearBookmarks(id); refresh() }

    // ---- viewer --------------------------------------------------------------------------------

    fun open(info: DocInfo) {
        closeDoc()
        openDoc = info
        repo.touch(info.id)
        loadCurrent()
    }

    private fun loadCurrent() {
        val d = openDoc ?: return
        session?.close(); session = null
        val (n, file) = repo.current(d.id)
        version = n
        contentCache.clear()
        bookmarks = repo.bookmarks(d.id)
        hits = emptyList()
        try {
            session = PdfSession(file)
            needsPassword = false
        } catch (e: SecurityException) {
            needsPassword = true
        } catch (e: Throwable) {
            fail(e)
            closeDoc()
        }
    }

    fun unlock(password: String) = launchIo {
        val d = openDoc ?: return@launchIo
        val (_, dst) = repo.nextVersion(d.id)
        PdfTools.decrypt(repo.current(d.id).second, password, dst)
        withContext(Dispatchers.Main) { loadCurrent() }
    }

    fun closeDoc() {
        session?.close(); session = null
        openDoc = null
        annots.clear(); edits.clear(); hits = emptyList(); needsPassword = false
        refresh()
    }

    fun toggleBookmark(page: Int) { openDoc?.let { bookmarks = repo.toggleBookmark(it.id, page) } }

    fun search(q: String) = launchIo {
        val d = openDoc ?: return@launchIo
        val r = PdfTools.search(repo.current(d.id).second, q)
        withContext(Dispatchers.Main) { hits = r }
    }

    // ---- annotations ---------------------------------------------------------------------------

    fun addAnnot(a: Annot) { annots.add(a) }
    fun undoAnnot() { if (annots.isNotEmpty()) annots.removeAt(annots.lastIndex) }

    fun saveAnnotations() = launchIo {
        val d = openDoc ?: return@launchIo
        if (annots.isEmpty()) return@launchIo
        val snapshot = annots.toList()
        val (n, dst) = repo.nextVersion(d.id)
        PdfTools.burnAnnotations(repo.current(d.id).second, snapshot, dst)
        withContext(Dispatchers.Main) { annots.clear(); loadCurrent(); message = Msg(R.string.saved_version, listOf(n)) }
    }

    // ---- page tools (each creates a new version) -----------------------------------------------

    fun mergeWith(uri: Uri) = launchIo {
        val d = openDoc ?: return@launchIo
        val other = File(ctx.cacheDir, "merge_in.pdf")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> other.outputStream().use { i.copyTo(it) } }
        val (n, dst) = repo.nextVersion(d.id)
        PdfTools.merge(listOf(repo.current(d.id).second, other), dst)
        other.delete()
        withContext(Dispatchers.Main) { annots.clear(); edits.clear(); loadCurrent(); message = Msg(R.string.saved_version, listOf(n)) }
    }

    fun runTool(op: ToolOp, input: String) = launchIo {
        val d = openDoc ?: return@launchIo
        val s = session ?: return@launchIo
        val src = repo.current(d.id).second
        when (op) {
            ToolOp.Split -> {
                val ranges = RangeParser.ranges(input, s.pageCount)
                ranges.forEachIndexed { i, r ->
                    val tmp = File(ctx.cacheDir, "split_$i.pdf")
                    PdfTools.pages(src, r.toList(), tmp)
                    repo.import("${d.name.removeSuffix(".pdf")}_part${i + 1}.pdf") { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                }
                withContext(Dispatchers.Main) { refresh(); message = Msg(R.string.split_saved, listOf(ranges.size)) }
            }
            else -> {
                val (n, dst) = repo.nextVersion(d.id)
                when (op) {
                    ToolOp.Reorder -> PdfTools.pages(src, RangeParser.order(input, s.pageCount), dst)
                    ToolOp.Watermark -> PdfTools.watermark(src, input, dst)
                    ToolOp.Protect -> PdfTools.protect(src, input, dst)
                    ToolOp.Split -> Unit
                }
                withContext(Dispatchers.Main) {
                    annots.clear(); edits.clear(); bookmarks = emptySet(); repo.clearBookmarks(d.id)
                    loadCurrent(); message = Msg(R.string.saved_version, listOf(n))
                }
            }
        }
    }

    // ---- content editing -----------------------------------------------------------------------

    /** Editable text lines and images of a page (cached per version). */
    suspend fun pageContent(page: Int): PageContent {
        contentCache[version to page]?.let { return it }
        val d = openDoc ?: return PageContent(emptyList(), emptyList())
        val file = repo.current(d.id).second
        val c = withContext(Dispatchers.IO) { runCatching { PdfAnalyzer.analyze(file, page) }.getOrDefault(PageContent(emptyList(), emptyList())) }
        contentCache[version to page] = c
        return c
    }

    fun addEdit(op: EditOp) { edits.add(op) }
    fun discardEdits() { edits.clear() }

    /** Queues [op] and saves it as a new version straight away, so the user sees the result immediately. */
    fun applyEdit(op: EditOp) {
        val s = session
        viewModelScope.launch {
            // If the old text has to be painted over, use the page's own background rather than assuming white.
            val prepared = if (op is EditOp.ReplaceText && s != null) {
                op.copy(cover = runCatching { sampleBackground(s, op) }.getOrDefault(0xFFFFFF))
            } else op
            edits.add(prepared)
            saveEdits()
        }
    }

    private suspend fun sampleBackground(s: PdfSession, op: EditOp.ReplaceText): Int {
        val bmp = s.render(op.page, 700)
        try {
            val r = op.run
            val midY = (r.top + r.bottom) / 2
            val midX = (r.left + r.right) / 2
            val points = listOf(r.left - 0.006f to midY, r.right + 0.006f to midY, midX to r.top - 0.004f, midX to r.bottom + 0.004f)
            val colors = points.map { (x, y) ->
                bmp.getPixel((x * bmp.width).toInt().coerceIn(0, bmp.width - 1), (y * bmp.height).toInt().coerceIn(0, bmp.height - 1))
            }
            fun median(shift: Int) = colors.map { (it shr shift) and 0xFF }.sorted().let { (it[1] + it[2]) / 2 }
            return (median(16) shl 16) or (median(8) shl 8) or median(0)
        } finally {
            bmp.recycle()
        }
    }

    /** Reads a picked image and queues it, centred on ([nx], [ny]) or fitted into [replaces]. */
    fun placeImage(uri: Uri, page: Int, nx: Float, ny: Float, replaces: ImageBox?) = launchIo {
        val s = session ?: return@launchIo
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launchIo
        require(bytes.size <= 25 * 1024 * 1024) { "Image is larger than 25 MB" }
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        require(o.outWidth > 0 && o.outHeight > 0) { "Unsupported image" }
        val (wPt, hPt) = s.size(page)
        val imgAspect = o.outHeight.toFloat() / o.outWidth * wPt / hPt // height/width in normalised page units
        val (l, t, r, b) = if (replaces != null) {
            val bw = replaces.right - replaces.left
            val bh = replaces.bottom - replaces.top
            val fitW = minOf(bw, bh / imgAspect)
            val fitH = fitW * imgAspect
            val cx = (replaces.left + replaces.right) / 2
            val cy = (replaces.top + replaces.bottom) / 2
            listOf(cx - fitW / 2, cy - fitH / 2, cx + fitW / 2, cy + fitH / 2)
        } else {
            val w = 0.4f
            val h = w * imgAspect
            val left = (nx - w / 2).coerceIn(0f, 1f - w)
            val top = (ny - h / 2).coerceIn(0f, (1f - h).coerceAtLeast(0f))
            listOf(left, top, left + w, top + h)
        }
        withContext(Dispatchers.Main) { edits.add(EditOp.PlaceImage(page, l, t, r, b, bytes, replaces)); saveEdits() }
    }

    fun saveEdits() = launchIo {
        val d = openDoc ?: return@launchIo
        if (edits.isEmpty()) return@launchIo
        val ops = edits.toList()
        val (n, dst) = repo.nextVersion(d.id)
        val r = PdfEditor.apply(repo.current(d.id).second, dst, ops)
        withContext(Dispatchers.Main) {
            edits.clear(); loadCurrent()
            message = if (r.covered > 0) Msg(R.string.edit_saved_covered, listOf(r.applied, r.covered, n))
            else Msg(R.string.edit_saved, listOf(r.applied, n))
        }
    }

    suspend fun readInfo(): InfoFields? {
        val file = openDoc?.let { repo.current(it.id).second } ?: return null
        return withContext(Dispatchers.IO) { runCatching { PdfEditor.readInfo(file) }.getOrNull() }
    }

    fun saveInfo(fields: InfoFields) = launchIo {
        val d = openDoc ?: return@launchIo
        val (n, dst) = repo.nextVersion(d.id)
        PdfEditor.writeInfo(repo.current(d.id).second, dst, fields)
        withContext(Dispatchers.Main) { loadCurrent(); message = Msg(R.string.saved_version, listOf(n)) }
    }

    // ---- OCR and archival export ---------------------------------------------------------------

    private fun report(done: Int, total: Int) { progress = done to total }

    /** Adds an invisible text layer to scanned pages so the document becomes searchable. */
    fun runOcr() = launchIo {
        val d = openDoc ?: return@launchIo
        val (_, src) = repo.current(d.id)
        val (n, dst) = repo.nextVersion(d.id)
        val pages = OcrPipeline.makeSearchable(src, dst, engine(), ::report)
        withContext(Dispatchers.Main) {
            if (pages == 0) {
                message = Msg(R.string.ocr_nothing)
            } else {
                loadCurrent()
                message = Msg(R.string.ocr_done, listOf(pages, n))
            }
        }
    }

    /** Writes a PDF/A-2b copy into the library as a new document. */
    fun exportPdfA(keepSearchable: Boolean) = launchIo {
        val d = openDoc ?: return@launchIo
        val tmp = File(ctx.cacheDir, "pdfa_out.pdf")
        try {
            PdfaExporter.export(
                src = repo.current(d.id).second, dst = tmp,
                title = d.name.removeSuffix(".pdf"),
                ocr = if (keepSearchable) engine() else null,
                sRgbProfile = { ctx.assets.open("sRGB.icc") },
                onProgress = ::report
            )
            val name = d.name.removeSuffix(".pdf") + "_PDFA.pdf"
            repo.import(name) { out -> tmp.inputStream().use { it.copyTo(out) } }
            withContext(Dispatchers.Main) { refresh(); message = Msg(R.string.pdfa_done, listOf(name)) }
        } finally {
            tmp.delete()
        }
    }

    // ---- versions ------------------------------------------------------------------------------

    fun versionList() = openDoc?.let { repo.versions(it.id).map { v -> v.first } } ?: emptyList()

    fun restore(v: Int) {
        val d = openDoc ?: return
        val n = repo.restore(d.id, v)
        annots.clear(); edits.clear()
        loadCurrent()
        message = Msg(R.string.saved_version, listOf(n))
    }

    // ---- sharing -------------------------------------------------------------------------------

    private fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun sharePdf() = launchIo {
        val d = openDoc ?: return@launchIo
        val f = File(ctx.cacheDir, "share").apply { mkdirs() }.let { File(it, d.name) }
        repo.current(d.id).second.copyTo(f, overwrite = true)
        withContext(Dispatchers.Main) { shareFile(f, "application/pdf") }
    }

    fun sharePagePng(page: Int) = launchIo {
        val s = session ?: return@launchIo
        val bmp = s.render(page, 1600)
        val f = File(ctx.cacheDir, "share").apply { mkdirs() }.let { File(it, "page_${page + 1}.png") }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        withContext(Dispatchers.Main) { shareFile(f, "image/png") }
    }

    // ---- cloud sync ----------------------------------------------------------------------------

    fun setSyncFolder(uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        repo.syncFolder = uri.toString()
        message = Msg(R.string.done)
    }

    fun hasSyncFolder() = sync.hasFolder()

    fun syncPush() = launchIo {
        val d = openDoc ?: return@launchIo
        val n = sync.push(d.id)
        withContext(Dispatchers.Main) { message = if (n == 0 && !sync.hasFolder()) Msg(R.string.sync_none) else Msg(R.string.sync_done, listOf(n)) }
    }

    fun syncPull() = launchIo {
        val n = sync.pull()
        withContext(Dispatchers.Main) { refresh(); message = if (!sync.hasFolder()) Msg(R.string.sync_none) else Msg(R.string.sync_done, listOf(n)) }
    }

    override fun onCleared() { session?.close(); ocr?.close() }
}
