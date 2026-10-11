package com.zhenmei.android.pooreditor.internal

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.video.videoFrameMillis
import com.zhenmei.android.pooreditor.R
import com.zhenmei.android.pooreditor.databinding.ItemBlockImageBinding
import com.zhenmei.android.pooreditor.databinding.ItemBlockLocationBinding
import com.zhenmei.android.pooreditor.databinding.ItemBlockTextBinding
import com.zhenmei.android.pooreditor.databinding.ItemBlockVideoBinding
import com.zhenmei.android.pooreditor.model.EditorBlock
import com.zhenmei.android.pooreditor.model.ImageBlock
import com.zhenmei.android.pooreditor.model.LocationBlock
import com.zhenmei.android.pooreditor.model.TextBlock
import com.zhenmei.android.pooreditor.model.VideoBlock
import com.zhenmei.android.pooreditor.upload.UploadInfo
import com.zhenmei.android.pooreditor.upload.UploadState

/**
 * 内容块适配器：文本 / 图片 / 视频 / 位置 四种 ViewType。
 * 内部直接持有可变列表（编辑器需要频繁局部更新，不适用 DiffUtil）。
 */
class EditorAdapter(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onBlockTapped(position: Int)
        fun onDeleteClicked(position: Int)
        fun onTextChanged(blockId: String, content: String)
        fun onEditTextFocused(blockId: String, editText: EditText?)
        fun onPlayVideo(block: VideoBlock, playerView: PlayerView)
        fun onRetryUpload(blockId: String)
        fun onStartDrag(holder: RecyclerView.ViewHolder)
        fun onBlockMoved(from: Int, to: Int)
    }

    val blocks = mutableListOf<EditorBlock>()

    var hint: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    @ColorInt
    var accentColor: Int = 0

    private val uploadStates = mutableMapOf<String, UploadInfo>()
    private var currentPlayer: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null

    private val mentionRegex = Regex("@\\[([^\\]]+)\\]\\(([^)]*)\\)")

    // ------------------------------------------------------------ 数据操作

    fun indexOf(blockId: String): Int = blocks.indexOfFirst { it.id == blockId }

    fun replaceAll(newBlocks: List<EditorBlock>) {
        blocks.clear()
        blocks.addAll(newBlocks)
        releasePlayer()
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in blocks.indices || to !in blocks.indices || from == to) return
        val block = blocks.removeAt(from)
        blocks.add(to, block)
        releasePlayer()
        notifyDataSetChanged()
        callbacks.onBlockMoved(from, to)
    }

    fun setUploadState(blockId: String, info: UploadInfo) {
        uploadStates[blockId] = info
        val pos = indexOf(blockId)
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun uploadState(blockId: String): UploadInfo? = uploadStates[blockId]

    // ------------------------------------------------------------ 视频播放

    fun playVideo(block: VideoBlock, playerView: PlayerView) {
        releasePlayer()
        val url = block.localUri ?: block.remoteUrl ?: return
        val container = playerView.parent as? ViewGroup
        container?.findViewById<ImageView>(R.id.ivPoster)?.visibility = View.GONE
        container?.findViewById<ImageView>(R.id.btnPlay)?.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val player = ExoPlayer.Builder(context).build()
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        currentPlayer = player
        currentPlayerView = playerView
    }

    fun releasePlayer() {
        currentPlayer?.release()
        currentPlayer = null
        currentPlayerView?.let { pv ->
            pv.player = null
            pv.visibility = View.GONE
            val container = pv.parent as? ViewGroup
            container?.findViewById<ImageView>(R.id.ivPoster)?.visibility = View.VISIBLE
            container?.findViewById<ImageView>(R.id.btnPlay)?.visibility = View.VISIBLE
        }
        currentPlayerView = null
    }

    // ------------------------------------------------------------ RecyclerView

    override fun getItemCount(): Int = blocks.size

    override fun getItemViewType(position: Int): Int = when (blocks[position]) {
        is TextBlock -> TYPE_TEXT
        is ImageBlock -> TYPE_IMAGE
        is VideoBlock -> TYPE_VIDEO
        is LocationBlock -> TYPE_LOCATION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TEXT -> TextVH(ItemBlockTextBinding.inflate(inflater, parent, false))
            TYPE_IMAGE -> ImageVH(ItemBlockImageBinding.inflate(inflater, parent, false))
            TYPE_VIDEO -> VideoVH(ItemBlockVideoBinding.inflate(inflater, parent, false))
            else -> LocationVH(ItemBlockLocationBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = blocks[position]) {
            is TextBlock -> bindText(holder as TextVH, item)
            is ImageBlock -> bindImage(holder as ImageVH, item)
            is VideoBlock -> bindVideo(holder as VideoVH, item)
            is LocationBlock -> bindLocation(holder as LocationVH, item)
        }
    }

    // ------------------------------------------------------------ 绑定

    private fun bindText(holder: TextVH, item: TextBlock) {
        val b = holder.binding
        b.tvSortNum.text = (holder.absoluteAdapterPosition + 1).toString()

        (b.etContent.tag as? TextWatcher)?.let { b.etContent.removeTextChangedListener(it) }

        val rendered = renderRichText(item.content)
        val current = b.etContent.text?.toString().orEmpty()
        if (current != item.content && current != rendered.toString()) {
            b.etContent.setText(rendered)
            b.etContent.setSelection(rendered.length)
        }
        b.etContent.hint = hint

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                // 渲染 @提及 span 导致的文本差异不回写模型，保留原始标记
                if (s.toString() == rendered.toString()) return
                callbacks.onTextChanged(item.id, s.toString())
            }
        }
        b.etContent.addTextChangedListener(watcher)
        b.etContent.tag = watcher

        b.etContent.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                callbacks.onEditTextFocused(item.id, b.etContent)
                val pos = holder.absoluteAdapterPosition
                if (pos != RecyclerView.NO_POSITION) callbacks.onBlockTapped(pos)
            }
        }
        b.btnDelete.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onDeleteClicked(pos)
        }
        b.btnDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) callbacks.onStartDrag(holder)
            false
        }
    }

    private fun bindImage(holder: ImageVH, item: ImageBlock) {
        val b = holder.binding
        b.tvSortNum.text = (holder.absoluteAdapterPosition + 1).toString()

        val model: Any? = item.remoteUrl ?: item.localUri?.let { Uri.parse(it) }
        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(model)
                .crossfade(true)
                .target(b.ivImage)
                .build()
        )

        bindUploadUi(b.tvUploadStatus, b.progress, uploadStates[item.id])
        b.tvUploadStatus.setOnClickListener {
            if (uploadStates[item.id]?.state == UploadState.FAILED) callbacks.onRetryUpload(item.id)
        }
        b.btnDelete.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onDeleteClicked(pos)
        }
        b.btnDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) callbacks.onStartDrag(holder)
            false
        }
        b.root.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onBlockTapped(pos)
        }
    }

    private fun bindVideo(holder: VideoVH, item: VideoBlock) {
        val b = holder.binding
        b.tvSortNum.text = (holder.absoluteAdapterPosition + 1).toString()

        if (currentPlayerView !== b.playerView) {
            b.playerView.visibility = View.GONE
            b.playerView.player = null
            b.ivPoster.visibility = View.VISIBLE
            b.btnPlay.visibility = View.VISIBLE
            val model: Any? = item.posterUrl
                ?: item.localUri?.let { Uri.parse(it) }
                ?: item.remoteUrl
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(model)
                    .videoFrameMillis(1000)
                    .crossfade(true)
                    .target(b.ivPoster)
                    .build()
            )
        }

        bindUploadUi(b.tvUploadStatus, b.progress, uploadStates[item.id])
        b.tvUploadStatus.setOnClickListener {
            if (uploadStates[item.id]?.state == UploadState.FAILED) callbacks.onRetryUpload(item.id)
        }
        b.btnPlay.setOnClickListener { callbacks.onPlayVideo(item, b.playerView) }
        b.btnDelete.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onDeleteClicked(pos)
        }
        b.btnDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) callbacks.onStartDrag(holder)
            false
        }
        b.root.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onBlockTapped(pos)
        }
    }

    private fun bindLocation(holder: LocationVH, item: LocationBlock) {
        val b = holder.binding
        b.tvSortNum.text = (holder.absoluteAdapterPosition + 1).toString()
        b.tvName.text = item.name
        b.tvCoords.text = item.address ?: "%.4f, %.4f".format(item.lat, item.lng)
        b.btnDelete.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onDeleteClicked(pos)
        }
        b.btnDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) callbacks.onStartDrag(holder)
            false
        }
        b.root.setOnClickListener {
            val pos = holder.absoluteAdapterPosition
            if (pos != RecyclerView.NO_POSITION) callbacks.onBlockTapped(pos)
        }
    }

    private fun bindUploadUi(tv: TextView, progress: View, info: UploadInfo?) {
        when (info?.state) {
            null, UploadState.DONE -> {
                tv.visibility = View.GONE
                progress.visibility = View.GONE
            }
            UploadState.PENDING -> {
                tv.visibility = View.VISIBLE
                tv.text = "待上传"
                progress.visibility = View.GONE
            }
            UploadState.UPLOADING -> {
                tv.visibility = View.VISIBLE
                tv.text = "上传中 ${info.percent}%"
                progress.visibility = View.VISIBLE
            }
            UploadState.FAILED -> {
                tv.visibility = View.VISIBLE
                tv.text = "上传失败，点击重试"
                progress.visibility = View.GONE
            }
        }
    }

    /** 把 @[name](id) 标记渲染成彩色提及文本 */
    private fun renderRichText(raw: String): CharSequence {
        if (!raw.contains("@[")) return raw
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in mentionRegex.findAll(raw)) {
            sb.append(raw, last, m.range.first)
            val start = sb.length
            sb.append("@").append(m.groupValues[1]).append(" ")
            val color = if (accentColor != 0) accentColor
            else ContextCompat.getColor(context, R.color.re_accent)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        sb.append(raw, last, raw.length)
        return sb
    }

    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_IMAGE = 2
        const val TYPE_VIDEO = 3
        const val TYPE_LOCATION = 4
    }

    class TextVH(val binding: ItemBlockTextBinding) : RecyclerView.ViewHolder(binding.root)
    class ImageVH(val binding: ItemBlockImageBinding) : RecyclerView.ViewHolder(binding.root)
    class VideoVH(val binding: ItemBlockVideoBinding) : RecyclerView.ViewHolder(binding.root)
    class LocationVH(val binding: ItemBlockLocationBinding) : RecyclerView.ViewHolder(binding.root)
}
