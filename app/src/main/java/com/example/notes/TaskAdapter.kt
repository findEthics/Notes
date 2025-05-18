package com.example.notes

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView

class TaskAdapter(
    private var tasks: MutableList<Task>,
    private val onTaskCheckedChanged: (Task, Boolean) -> Unit,
    private val onTaskTextChanged: (Task, String) -> Unit,
    private val onTaskDeleted: (Task) -> Unit
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbTask: CheckBox = itemView.findViewById(R.id.cbTask)
        val etTaskText: EditText = itemView.findViewById(R.id.etTaskText)
        val ibDeleteTask: ImageButton = itemView.findViewById(R.id.ibDeleteTask)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = tasks[position]

        // Set task text and checked state
        holder.etTaskText.setText(task.text)
        holder.cbTask.isChecked = task.isChecked

        // Apply strikethrough if task is checked
        updateTextAppearance(holder.etTaskText, task.isChecked)

        // Set listeners
        holder.cbTask.setOnCheckedChangeListener { _, isChecked ->
            onTaskCheckedChanged(task, isChecked)
            updateTextAppearance(holder.etTaskText, isChecked)
        }

        holder.etTaskText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val newText = s.toString()
                if (newText != task.text) {
                    onTaskTextChanged(task, newText)
                }
            }
        })

        holder.ibDeleteTask.setOnClickListener {
            onTaskDeleted(task)
            tasks.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, tasks.size)
        }
    }

    override fun getItemCount(): Int = tasks.size

    private fun updateTextAppearance(editText: EditText, isChecked: Boolean) {
        if (isChecked) {
            editText.apply {
                setTextColor(android.graphics.Color.GRAY)
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
        } else {
            editText.apply {
                setTextColor(android.graphics.Color.BLACK)
                paintFlags = paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }
    }

    // Update adapter data
    fun updateTasks(newTasks: List<Task>) {
        tasks.clear()
        tasks.addAll(newTasks)
        notifyDataSetChanged()
    }

    // Add a new task
    fun addTask(task: Task) {
        tasks.add(task)
        notifyItemInserted(tasks.size - 1)
    }
}
