// NoteAdapter.kt
package com.example.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NoteAdapter(
    private var notes: List<Note>,
    private val onNoteClick: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.NoteViewHolder>() {

    private var selectionMode = false
    private val selectedNotes = mutableSetOf<Long>()

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNoteTitle: TextView = itemView.findViewById(R.id.tvNoteTitle)
        val cbSelectNote: CheckBox = itemView.findViewById(R.id.cbSelectNote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = notes[position]

        // Set note title
        holder.tvNoteTitle.text = note.title.ifEmpty { "Untitled Note" }

        // Handle selection mode
        if (selectionMode) {
            holder.cbSelectNote.visibility = View.VISIBLE
            holder.cbSelectNote.isChecked = selectedNotes.contains(note.id)

            holder.cbSelectNote.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedNotes.add(note.id)
                } else {
                    selectedNotes.remove(note.id)
                }
            }

            holder.itemView.setOnClickListener {
                holder.cbSelectNote.isChecked = !holder.cbSelectNote.isChecked
            }
        } else {
            holder.cbSelectNote.visibility = View.GONE
            holder.cbSelectNote.setOnCheckedChangeListener(null)

            // Set click listener for normal mode
            holder.itemView.setOnClickListener {
                onNoteClick(note)
            }
        }
    }

    override fun getItemCount(): Int = notes.size

    fun updateNotes(newNotes: List<Note>) {
        notes = newNotes
        notifyDataSetChanged()
    }

    fun toggleSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (!selectionMode) {
            selectedNotes.clear()
        }
        notifyDataSetChanged()
    }

    fun getSelectedNoteIds(): List<Long> {
        return selectedNotes.toList()
    }

    fun isAnyNoteSelected(): Boolean {
        return selectedNotes.isNotEmpty()
    }
}
