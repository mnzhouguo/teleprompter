package com.example.teleprompter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 提词文稿列表适配器
 */
class ScriptListAdapter(
    private val scripts: MutableList<Script>,
    private val onEditClick: (Script) -> Unit,
    private val onDeleteClick: (Script) -> Unit,
    private val onRecordClick: (Script) -> Unit,
    private val onViewClick: (Script) -> Unit  // 双击查看
) : RecyclerView.Adapter<ScriptListAdapter.ViewHolder>() {

    private var selectedPosition: Int = -1
    private var lastClickTime: Long = 0
    private var lastClickPosition: Int = -1
    private val DOUBLE_CLICK_INTERVAL: Long = 300  // 双击间隔300ms

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardRoot: LinearLayout = view.findViewById(R.id.script_card)
        val cardContent: LinearLayout = view.findViewById(R.id.card_content)
        val cardActions: LinearLayout = view.findViewById(R.id.card_actions)
        val title: TextView = view.findViewById(R.id.card_title)
        val duration: TextView = view.findViewById(R.id.card_duration)
        val preview: TextView = view.findViewById(R.id.card_preview)
        val charCount: TextView = view.findViewById(R.id.card_char_count)
        val createdTime: TextView = view.findViewById(R.id.card_created_time)
        val actionEdit: LinearLayout = view.findViewById(R.id.action_edit)
        val actionDelete: LinearLayout = view.findViewById(R.id.action_delete)
        val actionRecord: LinearLayout = view.findViewById(R.id.action_record)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_script_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val script = scripts[position]

        // 设置内容
        holder.title.text = script.title
        holder.duration.text = script.durationText
        holder.preview.text = script.preview
        holder.charCount.text = "${script.charCount} 字"
        holder.createdTime.text = script.createdTimeText

        // 选中状态：显示/隐藏操作按钮
        val isSelected = position == selectedPosition
        holder.cardActions.visibility = if (isSelected) View.VISIBLE else View.GONE

        // 选中时改变卡片背景
        if (isSelected) {
            holder.cardRoot.setBackgroundResource(R.drawable.bg_script_card_selected)
        } else {
            holder.cardRoot.setBackgroundResource(R.drawable.bg_script_card)
        }

        // 点击卡片内容区域：支持单击选中、双击查看
        holder.cardContent.setOnClickListener {
            val currentTime = System.currentTimeMillis()

            // 判断是否双击
            if (currentTime - lastClickTime < DOUBLE_CLICK_INTERVAL && position == lastClickPosition) {
                // 双击：进入浏览页面
                onViewClick(script)
                lastClickTime = 0
                lastClickPosition = -1
            } else {
                // 单击：选中/取消选中
                if (selectedPosition == position) {
                    selectedPosition = -1
                } else {
                    selectedPosition = position
                }
                notifyDataSetChanged()
                lastClickTime = currentTime
                lastClickPosition = position
            }
        }

        // 操作按钮点击事件
        holder.actionEdit.setOnClickListener { onEditClick(script) }
        holder.actionDelete.setOnClickListener { onDeleteClick(script) }
        holder.actionRecord.setOnClickListener { onRecordClick(script) }
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
            if (selectedPosition == index) {
                selectedPosition = -1
            } else if (selectedPosition > index) {
                selectedPosition--
            }
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
        if (selectedPosition != -1) {
            selectedPosition = -1
            notifyDataSetChanged()
        }
    }
}