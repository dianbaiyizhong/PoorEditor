package com.zhenmei.android.pooreditor.internal

import android.content.Context
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 内置轻量表情面板：Unicode Emoji，点击回传给编辑器 */
internal object EmojiPanel {

    private val EMOJIS = listOf(
        "😀", "😄", "😁", "😆", "😅", "🤣", "😊", "😍",
        "😘", "😜", "🤔", "😎", "😭", "😡", "🥳", "😴",
        "👍", "👎", "👏", "🙏", "💪", "🤝", "👌", "✌️",
        "🎉", "🔥", "❤️", "💔", "⭐", "🌈", "☀️", "🌙",
        "🚗", "✈️", "🚴", "🏔️", "🌊", "🍚", "🍜", "🍺",
        "📷", "🎬", "📍", "💡", "✅", "❗", "❓", "💬"
    )

    fun show(context: Context, onPick: (String) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val grid = GridLayout(context).apply {
            columnCount = 8
            val pad = 16.dp(context)
            setPadding(pad, pad, pad, pad)
        }
        EMOJIS.forEach { emoji ->
            val tv = TextView(context).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 44.dp(context)
                    height = 44.dp(context)
                }
                setOnClickListener {
                    onPick(emoji)
                    dialog.dismiss()
                }
            }
            grid.addView(tv)
        }
        val scroll = ScrollView(context).apply { addView(grid) }
        dialog.setContentView(scroll)
        dialog.show()
    }
}
