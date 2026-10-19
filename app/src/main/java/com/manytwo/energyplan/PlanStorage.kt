package com.manytwo.energyplan

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves plans, by default where the user can find them without any storage
 * permission:
 *   JPGs -> Pictures/EnergyPlan/   (they show up in the gallery)
 *   PDF, JSON and log -> Documents/EnergyPlan/
 * or, once the user has picked a folder (Save location…), all of them there.
 *
 * Files are named yyyy-mm-dd-Plan_Name with the save date: .Floor.jpg per
 * floor, .Summary.jpg, .pdf (every page, A4), .json and .txt (the log).
 * Saving again on the same day overwrites them.
 * The working plan is also autosaved privately after every change.
 */
object PlanStorage {
    private const val FOLDER = "EnergyPlan"
    val DOC_DIR = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER/"
    val IMG_DIR = "${Environment.DIRECTORY_PICTURES}/$FOLDER/"
    private const val PREF_TREE = "saveTreeUri"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("energyplan", Context.MODE_PRIVATE)

    /** yyyy-mm-dd-Plan_Name: the save date, then the plan name with spaces and unsafe characters as "_". */
    fun baseName(plan: Plan, date: Date = Date()): String {
        val name = plan.name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), "_").ifBlank { "plan" }
        return SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(date) + "-" + name
    }

    // --- save location ---

    /** The folder the user picked, if it's still usable; null means the default folders. */
    fun customFolder(ctx: Context): DocumentFile? {
        val uri = prefs(ctx).getString(PREF_TREE, null)?.let(Uri::parse) ?: return null
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (granted) DocumentFile.fromTreeUri(ctx, uri)?.takeIf { it.canWrite() } else null
    }

    /** Remembers the folder picked with ACTION_OPEN_DOCUMENT_TREE, or goes back to the defaults with null. */
    fun setCustomFolder(ctx: Context, uri: Uri?) {
        val cr = ctx.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        prefs(ctx).getString(PREF_TREE, null)?.let(Uri::parse)?.takeIf { it != uri }?.let { old ->
            runCatching { cr.releasePersistableUriPermission(old, flags) }
        }
        if (uri == null) {
            prefs(ctx).edit().remove(PREF_TREE).apply()
        } else {
            cr.takePersistableUriPermission(uri, flags)
            prefs(ctx).edit().putString(PREF_TREE, uri.toString()).apply()
        }
    }

    /** Where files go, for showing to the user. */
    fun locationLabel(ctx: Context): String =
        customFolder(ctx)?.let { "Folder: ${it.name ?: it.uri}" } ?: "Default: $IMG_DIR (JPG) and $DOC_DIR (PDF, JSON, log)"

    // --- saving ---

    /**
     * Writes the JSON, one JPG per page and the PDF of all pages. Returns the
     * file names written. The log is written separately by [saveLog], last,
     * so it records how the save went.
     */
    fun save(ctx: Context, plan: Plan, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<String> {
        val base = baseName(plan)
        // A picked folder is listed once, not once per file: cloud providers are slow to list.
        known = customFolder(ctx)?.listFiles()?.associateBy { it.name ?: "" }?.toMutableMap()
        val written = mutableListOf<String>()
        val pages = PlanRenderer.pages(plan)
        val total = pages.size + 2  // JSON, the pages, the PDF
        fun record(name: String, uri: Uri) {
            written += name
            AppLog.i("Wrote $name ($uri)")
            progress(written.size, total)
        }

        "$base.json".let { name -> record(name, write(ctx, name, "application/json", image = false) { it.write(plan.toJson().toByteArray()) }) }

        for (page in pages) {
            val name = "$base.${page.name}.jpg"
            val bmp = page.bitmap()
            try {
                record(name, write(ctx, name, "image/jpeg", image = true) { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) })
            } finally {
                bmp.recycle()
            }
        }

        // The PDF draws the same pages straight onto A4 (595 × 842 pt), so text and lines stay sharp.
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { i, page ->
                val (pw, ph) = if (page.width > page.height) 842 to 595 else 595 to 842
                val p = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                p.canvas.scale(pw.toFloat() / page.width, ph.toFloat() / page.height)
                page.draw(p.canvas)
                pdf.finishPage(p)
            }
            "$base.pdf".let { name -> record(name, write(ctx, name, "application/pdf", image = false) { pdf.writeTo(it) }) }
        } finally {
            pdf.close()
        }
        removeStalePages(ctx, base, written)
        known = null
        return written
    }

    /** The picked folder's files by name, while a save is running. */
    private var known: MutableMap<String, DocumentFile>? = null

    /**
     * Deletes this plan's page JPGs from the same day that this save didn't
     * write - e.g. a floor that's gone, or a summary that now fits on fewer
     * pages - so the folder only holds the current set.
     */
    private fun removeStalePages(ctx: Context, base: String, written: List<String>) {
        fun stale(name: String?) = name != null && name.startsWith("$base.") && name.endsWith(".jpg") && name !in written
        val folder = customFolder(ctx)
        if (folder != null) {
            for (f in known?.values ?: folder.listFiles().toList()) if (stale(f.name) && f.delete()) AppLog.i("Removed old ${f.name}")
            return
        }
        val cr = ctx.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        cr.query(
            collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(IMG_DIR, "$base.%.jpg"), null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1)
                if (!stale(name)) continue
                // Scoped storage only lets us delete files this install created; others are left alone.
                runCatching { cr.delete(ContentUris.withAppendedId(collection, cur.getLong(0)), null, null) }
                    .onSuccess { if (it > 0) AppLog.i("Removed old $name") }
            }
        }
    }

    /** Copies the app log next to the other files, as yyyy-mm-dd-Plan_Name.txt. */
    fun saveLog(ctx: Context, plan: Plan): Uri =
        write(ctx, "${baseName(plan)}.txt", "text/plain", image = false) { it.write(AppLog.text().toByteArray()) }

    fun readText(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open file")

    private fun autosaveFile(ctx: Context) = File(ctx.filesDir, "current.json")

    fun autosave(ctx: Context, plan: Plan) = autosaveFile(ctx).writeText(plan.toJson())

    fun loadAutosave(ctx: Context): Plan? = try {
        autosaveFile(ctx).takeIf { it.exists() }?.let { Plan.fromJson(it.readText()) }
    } catch (e: Exception) {
        AppLog.e("Autosaved plan could not be read - starting a new one", e)
        null
    }

    /** Writes one file to the picked folder, or else to the default folder for its kind. */
    private fun write(ctx: Context, name: String, mime: String, image: Boolean, block: (OutputStream) -> Unit): Uri {
        val folder = customFolder(ctx)
        if (folder != null) {
            // A generic type stops the provider adding its own extension to a name that already has one.
            val file = (known?.get(name) ?: if (known == null) folder.findFile(name) else null)
                ?: folder.createFile(if (mime == "application/json") "application/octet-stream" else mime, name)?.also { known?.put(name, it) }
                ?: throw IOException("Could not create $name in ${folder.name}")
            (ctx.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException("Could not write $name")).use(block)
            return file.uri
        }
        return if (image) {
            writeMedia(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, mime, IMG_DIR, block)
        } else {
            writeMedia(ctx, MediaStore.Files.getContentUri("external"), name, mime, DOC_DIR, block)
        }
    }

    private fun writeMedia(
        ctx: Context, collection: Uri, displayName: String, mime: String, relPath: String,
        write: (OutputStream) -> Unit,
    ): Uri {
        val cr = ctx.contentResolver
        // Only finds files this install created - that's all scoped storage lets us overwrite anyway.
        val existing = cr.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relPath, displayName), null,
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }

        val uri = existing ?: cr.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("Could not create $displayName")

        (cr.openOutputStream(uri, "wt") ?: throw IOException("Could not write $displayName")).use(write)

        if (existing == null) {
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    }
}
