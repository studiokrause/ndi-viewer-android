package com.lekozaur.ndiviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SourceAdapter(private val onClick: (NdiSource) -> Unit) :
    RecyclerView.Adapter<SourceAdapter.VH>() {

    private val items = ArrayList<NdiSource>()
    private val statusMap = mutableMapOf<String, DecodeStatus>()

    fun submit(list: List<NdiSource>) {
        items.clear()
        items.addAll(list)
        // keep existing status for known URLs, reset unknown to gray
        // remove stale entries
        val keys = list.map { it.url }.toSet()
        statusMap.keys.retainAll(keys)
        notifyDataSetChanged()
    }

    fun updateStatus(url: String, status: DecodeStatus) {
        statusMap[url] = status
        val idx = items.indexOfFirst { it.url == url }
        if (idx >= 0) notifyItemChanged(idx)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.sourceName)
        val url: TextView = v.findViewById(R.id.sourceUrl)
        val dot: View = v.findViewById(R.id.decodeDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        holder.url.text = s.url
        val st = statusMap[s.url] ?: DecodeClassifier.fromNameHeuristic(s.name).let {
            if (it == DecodeStatus.UNKNOWN) DecodeStatus.UNKNOWN else it
        }
        val bg = when (st) {
            DecodeStatus.GREEN -> R.drawable.dot_green
            DecodeStatus.YELLOW -> R.drawable.dot_yellow
            DecodeStatus.RED -> R.drawable.dot_red
            DecodeStatus.UNKNOWN -> R.drawable.dot_gray
        }
        holder.dot.setBackgroundResource(bg)
        holder.itemView.setOnClickListener { onClick(s) }
    }
}
