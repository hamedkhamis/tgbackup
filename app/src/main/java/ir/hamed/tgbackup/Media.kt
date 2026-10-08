package ir.hamed.tgbackup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(val key: String, val uri: Uri, val name: String, val size: Long, val date: Long, val video: Boolean)

object Media {
    fun all(ctx: Context): List<MediaItem> {
        val list = ArrayList<MediaItem>()
        query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "i", false, list)
        query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "v", true, list)
        return list.sortedBy { it.date }
    }

    private fun query(ctx: Context, base: Uri, prefix: String, video: Boolean, out: MutableList<MediaItem>) {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED
        )
        ctx.contentResolver.query(base, proj, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
            val iSize = c.getColumnIndexOrThrow(proj[2]); val iDate = c.getColumnIndexOrThrow(proj[3])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += MediaItem(
                    "$prefix:$id", ContentUris.withAppendedId(base, id),
                    c.getString(iName) ?: "file_$id", c.getLong(iSize), c.getLong(iDate), video
                )
            }
        }
    }
}
