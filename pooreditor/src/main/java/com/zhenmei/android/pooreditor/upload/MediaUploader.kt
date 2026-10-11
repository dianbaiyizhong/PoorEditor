package com.zhenmei.android.pooreditor.upload

import android.content.ContentResolver
import android.net.Uri

/** 媒体上传状态（运行时状态，不参与序列化） */
enum class UploadState { PENDING, UPLOADING, DONE, FAILED }

data class UploadInfo(
    val state: UploadState,
    val percent: Int,
    val error: String? = null
)

/** 媒体类型，用于宿主自定义选媒体回调 */
enum class MediaKind { IMAGE, VIDEO }

data class UploadRequest(
    val blockId: String,
    val kind: Kind,
    val localUri: Uri,
    val fileName: String,
    val mimeType: String,
    val contentResolver: ContentResolver
) {
    enum class Kind { IMAGE, VIDEO }
}

/**
 * 上传器接口：宿主实现并注入 PoorEditorConfig 即可接入自己的服务端。
 * 返回值应为可直接访问的远程 URL。
 */
interface MediaUploader {
    suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): String
}
