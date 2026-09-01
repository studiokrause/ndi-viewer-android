package com.lekozaur.ndiviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SourceAdapter(private val onClick: (NdiSource) -> Unit) :
    RecyclerView.Adapter<SourceAdapter.VH>() {

    private val items = ArrayList<NdiSource>()

    fun submit(list: List<NdiSource>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.sourceName)
        val url: TextView = v.findViewById(R.id.sourceUrl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        holder.url.text = s.url
        holder.itemView.setOnClickListener { onClick(s) }
    }
}
