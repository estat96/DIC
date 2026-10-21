package com.dic.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream

/** Результаты пишутся либо в выбранную пользователем папку (SAF), либо в папку приложения. */
class Storage(private val ctx: Context, private val settings: Settings) {
    data class Item(val name: String, val size: Long, val time: Long, val uri: Uri)

    fun defaultDir(): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "results").apply { mkdirs() }

    private fun tree(): DocumentFile? = settings.folderUri?.let {
        val d = DocumentFile.fromTreeUri(ctx, Uri.parse(it))
        if (d != null && d.canWrite()) d else null
    }

    fun folderLabel(): String = tree()?.let { "📁 " + (it.name ?: it.uri.toString()) }
        ?: ("App folder: " + defaultDir().absolutePath)

    fun create(name: String): Pair<OutputStream, Uri> {
        tree()?.let { t ->
            val f = t.createFile("text/csv", name) ?: error("Cannot create a file in the selected folder")
            return ctx.contentResolver.openOutputStream(f.uri)!! to f.uri
        }
        val f = File(defaultDir(), name)
        return f.outputStream() to uriFor(f)
    }

    fun list(): List<Item> {
        val t = tree()
        val items = if (t != null) t.listFiles().filter { it.name?.endsWith(".csv") == true }
            .map { Item(it.name!!, it.length(), it.lastModified(), it.uri) }
        else defaultDir().listFiles().orEmpty().filter { it.name.endsWith(".csv") }
            .map { Item(it.name, it.length(), it.lastModified(), uriFor(it)) }
        return items.sortedByDescending { it.time }
    }

    fun delete(i: Item) {
        DocumentFile.fromSingleUri(ctx, i.uri)?.delete()
        File(defaultDir(), i.name).takeIf { tree() == null }?.delete()
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(ctx, "com.dic.app.files", f)
}
