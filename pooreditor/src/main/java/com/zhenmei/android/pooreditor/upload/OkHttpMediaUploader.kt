package com.zhenmei.android.pooreditor.upload

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException

/**
 * 基于 OkHttp multipart 的默认上传器实现。
 *
 * @param endpoint        上传接口地址
 * @param fileField       文件字段名，默认 "file"
 * @param extraFormFields 附加表单字段（如 token、bizType 等）
 * @param client          自定义 OkHttpClient（可挂拦截器）
 * @param responseParser  响应体 -> 远程 URL 的解析器；不传则尝试从 JSON 的 url / data.url 字段取
 */
class OkHttpMediaUploader(
    private val endpoint: String,
    private val fileField: String = "file",
    private val extraFormFields: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = OkHttpClient(),
    private val responseParser: ((body: String) -> String)? = null
) : MediaUploader {

    override suspend fun upload(request: UploadRequest, onProgress: (Int) -> Unit): String {
        return withContext(Dispatchers.IO) {
            val mime = request.mimeType.ifBlank { "application/octet-stream" }
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    fileField,
                    request.fileName,
                    CountingBody(request.contentResolver, request.localUri, mime, onProgress)
                )
            extraFormFields.forEach { (k, v) -> multipart.addFormDataPart(k, v) }

            val call = client.newCall(
                Request.Builder().url(endpoint).post(multipart.build()).build()
            )
            call.execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("上传失败 HTTP ${resp.code}: $bodyStr")
                }
                responseParser?.invoke(bodyStr) ?: parseUrlFromBody(bodyStr)
            }
        }
    }

    private fun parseUrlFromBody(body: String): String {
        return try {
            val root = JSONObject(body)
            root.optString("url").takeIf { it.isNotBlank() }
                ?: root.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }
                ?: body.takeIf { it.startsWith("http") }
                ?: throw IOException("无法从响应解析 URL: $body")
        } catch (e: Exception) {
            throw IOException("无法从响应解析 URL: $body", e)
        }
    }
}

/** 带上传进度的 RequestBody（边读边写边回调） */
private class CountingBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val mime: String,
    private val onProgress: (Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = mime.toMediaTypeOrNull()

    override fun contentLength(): Long = try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    override fun writeTo(sink: BufferedSink) {
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            val total = contentLength()
            var written = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                written += read
                if (total > 0) {
                    onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        } ?: throw IOException("无法读取文件: $uri")
    }
}
