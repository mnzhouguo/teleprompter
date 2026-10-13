package com.example.teleprompter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.teleprompter.MobileSyncApiService.RemoteSpeech

/**
 * 远程口语稿列表：点击录制；左滑露出「编辑」。
 */
class RemoteSpeechAdapter(
    private val speeches: MutableList<RemoteSpeech>,
    private val onSpeechClick: (RemoteSpeech) -> Unit,
    private val onEditClick: (RemoteSpeech) -> Unit
) : RecyclerView.Adapter<RemoteSpeechAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val front: View = view.findViewById(R.id.speech_front)
        val actions: View = view.findViewById(R.id.speech_actions)
        val title: TextView = view.findViewById(R.id.speech_title)
        val preview: TextView = view.findViewById(R.id.speech_preview)
        val meta: TextView = view.findViewById(R.id.speech_meta)
        val edit: TextView = view.findViewById(R.id.speech_edit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_remote_speech, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val speech = speeches[position]
        holder.title.text = speech.title
        holder.preview.text = speech.preview.ifBlank { "（无预览）" }
        holder.meta.text = buildString {
            append("${speech.charCount} 字")
            if (speech.updatedAt.isNotBlank()) append(" · ${speech.updatedAt}")
        }

        holder.front.translationX = 0f
        val density = holder.itemView.resources.displayMetrics.density
        SwipeRevealHelper.bind(holder.front) {
            holder.actions.width.takeIf { it > 0 }
                ?: (88 * density).toInt()
        }

        holder.front.setOnClickListener {
            if (holder.front.translationX < -8f) {
                SwipeRevealHelper.closeIfOpen(holder.front)
            } else {
                SwipeRevealHelper.closeOpen()
                onSpeechClick(speech)
            }
        }
        holder.edit.setOnClickListener {
            SwipeRevealHelper.closeOpen()
            onEditClick(speech)
        }
    }

    override fun getItemCount(): Int = speeches.size

    fun submit(list: List<RemoteSpeech>) {
        SwipeRevealHelper.closeOpen(animate = false)
        speeches.clear()
        speeches.addAll(list)
        notifyDataSetChanged()
    }
}
