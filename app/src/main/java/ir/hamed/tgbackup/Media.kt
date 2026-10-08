package ir.hamed.tgbackup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val key: String, val uri: Uri, val name: String, val size: Long, val date: Long,
    val video: Boolean, val bucket: String, val mime: String, val duration: Long
)

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
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.Video.VideoColumns.DURATION
        )
        val projection = if (video) proj else proj.copyOf(7).requireNoNulls()
        ctx.contentResolver.query(base, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
            val iSize = c.getColumnIndexOrThrow(proj[2]); val iDate = c.getColumnIndexOrThrow(proj[3])
            val iBucket = c.getColumnIndex(proj[4]); val iMime = c.getColumnIndex(proj[5])
            val iTaken = c.getColumnIndex(proj[6]); val iDur = if (video) c.getColumnIndex(proj[7]) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = if (iTaken >= 0 && !c.isNull(iTaken)) c.getLong(iTaken) / 1000 else 0L
                out += MediaItem(
                    key = "$prefix:$id",
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(iName) ?: "file_$id",
                    size = c.getLong(iSize),
                    date = if (taken > 0) taken else c.getLong(iDate),
                    video = video,
                    bucket = (if (iBucket >= 0) c.getString(iBucket) else null) ?: "",
                    mime = (if (iMime >= 0) c.getString(iMime) else null) ?: (if (video) "video/mp4" else "image/jpeg"),
                    duration = if (iDur >= 0) c.getLong(iDur) else 0L
                )
            }
        }
    }
}
