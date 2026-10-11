package com.zhenmei.android.pooreditor

import com.zhenmei.android.pooreditor.upload.MediaUploader

/** 上传时机 */
enum class UploadMode {
    /** 选中媒体后立即上传（组件内自动管理进度与重试） */
    IMMEDIATE,

    /** 懒上传：编辑期间仅保留本地 Uri，由宿主在发布时通过 uploadAllPending() 或自行上传 */
    LAZY
}

class PoorEditorConfig(
    /** 文本块 hint */
    var textHint: String = "开始输入正文…",

    /** 内容为空时的占位提示 */
    var emptyTip: String = "点击下方按钮开始创作",

    /** 最多可插入的媒体（图片+视频）数量 */
    var maxMediaCount: Int = 9,

    var uploadMode: UploadMode = UploadMode.IMMEDIATE,

    /** IMMEDIATE 模式必传 */
    var uploader: MediaUploader? = null,

    /** 是否显示底部插入工具栏 */
    var showToolbar: Boolean = true,

    /** 工具栏是否显示表情按钮 */
    var showEmoji: Boolean = true
)
