package com.zhenmei.android.pooreditor

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil3.ImageLoader
import coil3.video.VideoFrameDecoder
import com.zhenmei.android.pooreditor.databinding.ViewPoorEditorBinding
import com.zhenmei.android.pooreditor.internal.EditorAdapter
import com.zhenmei.android.pooreditor.internal.EmojiPanel
import com.zhenmei.android.pooreditor.internal.displayName
import com.zhenmei.android.pooreditor.internal.dp
import com.zhenmei.android.pooreditor.internal.imageSize
import com.zhenmei.android.pooreditor.internal.mimeTypeOf
import com.zhenmei.android.pooreditor.internal.newBlockId
import com.zhenmei.android.pooreditor.internal.videoMeta
import com.zhenmei.android.pooreditor.model.EditorBlock
import com.zhenmei.android.pooreditor.model.ImageBlock
import com.zhenmei.android.pooreditor.model.LocationBlock
import com.zhenmei.android.pooreditor.model.TextBlock
import com.zhenmei.android.pooreditor.model.VideoBlock
import com.zhenmei.android.pooreditor.upload.MediaKind
import com.zhenmei.android.pooreditor.upload.UploadInfo
import com.zhenmei.android.pooreditor.upload.UploadRequest
import com.zhenmei.android.pooreditor.upload.UploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 通用富文本编辑器组件（块状编辑）。
 *
 * 用法：
 * ```
 * editor.config = PoorEditorConfig(uploader = MyUploader())
 * editor.listener = object : OnEditorEventListener { ... }
 * editor.insertImage(uri, width, height) / insertVideo(...) / insertLocation(...)
 * val json = editor.getContentJson()   // 存草稿 / 提交
 * editor.setContent(json)              // 恢复草稿
 * ```
 * 需挂载在 Material 主题 + ComponentActivity 的页面中。
 */
class PoorEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var config: PoorEditorConfig = PoorEditorConfig()
        set(value) {
            field = value
            applyConfig()
        }

    var listener: OnEditorEventListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val binding = ViewPoorEditorBinding.inflate(LayoutInflater.from(context), this)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val imageLoader = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    private val adapter: EditorAdapter = EditorAdapter(context, imageLoader, object : EditorAdapter.Callbacks {
        override fun onBlockTapped(position: Int) {
            setSelection(position + 1)
        }

        override fun onDeleteClicked(position: Int) {
            deleteAt(position)
        }

        override fun onTextChanged(blockId: String, content: String) {
            updateTextBlock(blockId, content)
        }

        override fun onEditTextFocused(blockId: String, editText: EditText?) {
            activeEditText = editText
        }

        override fun onPlayVideo(block: VideoBlock, playerView: androidx.media3.ui.PlayerView) {
            adapter.playVideo(block, playerView)
        }

        override fun onRetryUpload(blockId: String) {
            startUploadFor(blockId)
        }

        override fun onStartDrag(holder: RecyclerView.ViewHolder) {
            dragHelper.startDrag(holder)
        }

        override fun onBlockMoved(from: Int, to: Int) {
            insertAt = to + 1
            updateIndicator()
            notifyContentChanged()
        }
    })

    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled(): Boolean = false
    })

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var imagePicker: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var videoPicker: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pickerRegistry: ActivityResultRegistry? = null
    private var activeEditText: EditText? = null

    /** 下一个内容的插入位置（0..blockCount），视觉上表现为"编辑指针" */
    private var insertAt = 0

    // ---- 插入光标条拖拽状态 ----
    private var dragging = false
    private var dragStartRawY = 0f
    private var dragStartTranslation = 0f
    private var dragInsertIndex = 0
    private var lastDragBoundaryY = 0f

    init {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        dragHelper.attachToRecyclerView(binding.recyclerView)
        binding.recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val pos = parent.getChildAdapterPosition(view)
                if (pos <= 0) return
                outRect.top = 12.dp(context)
            }
        })
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) updateIndicator()
            }
        })

        binding.btnAddText.setOnClickListener { insertBlock(TextBlock(newBlockId())) }
        binding.btnAddImage.setOnClickListener { requestPickImage() }
        binding.btnAddVideo.setOnClickListener { requestPickVideo() }
        binding.btnAddLocation.setOnClickListener { requestLocation() }
        binding.btnAddEmoji.setOnClickListener {
            EmojiPanel.show(context) { emoji -> appendToActiveText(emoji) }
        }

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.PoorEditorView)
            a.getString(R.styleable.PoorEditorView_editorHint)?.let { config.textHint = it }
            a.getString(R.styleable.PoorEditorView_editorEmptyTip)?.let { config.emptyTip = it }
            config.showToolbar = a.getBoolean(R.styleable.PoorEditorView_showToolbar, true)
            config.showEmoji = a.getBoolean(R.styleable.PoorEditorView_showEmoji, true)
            a.recycle()
        }

        applyConfig()
        setupInsertBarInteractions()
        applyImeInsets()
        binding.recyclerView.post { updateIndicator() }
    }

    /**
     * 键盘弹出时整体上推：监听 IME insets，把键盘高度转成编辑器底部 padding。
     * 宿主 adjustResize 生效时 IME inset 为 0（窗口已自动压缩），两种场景互不冲突；
     * 同时兼容 API 35+ 强制 edge-to-edge 的宿主。
     */
    private fun applyImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = ime)
            insets
        }
    }

    /** 插入光标条：拖拽手柄移动插入位置，"+" 展开快捷插入栏 */
    private fun setupInsertBarInteractions() {
        binding.dragHandle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartRawY = event.rawY
                    dragStartTranslation = binding.insertBar.translationY
                    binding.insertBar.alpha = 0.95f
                    binding.quickBar.visibility = View.GONE
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val rowH = binding.dragHandle.height.toFloat().coerceAtLeast(1f)
                    val maxY = (binding.recyclerView.height - rowH).coerceAtLeast(0f)
                    val ny = (dragStartTranslation + (event.rawY - dragStartRawY))
                        .coerceIn(0f, maxY)
                    binding.insertBar.translationY = ny
                    lastDragBoundaryY = ny + rowH / 2f
                    dragInsertIndex = computeIndexAt(lastDragBoundaryY)
                    updateDragEdgeScroll()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    binding.insertBar.alpha = 1f
                    stopDragEdgeScroll()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        insertAt = dragInsertIndex.coerceIn(0, adapter.itemCount)
                    }
                    updateIndicator()
                    v.performClick()
                    true
                }

                else -> false
            }
        }

        binding.btnQuickToggle.setOnClickListener {
            binding.quickBar.visibility =
                if (binding.quickBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnQuickText.setOnClickListener {
            binding.quickBar.visibility = View.GONE
            insertBlock(TextBlock(newBlockId()))
        }
        binding.btnQuickImage.setOnClickListener {
            binding.quickBar.visibility = View.GONE
            requestPickImage()
        }
        binding.btnQuickVideo.setOnClickListener {
            binding.quickBar.visibility = View.GONE
            requestPickVideo()
        }
        binding.btnQuickLocation.setOnClickListener {
            binding.quickBar.visibility = View.GONE
            requestLocation()
        }
        binding.btnQuickEmoji.setOnClickListener {
            binding.quickBar.visibility = View.GONE
            EmojiPanel.show(context) { emoji -> appendToActiveText(emoji) }
        }
    }

    /** 把光标条当前 Y（RecyclerView 坐标系）换算成插入下标 */
    private fun computeIndexAt(boundaryY: Float): Int {
        val rv = binding.recyclerView
        val lm = rv.layoutManager as? LinearLayoutManager ?: return insertAt
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return insertAt
        var crossed = 0
        for (i in 0 until rv.childCount) {
            if (rv.getChildAt(i).bottom < boundaryY) crossed++
        }
        return (first + crossed).coerceIn(0, adapter.itemCount)
    }

    private val dragScrollRunnable = object : Runnable {
        override fun run() {
            if (!dragging) return
            val rv = binding.recyclerView
            val zone = (rv.height / 6).coerceAtLeast(1)
            val speed = when {
                lastDragBoundaryY < zone -> -16
                lastDragBoundaryY > rv.height - zone -> 16
                else -> 0
            }
            if (speed != 0) {
                rv.scrollBy(0, speed)
                dragInsertIndex = computeIndexAt(lastDragBoundaryY)
                rv.postDelayed(this, 16)
            }
        }
    }

    private fun updateDragEdgeScroll() {
        val rv = binding.recyclerView
        val zone = (rv.height / 6).coerceAtLeast(1)
        val inEdge = lastDragBoundaryY < zone || lastDragBoundaryY > rv.height - zone
        if (inEdge) {
            rv.removeCallbacks(dragScrollRunnable)
            rv.postDelayed(dragScrollRunnable, 16)
        }
    }

    private fun stopDragEdgeScroll() {
        binding.recyclerView.removeCallbacks(dragScrollRunnable)
    }

    // ------------------------------------------------------------ 公开 API

    /** 用内容块列表填充编辑器（恢复草稿） */
    fun setContent(blocks: List<EditorBlock>) {
        adapter.replaceAll(blocks)
        insertAt = blocks.size
        notifyContentChanged()
        binding.recyclerView.scrollToPosition(0)
        binding.recyclerView.post { updateIndicator() }
    }

    /** 用 JSON 填充编辑器（恢复草稿） */
    fun setContent(jsonStr: String) {
        runCatching { json.decodeFromString<List<EditorBlock>>(jsonStr) }
            .onSuccess { setContent(it) }
            .onFailure { listener?.onEditorError("内容解析失败: ${it.message}") }
    }

    /** 导出内容块 */
    fun getBlocks(): List<EditorBlock> = adapter.blocks.toList()

    /** 导出 JSON（存草稿 / 提交发布） */
    fun getContentJson(): String = json.encodeToString<List<EditorBlock>>(adapter.blocks.toList())

    /** 插入文本块 */
    fun insertText(content: String = "") {
        insertBlock(TextBlock(newBlockId(), content))
    }

    /** 插入图片（宿主自定义选图后调用） */
    fun insertImage(localUri: Uri, width: Int = 0, height: Int = 0, shootTime: Long? = null) {
        insertBlock(ImageBlock(newBlockId(), localUri = localUri.toString(), width = width, height = height, shootTime = shootTime))
    }

    /** 插入视频（宿主自定义选视频后调用） */
    fun insertVideo(
        localUri: Uri,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0,
        posterUrl: String? = null,
        shootTime: Long? = null
    ) {
        insertBlock(
            VideoBlock(
                newBlockId(),
                localUri = localUri.toString(),
                width = width,
                height = height,
                durationMs = durationMs,
                posterUrl = posterUrl,
                shootTime = shootTime
            )
        )
    }

    /** 插入位置块 */
    fun insertLocation(name: String, address: String?, lat: Double, lng: Double) {
        insertBlock(LocationBlock(newBlockId(), name, address, lat, lng))
    }

    /**
     * 插入 @提及。渲染为彩色 "@名字"，序列化保留 @[name](id) 标记。
     * 注意：该块被重新编辑后，标记会退化为纯文本。
     */
    fun insertMention(userId: String, name: String): Boolean {
        return appendToActiveText("@[$name]($userId)")
    }

    /** 字数统计：文本字符数 + 每个媒体/位置块计 1 */
    fun charCount(): Int {
        var count = 0
        for (b in adapter.blocks) {
            count += when (b) {
                is TextBlock -> b.content.length
                else -> 1
            }
        }
        return count
    }

    /** LAZY 模式下，尚未上传的媒体块数量 */
    fun pendingUploadCount(): Int = adapter.blocks.count { isPendingBlock(it) }

    /** LAZY 模式下，把所有未上传的媒体块依次提交上传，返回触发上传的数量 */
    fun uploadAllPending(): Int {
        var n = 0
        for (b in adapter.blocks.toList()) {
            if (isPendingBlock(b)) {
                startUploadFor(b.id)
                n++
            }
        }
        return n
    }

    /** 上传成功后由宿主回填远程地址（LAZY 模式自行上传时使用） */
    fun attachRemoteUrl(blockId: String, remoteUrl: String) {
        val idx = adapter.indexOf(blockId)
        if (idx < 0) return
        val b = adapter.blocks[idx]
        adapter.blocks[idx] = when (b) {
            is ImageBlock -> b.copy(remoteUrl = remoteUrl)
            is VideoBlock -> b.copy(remoteUrl = remoteUrl)
            else -> return
        }
        adapter.setUploadState(blockId, UploadInfo(UploadState.DONE, 100))
    }

    fun clear() {
        adapter.replaceAll(emptyList())
        insertAt = 0
        notifyContentChanged()
        binding.recyclerView.post { updateIndicator() }
    }

    fun releasePlayers() {
        adapter.releasePlayer()
    }

    // ------------------------------------------------------------ 内部实现

    private fun isPendingBlock(b: EditorBlock): Boolean = when (b) {
        is ImageBlock -> b.remoteUrl == null && b.localUri != null
        is VideoBlock -> b.remoteUrl == null && b.localUri != null
        else -> false
    }

    private fun applyConfig() {
        binding.toolbar.visibility = if (config.showToolbar) View.VISIBLE else View.GONE
        binding.btnAddEmoji.visibility = if (config.showEmoji) View.VISIBLE else View.GONE
        binding.btnQuickEmoji.visibility = if (config.showEmoji) View.VISIBLE else View.GONE
        binding.tvEmptyTip.text = config.emptyTip
        adapter.hint = config.textHint
        adapter.notifyDataSetChanged()
        binding.recyclerView.setPadding(12.dp(context), 8.dp(context), 12.dp(context), 92.dp(context))
    }

    private fun insertBlock(block: EditorBlock) {
        val idx = insertAt.coerceIn(0, adapter.blocks.size)
        adapter.blocks.add(idx, block)
        adapter.notifyItemInserted(idx)
        insertAt = idx + 1
        binding.tvEmptyTip.visibility = View.GONE
        notifyContentChanged()
        scrollTo(idx)
        if (block is TextBlock) focusTextAt(idx)
        if (config.uploadMode == UploadMode.IMMEDIATE && isPendingBlock(block)) {
            startUploadFor(block.id)
        }
        updateIndicator()
    }

    private fun deleteAt(position: Int) {
        if (position !in adapter.blocks.indices) return
        val blockId = adapter.blocks[position].id
        if (adapter.uploadState(blockId)?.state == UploadState.UPLOADING) {
            // 正在上传的块也允许删除，上传结果回填时按 id 查找会自动落空
        }
        adapter.blocks.removeAt(position)
        adapter.releasePlayer()
        adapter.notifyItemRemoved(position)
        if (position < insertAt) insertAt--
        insertAt = insertAt.coerceIn(0, adapter.blocks.size)
        if (adapter.blocks.isEmpty()) {
            binding.tvEmptyTip.visibility = View.VISIBLE
            insertAt = 0
        }
        notifyContentChanged()
        binding.recyclerView.post { updateIndicator() }
    }

    private fun setSelection(index: Int) {
        insertAt = index.coerceIn(0, adapter.blocks.size)
        updateIndicator()
    }

    private fun updateTextBlock(blockId: String, content: String) {
        val idx = adapter.indexOf(blockId)
        if (idx >= 0) {
            val b = adapter.blocks[idx]
            if (b is TextBlock) adapter.blocks[idx] = b.copy(content = content)
        }
        notifyContentChanged()
    }

    private fun scrollTo(position: Int) {
        binding.recyclerView.post {
            (binding.recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 200)
            updateIndicator()
        }
    }

    private fun focusTextAt(position: Int) {
        binding.recyclerView.postDelayed({
            val holder = binding.recyclerView
                .findViewHolderForAdapterPosition(position) as? EditorAdapter.TextVH
                ?: return@postDelayed
            val et = holder.binding.etContent
            et.requestFocus()
            et.setSelection(et.text?.length ?: 0)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    @SuppressLint("CutPasteId")
    private fun updateIndicator() {
        if (dragging) return
        binding.recyclerView.post {
            val count = adapter.itemCount
            if (count == 0) {
                // 空内容时插入条停在顶部，保证首次插入也走"光标 + 快捷栏"交互
                binding.insertBar.visibility = View.VISIBLE
                binding.insertBar.translationY = 16.dp(context).toFloat()
                return@post
            }
            val anchor = insertAt.coerceIn(0, count) - 1
            val halfRow = (binding.dragHandle.height / 2f)
                .takeIf { it > 0f } ?: (16.dp(context)).toFloat()
            if (anchor >= 0) {
                // 常规情况：线的中心对齐"上一个块"的底边（即插入边界）
                val holder = binding.recyclerView.findViewHolderForAdapterPosition(anchor)
                if (holder != null) {
                    binding.insertBar.visibility = View.VISIBLE
                    binding.insertBar.translationY = holder.itemView.bottom - halfRow
                } else {
                    binding.insertBar.visibility = View.GONE
                }
            } else {
                // insertAt == 0（插到最前面）：对齐第一个可见块的顶部，避免光标条消失
                val first = binding.recyclerView.findViewHolderForAdapterPosition(0)
                if (first != null) {
                    binding.insertBar.visibility = View.VISIBLE
                    binding.insertBar.translationY =
                        (first.itemView.top - halfRow).coerceAtLeast(0f)
                } else {
                    binding.insertBar.visibility = View.GONE
                }
            }
        }
    }

    private fun notifyContentChanged() {
        listener?.onContentChanged(adapter.itemCount, charCount())
    }

    // ------------------------------------------------------------ 选媒体（Photo Picker）

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        registerPickers()
    }

    override fun onDetachedFromWindow() {
        adapter.releasePlayer()
        scope.cancel()
        unregisterPickers()
        super.onDetachedFromWindow()
    }

    /**
     * 注册 Photo Picker。
     *
     * 视图的 onAttachedToWindow 发生在宿主 Activity RESUMED 之后，
     * 而 ActivityResultRegistry 带 LifecycleOwner 的重载要求在 STARTED 之前注册，
     * 因此这里必须使用不带 LifecycleOwner 的重载，并在 detach 时手动 unregister。
     */
    private fun registerPickers() {
        val registry = findComponentActivity()?.activityResultRegistry ?: return
        if (pickerRegistry === registry && imagePicker != null && videoPicker != null) return
        unregisterPickers()
        imagePicker = registry.register(
            PICK_IMAGE_KEY, ActivityResultContracts.PickVisualMedia()
        ) { uri -> uri?.let { handlePickedMedia(it, isVideo = false) } }
        videoPicker = registry.register(
            PICK_VIDEO_KEY, ActivityResultContracts.PickVisualMedia()
        ) { uri -> uri?.let { handlePickedMedia(it, isVideo = true) } }
        pickerRegistry = registry
    }

    private fun unregisterPickers() {
        runCatching { imagePicker?.unregister() }
        runCatching { videoPicker?.unregister() }
        imagePicker = null
        videoPicker = null
        pickerRegistry = null
    }

    private fun findComponentActivity(): ComponentActivity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is ComponentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun requestPickImage() {
        if (listener?.onMediaRequested(MediaKind.IMAGE) == true) return
        val launcher = imagePicker
            ?: run {
                listener?.onEditorError("PoorEditorView 需挂载在 ComponentActivity 上才能使用内置选图，或实现 listener.onMediaRequested 自行处理")
                return
            }
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun requestPickVideo() {
        if (listener?.onMediaRequested(MediaKind.VIDEO) == true) return
        val launcher = videoPicker
            ?: run {
                listener?.onEditorError("PoorEditorView 需挂载在 ComponentActivity 上才能使用内置选视频，或实现 listener.onMediaRequested 自行处理")
                return
            }
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    /** 请求插入位置块：交给宿主实现，未实现时提示 */
    private fun requestLocation() {
        if (listener?.onLocationRequested() != true) {
            Toast.makeText(
                context,
                "请在 listener.onLocationRequested() 中实现位置选择",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handlePickedMedia(uri: Uri, isVideo: Boolean) {
        val mediaCount = adapter.blocks.count { it is ImageBlock || it is VideoBlock }
        if (mediaCount >= config.maxMediaCount) {
            listener?.onMaxMediaReached(config.maxMediaCount)
            return
        }
        if (isVideo) {
            val meta = context.videoMeta(uri)
            insertBlock(
                VideoBlock(
                    newBlockId(),
                    localUri = uri.toString(),
                    width = meta.width,
                    height = meta.height,
                    durationMs = meta.durationMs
                )
            )
        } else {
            val (w, h) = context.imageSize(uri)
            insertBlock(ImageBlock(newBlockId(), localUri = uri.toString(), width = w, height = h))
        }
    }

    // ------------------------------------------------------------ 上传

    private fun startUploadFor(blockId: String) {
        val uploader = config.uploader
        if (uploader == null) {
            adapter.setUploadState(blockId, UploadInfo(UploadState.FAILED, 0, "未配置 MediaUploader"))
            listener?.onUploadStateChanged(blockId, UploadState.FAILED, 0, "未配置 MediaUploader")
            return
        }
        val block = adapter.blocks.firstOrNull { it.id == blockId } ?: return
        val request = when (block) {
            is ImageBlock -> UploadRequest(
                blockId, UploadRequest.Kind.IMAGE,
                Uri.parse(block.localUri ?: return),
                context.displayName(Uri.parse(block.localUri)),
                context.mimeTypeOf(Uri.parse(block.localUri), false),
                context.contentResolver
            )
            is VideoBlock -> UploadRequest(
                blockId, UploadRequest.Kind.VIDEO,
                Uri.parse(block.localUri ?: return),
                context.displayName(Uri.parse(block.localUri)),
                context.mimeTypeOf(Uri.parse(block.localUri), true),
                context.contentResolver
            )
            else -> return
        }
        adapter.setUploadState(blockId, UploadInfo(UploadState.UPLOADING, 0))
        listener?.onUploadStateChanged(blockId, UploadState.UPLOADING, 0, null)
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    uploader.upload(request) { p ->
                        mainHandler.post {
                            adapter.setUploadState(blockId, UploadInfo(UploadState.UPLOADING, p))
                        }
                    }
                }
                val idx = adapter.indexOf(blockId)
                if (idx >= 0) {
                    val b = adapter.blocks[idx]
                    adapter.blocks[idx] = when (b) {
                        is ImageBlock -> b.copy(remoteUrl = url)
                        is VideoBlock -> b.copy(remoteUrl = url)
                        else -> b
                    }
                    adapter.notifyItemChanged(idx)
                }
                adapter.setUploadState(blockId, UploadInfo(UploadState.DONE, 100))
                listener?.onUploadStateChanged(blockId, UploadState.DONE, 100, null)
                notifyContentChanged()
            } catch (t: Throwable) {
                adapter.setUploadState(blockId, UploadInfo(UploadState.FAILED, 0, t.message))
                listener?.onUploadStateChanged(blockId, UploadState.FAILED, 0, t.message)
            }
        }
    }

    // ------------------------------------------------------------ 表情 / 提及的文本插入

    private fun appendToActiveText(token: String): Boolean {
        val et = activeEditText
        return if (et != null && et.isAttachedToWindow) {
            val start = et.selectionEnd.coerceAtLeast(0)
            et.text?.insert(start, token)
            true
        } else {
            val idx = adapter.blocks.indexOfLast { it is TextBlock }
            if (idx >= 0) {
                val b = adapter.blocks[idx] as TextBlock
                adapter.blocks[idx] = b.copy(content = b.content + token)
                adapter.notifyItemChanged(idx)
            } else {
                insertBlock(TextBlock(newBlockId(), content = token))
            }
            true
        }
    }

    companion object {
        private const val PICK_IMAGE_KEY = "pooreditor_pick_image"
        private const val PICK_VIDEO_KEY = "pooreditor_pick_video"
    }
}
