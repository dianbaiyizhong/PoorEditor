package com.zhenmei.android.pooreditor

import com.zhenmei.android.pooreditor.upload.MediaKind
import com.zhenmei.android.pooreditor.upload.UploadState

/** 编辑器事件，全部为可选实现 */
interface OnEditorEventListener {
    /** 内容（块数/字数）变化 */
    fun onContentChanged(blockCount: Int, charCount: Int) {}

    /**
     * 组件准备插入图片/视频。默认行为：挂载在 ComponentActivity 上时用系统 Photo Picker 选媒体。
     * 宿主返回 true 表示自己处理（如调起自定义选择器），处理后调用 insertImage/insertVideo 即可。
     */
    fun onMediaRequested(kind: MediaKind): Boolean = false

    /**
     * 用户点击"位置"按钮。宿主返回 true 表示已处理（通常在回调里调 insertLocation）。
     */
    fun onLocationRequested(): Boolean = false

    /** 上传状态变化 */
    fun onUploadStateChanged(blockId: String, state: UploadState, percent: Int, error: String?) {}

    /** 超出最大媒体数 */
    fun onMaxMediaReached(max: Int) {}

    /** 组件级错误 */
    fun onEditorError(message: String) {}
}
