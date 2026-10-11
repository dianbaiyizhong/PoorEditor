package com.zhenmei.android.pooreditor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 编辑器内容块。多态序列化，JSON 形如：
 * [{"type":"text","id":"...","content":"..."}, {"type":"image",...}]
 */
@Serializable
sealed interface EditorBlock {
    val id: String
}

/** 文本块，支持 @[显示名](提及id) 标记 */
@Serializable
@SerialName("text")
data class TextBlock(
    override val id: String,
    val content: String = ""
) : EditorBlock

/** 图片块 */
@Serializable
@SerialName("image")
data class ImageBlock(
    override val id: String,
    val localUri: String? = null,
    val remoteUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val shootTime: Long? = null
) : EditorBlock

/** 视频块 */
@Serializable
@SerialName("video")
data class VideoBlock(
    override val id: String,
    val localUri: String? = null,
    val remoteUrl: String? = null,
    val posterUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val shootTime: Long? = null
) : EditorBlock

/** 位置块 */
@Serializable
@SerialName("location")
data class LocationBlock(
    override val id: String,
    val name: String,
    val address: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0
) : EditorBlock
