package com.pdfstudio.app.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pdfstudio.app.data.DocRepo

/**
 * Cloud sync through the Storage Access Framework. Google Drive, OneDrive and Dropbox apps all
 * expose their files as document providers, so one user-chosen folder covers all three without
 * shipping OAuth client secrets in the APK. A server-backed sync (see docs) can replace this
 * behind the same two calls.
 *
 * Conflict rule: never overwrite silently. A remote file that is newer than the library copy is
 * imported as a new document named "<name> (remote)".
 */
class FolderSync(private val ctx: Context, private val repo: DocRepo) {

    private fun folder(): DocumentFile? =
        repo.syncFolder?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it)) }?.takeIf { it.canWrite() }

    fun hasFolder() = folder() != null

    /** Uploads the current version of [id]; returns 1 when written. */
    fun push(id: String): Int {
        val dir = folder() ?: return 0
        val name = repo.name(id)
        val target = dir.findFile(name) ?: dir.createFile("application/pdf", name.removeSuffix(".pdf")) ?: return 0
        ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            repo.current(id).second.inputStream().use { it.copyTo(out) }
        } ?: return 0
        return 1
    }

    /** Imports PDFs from the folder that are not in the library or are newer than the local copy. */
    fun pull(): Int {
        val dir = folder() ?: return 0
        val local = repo.list().associateBy { it.name }
        var n = 0
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory || f.type != "application/pdf" && !name.endsWith(".pdf", true)) continue
            val have = local[name]
            if (have != null && f.lastModified() <= have.opened) continue
            val label = if (have != null) name.removeSuffix(".pdf") + " (remote).pdf" else name
            repo.import(label) { out -> ctx.contentResolver.openInputStream(f.uri)?.use { it.copyTo(out) } }
            n++
        }
        return n
    }
}
