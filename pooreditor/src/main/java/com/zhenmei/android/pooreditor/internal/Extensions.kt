package com.zhenmei.android.pooreditor.internal

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.util.UUID

internal fun newBlockId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

internal fun Int.dp(context: Context): Int {
    val v = this * context.resources.displayMetrics.density
    return if (v >= 0) (v + 0.5f).toInt() else (v - 0.5f).toInt()
}

internal data class VideoMeta(val width: Int, val height: Int, val durationMs: Long)

internal fun Context.imageSize(uri: Uri): Pair<Int, Int> {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        (opts.outWidth.takeIf { it > 0 } ?: 0) to (opts.outHeight.takeIf { it > 0 } ?: 0)
    } catch (e: Exception) {
        0 to 0
    }
}

internal fun Context.videoMeta(uri: Uri): VideoMeta {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(this, uri)
        VideoMeta(
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        )
    } catch (e: Exception) {
        VideoMeta(0, 0, 0)
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
        }
    }
}

internal fun Context.displayName(uri: Uri): String {
    return try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "media_${System.currentTimeMillis()}"
    } catch (e: Exception) {
        "media_${System.currentTimeMillis()}"
    }
}

internal fun Context.mimeTypeOf(uri: Uri, isVideo: Boolean): String {
    return contentResolver.getType(uri)
        ?: if (isVideo) "video/mp4" else "image/jpeg"
}
