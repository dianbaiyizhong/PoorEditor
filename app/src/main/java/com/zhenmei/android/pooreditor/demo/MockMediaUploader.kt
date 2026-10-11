package com.zhenmei.android.pooreditor.demo

import com.zhenmei.android.pooreditor.upload.MediaUploader
import com.zhenmei.android.pooreditor.upload.UploadRequest
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.random.Random

/** 演示用模拟上传器：真实接入时换成 OkHttpMediaUploader 或自己的实现 */
class MockMediaUploader : MediaUploader {

    override suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): String {
        var progress = 0
        while (progress < 100) {
            delay(Random.nextLong(60, 180))
            progress = (progress + Random.nextInt(8, 22)).coerceAtMost(100)
            onProgress(progress)
        }
        val ext = when (request.kind) {
            UploadRequest.Kind.IMAGE -> "jpg"
            UploadRequest.Kind.VIDEO -> "mp4"
        }
        return "https://cdn.example.com/uploads/${UUID.randomUUID()}.$ext"
    }
}
