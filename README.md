# PoorEditor

通用 Android 富文本编辑器组件：块状编辑，支持文本 / 图片 / 视频 / 位置混排，内置媒体选取与上传进度管理，开箱即用。

技术栈：**Gradle 9.6 + AGP 9.4（内置 Kotlin）+ Kotlin 2.2 + ViewBinding + Coil 3 + Media3 + OkHttp 5 + kotlinx.serialization + 协程**，版本统一由 `gradle/libs.versions.toml` 管理。

## 工程结构

```
PoorEditor/
├── pooreditor/    # 组件库（可直接发布 AAR / Maven）
│   └── com.zhenmei.android.pooreditor.*
└── app/       # 演示 App（模拟上传 + 草稿往返）
```

## 集成

### 1. 依赖组件

方式一：源码集成 —— 把 `pooreditor` 模块拷入工程并在 `settings.gradle` include。

方式二：Maven —— `./gradlew :pooreditor:publishReleasePublicationToMavenLocal` 后依赖：

```kotlin
implementation("com.zhenmei:pooreditor:1.0.0")
```

要求：宿主页面为 **Material 主题**（如 `Theme.Material3.*`）+ **ComponentActivity**（AppCompatActivity 即可）。

### 2. 在布局中使用

```xml
<com.zhenmei.android.pooreditor.PoorEditorView
    android:id="@+id/editor"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:editorHint="开始输入正文…"
    app:showToolbar="true" />
```

### 3. 配置与监听

```kotlin
binding.editor.config = PoorEditorConfig(
    textHint = "分享你看到的路况…",
    maxMediaCount = 9,
    uploadMode = UploadMode.IMMEDIATE,          // 或 LAZY
    uploader = OkHttpMediaUploader(
        endpoint = "https://your.api/upload",
        fileField = "file",
        extraFormFields = mapOf("token" to "xxx"),
        responseParser = { body -> /* body -> url */ "" }
    )
)

binding.editor.listener = object : OnEditorEventListener {
    override fun onContentChanged(blockCount: Int, charCount: Int) { /* 更新字数 */ }
    override fun onLocationRequested(): Boolean {
        // 调起你的地图选点，选完调 editor.insertLocation(...)
        return true
    }
    // onMediaRequested: 自定义选图/选视频（默认用系统 Photo Picker，无需权限）
    // onUploadStateChanged: 上传进度 / 失败
}

// 退出页面前释放播放器
override fun onDestroy() {
    binding.editor.releasePlayers()
    super.onDestroy()
}
```

### 4. 取内容 / 恢复草稿

```kotlin
val json = editor.getContentJson()   // 存草稿 / 提交发布
editor.setContent(json)              // 恢复草稿
editor.getBlocks()                   // 强类型内容块列表
```

内容为多态 JSON（kotlinx.serialization）：

```json
[
  {"type":"text","id":"ab12","content":"正文 @[张三](123)"},
  {"type":"image","id":"cd34","localUri":"...","remoteUrl":"https://cdn/..jpg","width":1080,"height":810},
  {"type":"video","id":"ef56","remoteUrl":"https://cdn/..mp4","durationMs":15300},
  {"type":"location","id":"gh78","name":"杭州东站","lat":30.29,"lng":120.21}
]
```

## 两种上传模式

| 模式 | 行为 |
|---|---|
| `IMMEDIATE` | 选中媒体立即上传，块上实时显示进度，失败可点击重试；提交时内容已带 remoteUrl |
| `LAZY` | 编辑期间仅保留 localUri（同 module_publish 的"发布时上传"思路）；发布时调 `uploadAllPending()` 或自行上传后用 `attachRemoteUrl(blockId, url)` 回填 |

## 公开 API 一览

| API | 说明 |
|---|---|
| `config: PoorEditorConfig` | hint / 空态文案 / 媒体上限 / 上传模式 / 上传器 / 工具栏开关 |
| `listener: OnEditorEventListener` | 内容变化 / 媒体与位置请求 / 上传状态 / 错误 |
| `insertText()` / `insertImage()` / `insertVideo()` / `insertLocation()` | 编程插入内容块 |
| `insertMention(userId, name)` | 插入 @提及（彩色渲染，序列化保留标记；块被编辑后退化为纯文本） |
| `getContentJson()` / `setContent()` / `getBlocks()` | 内容导出与恢复 |
| `charCount()` / `pendingUploadCount()` / `uploadAllPending()` / `attachRemoteUrl()` | 字数与上传管理 |
| `clear()` / `releasePlayers()` | 清空 / 释放视频播放器 |

## 与旧 module_publish 的对应关系

| 旧实现 | 新实现 |
|---|---|
| EditorView + EditAdapter（Java） | PoorEditorView + EditorAdapter（Kotlin） |
| BaseQuickAdapter 多布局 | 原生 RecyclerView.Adapter + ViewBinding |
| JzvdStd 播放器 | Media3 ExoPlayer + PlayerView |
| Glide 图片加载 | Coil 3（含 coil-video 视频帧封面） |
| Gson/fastjson 序列化 | kotlinx.serialization 多态 sealed class |
| QMUI 弹出插入菜单 | 底部固定工具栏（可关）+ 插入线旁快捷插入栏 |
| 拖拽横线 + 点击吸附 | 编辑指针条：手柄可拖动（带边缘自动滚动），点击块自动定位；"+" 展开快捷插入栏；键盘弹出自动上推（IME insets 自适应，兼容 edge-to-edge） |
| 系统相册/权限 | Photo Picker（AndroidX ActivityResult，免权限） |
| 发布时 Retrofit multipart 上传 | 可插拔 MediaUploader（IMMEDIATE / LAZY 双模式，OkHttp 实现带进度回调） |
