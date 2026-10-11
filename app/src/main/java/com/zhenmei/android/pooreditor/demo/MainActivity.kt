package com.zhenmei.android.pooreditor.demo

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zhenmei.android.pooreditor.OnEditorEventListener
import com.zhenmei.android.pooreditor.PoorEditorConfig
import com.zhenmei.android.pooreditor.UploadMode
import com.zhenmei.android.pooreditor.demo.databinding.ActivityMainBinding
import com.zhenmei.android.pooreditor.upload.UploadState

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var draft: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editor.config = PoorEditorConfig(
            textHint = "分享你看到的路况…",
            uploadMode = UploadMode.IMMEDIATE,
            uploader = MockMediaUploader()
        )

        binding.editor.listener = object : OnEditorEventListener {
            override fun onContentChanged(blockCount: Int, charCount: Int) {
                binding.tvCount.text = "$blockCount 个内容块 · 约 $charCount 字"
            }

            override fun onLocationRequested(): Boolean {
                binding.editor.insertLocation(
                    "杭州东站",
                    "浙江省杭州市上城区全福桥路2号",
                    30.2911,
                    120.2133
                )
                return true
            }

            override fun onUploadStateChanged(
                blockId: String,
                state: UploadState,
                percent: Int,
                error: String?
            ) {
                if (state == UploadState.FAILED) {
                    Toast.makeText(this@MainActivity, "上传失败：$error", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMaxMediaReached(max: Int) {
                Toast.makeText(this@MainActivity, "最多插入 $max 个媒体", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPublish.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("编辑器内容 JSON")
                .setMessage(binding.editor.getContentJson())
                .setPositiveButton("关闭", null)
                .show()
        }

        binding.btnLocation.setOnClickListener {
            binding.editor.insertLocation(
                "西湖文化广场",
                "浙江省杭州市拱墅区中山北路581号",
                30.2741,
                120.1551
            )
        }

        binding.btnSaveDraft.setOnClickListener {
            draft = binding.editor.getContentJson()
            Toast.makeText(this, "草稿已保存（内存）", Toast.LENGTH_SHORT).show()
        }

        binding.btnRestoreDraft.setOnClickListener {
            val d = draft
            if (d == null) {
                Toast.makeText(this, "还没有草稿", Toast.LENGTH_SHORT).show()
            } else {
                binding.editor.setContent(d)
                Toast.makeText(this, "草稿已恢复", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        binding.editor.releasePlayers()
        super.onDestroy()
    }
}
