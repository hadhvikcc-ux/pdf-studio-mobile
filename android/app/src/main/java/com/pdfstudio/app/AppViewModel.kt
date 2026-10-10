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
        annots.clear(); hits = emptyList(); needsPassword = false
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
        withContext(Dispatchers.Main) { annots.clear(); loadCurrent(); message = Msg(R.string.saved_version, listOf(n)) }
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
                    annots.clear(); bookmarks = emptySet(); repo.clearBookmarks(d.id)
                    loadCurrent(); message = Msg(R.string.saved_version, listOf(n))
                }
            }
        }
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
                annots.clear(); loadCurrent()
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
        annots.clear()
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
