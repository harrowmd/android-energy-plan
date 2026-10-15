package com.manytwo.energyplan

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Saves plans where the user can find them without any storage permission:
 *   JSON -> Documents/EnergyPlan/<name>.json
 *   JPG  -> Pictures/EnergyPlan/<name>.jpg   (shows up in the gallery)
 * Saving again under the same name overwrites the earlier files.
 * The working plan is also autosaved privately after every change.
 */
object PlanStorage {
    private const val FOLDER = "EnergyPlan"
    val JSON_DIR = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER/"
    val JPG_DIR = "${Environment.DIRECTORY_PICTURES}/$FOLDER/"

    fun fileBase(name: String) =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "plan" }

    fun saveJson(ctx: Context, plan: Plan): Uri =
        writeMedia(ctx, MediaStore.Files.getContentUri("external"), "${fileBase(plan.name)}.json", "application/json", JSON_DIR) {
            it.write(plan.toJson().toByteArray())
        }

    fun saveJpg(ctx: Context, plan: Plan): Uri {
        val bmp = PlanRenderer.renderExport(plan)
        try {
            return writeMedia(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${fileBase(plan.name)}.jpg", "image/jpeg", JPG_DIR) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
        } finally {
            bmp.recycle()
        }
    }

    fun readText(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open file")

    private fun autosaveFile(ctx: Context) = File(ctx.filesDir, "current.json")

    fun autosave(ctx: Context, plan: Plan) = autosaveFile(ctx).writeText(plan.toJson())

    fun loadAutosave(ctx: Context): Plan? = try {
        autosaveFile(ctx).takeIf { it.exists() }?.let { Plan.fromJson(it.readText()) }
    } catch (e: Exception) {
        null
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
