package com.pdfstudio.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local, offline-first document store. Every document lives in filesDir/docs/<id>/ as immutable
 * versions v1.pdf, v2.pdf, ... Edits never overwrite a file, they create the next version, which
 * gives version history and restore for free.
 */
class DocRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pdfstudio", Context.MODE_PRIVATE)
    private val root = File(ctx.filesDir, "docs").apply { mkdirs() }

    private fun dir(id: String) = File(root, id)

    // ---- library -------------------------------------------------------------------------------

    fun list(): List<DocInfo> {
        val arr = JSONArray(prefs.getString("recents", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DocInfo(o.getString("id"), o.getString("name"), o.getLong("opened"))
        }.filter { dir(it.id).exists() }.sortedByDescending { it.opened }
    }

    private fun saveList(list: List<DocInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("opened", it.opened))
        }
        prefs.edit().putString("recents", arr.toString()).apply()
    }

    fun touch(id: String) = saveList(list().map { if (it.id == id) it.copy(opened = System.currentTimeMillis()) else it })

    fun import(uri: Uri): DocInfo {
        val name = displayName(uri) ?: "document.pdf"
        return import(name) { out ->
            ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                ?: throw IllegalStateException("Cannot read $uri")
        }
    }

    fun import(name: String, write: (java.io.OutputStream) -> Unit): DocInfo {
        val id = UUID.randomUUID().toString()
        val d = dir(id).apply { mkdirs() }
        File(d, "v1.pdf").outputStream().use(write)
        val info = DocInfo(id, name, System.currentTimeMillis())
        saveList(list() + info)
        return info
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        saveList(list().filter { it.id != id })
    }

    fun name(id: String) = list().firstOrNull { it.id == id }?.name ?: "document.pdf"

    private fun displayName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ---- versions ------------------------------------------------------------------------------

    private val versionName = Regex("v(\\d+)\\.pdf")

    fun versions(id: String): List<Pair<Int, File>> =
        (dir(id).listFiles() ?: emptyArray()).mapNotNull { f ->
            versionName.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f }
        }.sortedBy { it.first }

    fun current(id: String): Pair<Int, File> = versions(id).last()

    /** File to write the next version into (not created until the caller writes it). */
    fun nextVersion(id: String): Pair<Int, File> {
        val n = current(id).first + 1
        return n to File(dir(id), "v$n.pdf")
    }

    fun restore(id: String, version: Int): Int {
        val (n, dst) = nextVersion(id)
        versions(id).first { it.first == version }.second.copyTo(dst)
        return n
    }

    // ---- bookmarks (per document, page indices) ------------------------------------------------

    fun bookmarks(id: String): Set<Int> =
        prefs.getStringSet("bm_$id", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    fun toggleBookmark(id: String, page: Int): Set<Int> {
        val now = bookmarks(id).let { if (page in it) it - page else it + page }
        prefs.edit().putStringSet("bm_$id", now.map { it.toString() }.toSet()).apply()
        return now
    }

    fun clearBookmarks(id: String) = prefs.edit().remove("bm_$id").apply()

    // ---- sync folder ---------------------------------------------------------------------------

    var syncFolder: String?
        get() = prefs.getString("sync_folder", null)
        set(v) = prefs.edit().putString("sync_folder", v).apply()
}
