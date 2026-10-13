package com.example.teleprompter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 提词文稿列表：点击查看；左滑露出编辑 / 转写 / 录制 / 删除。
 */
class ScriptListAdapter(
    private val scripts: MutableList<Script>,
    private val onEditClick: (Script) -> Unit,
    private val onDeleteClick: (Script) -> Unit,
    private val onRecordClick: (Script) -> Unit,
    private val onViewClick: (Script) -> Unit,
    private val onPlaybackRefClick: (Script) -> Unit
) : RecyclerView.Adapter<ScriptListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardRoot: LinearLayout = view.findViewById(R.id.script_card)
        val cardContent: LinearLayout = view.findViewById(R.id.card_content)
        val cardActions: View = view.findViewById(R.id.card_actions)
        val title: TextView = view.findViewById(R.id.card_title)
        val duration: TextView = view.findViewById(R.id.card_duration)
        val preview: TextView = view.findViewById(R.id.card_preview)
        val charCount: TextView = view.findViewById(R.id.card_char_count)
        val createdTime: TextView = view.findViewById(R.id.card_created_time)
        val actionEdit: TextView = view.findViewById(R.id.action_edit)
        val actionDelete: TextView = view.findViewById(R.id.action_delete)
        val actionRecord: TextView = view.findViewById(R.id.action_record)
        val actionPlaybackRef: TextView = view.findViewById(R.id.action_playback_ref)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_script_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val script = scripts[position]
        val density = holder.itemView.resources.displayMetrics.density

        holder.title.text = script.title
        holder.duration.text = script.durationText
        holder.preview.text = script.preview
        holder.charCount.text = "${script.charCount} 字"
        holder.createdTime.text = script.createdTimeText
        holder.cardRoot.setBackgroundResource(R.drawable.bg_script_card)
        holder.cardRoot.translationX = 0f

        SwipeRevealHelper.bind(
            touchView = holder.cardContent,
            moveView = holder.cardRoot
        ) {
            holder.cardActions.width.takeIf { it > 0 }
                ?: (72 * 4 * density).toInt()
        }

        holder.cardContent.setOnClickListener {
            if (holder.cardRoot.translationX < -8f) {
                SwipeRevealHelper.closeIfOpen(holder.cardRoot)
            } else {
                SwipeRevealHelper.closeOpen()
                onViewClick(script)
            }
        }

        holder.actionEdit.setOnClickListener {
            SwipeRevealHelper.closeOpen()
            onEditClick(script)
        }
        holder.actionDelete.setOnClickListener {
            SwipeRevealHelper.closeOpen()
            onDeleteClick(script)
        }
        holder.actionRecord.setOnClickListener {
            SwipeRevealHelper.closeOpen()
            onRecordClick(script)
        }
        holder.actionPlaybackRef.setOnClickListener {
            SwipeRevealHelper.closeOpen()
            onPlaybackRefClick(script)
        }
    }

    override fun getItemCount(): Int = scripts.size

    fun addScript(script: Script) {
        scripts.add(0, script)
        notifyItemInserted(0)
    }

    fun removeScript(script: Script) {
        val index = scripts.indexOf(script)
        if (index >= 0) {
            scripts.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun updateScript(script: Script) {
        val index = scripts.indexOfFirst { it.id == script.id }
        if (index >= 0) {
            scripts[index] = script
            notifyItemChanged(index)
        }
    }

    fun clearSelection() {
        SwipeRevealHelper.closeOpen()
    }
}
