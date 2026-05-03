package com.example.mgrskor.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mgrskor.R
import com.example.mgrskor.data.SavedPoint
import com.example.mgrskor.databinding.ItemSavedPointBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedPointsAdapter(
    private val onDelete: (SavedPoint) -> Unit
) : ListAdapter<SavedPoint, SavedPointsAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSavedPointBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemSavedPointBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: SavedPoint) {
            b.tvName.text = p.name
            b.tvMgrs.text = p.mgrs
            b.tvMeta.text = if (p.satellitesUsed <= 0 || p.accuracyMeters.isNaN() || p.accuracyMeters <= 0f) {
                b.root.context.getString(
                    R.string.saved_meta_manual,
                    timeFmt.format(Date(p.timestampMs))
                )
            } else {
                b.root.context.getString(
                    R.string.saved_meta,
                    timeFmt.format(Date(p.timestampMs)),
                    p.accuracyMeters,
                    p.satellitesUsed
                )
            }
            b.btnDeleteItem.setOnClickListener { onDelete(p) }
            b.btnShareItem.setOnClickListener {
                val ctx = b.root.context
                val text = ctx.getString(
                    R.string.share_text,
                    p.mgrs,
                    String.format(Locale.US, "%.6f", p.latitude),
                    String.format(Locale.US, "%.6f", p.longitude)
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${p.name}\n$text")
                }
                ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_title)))
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SavedPoint>() {
            override fun areItemsTheSame(a: SavedPoint, b: SavedPoint) = a.id == b.id
            override fun areContentsTheSame(a: SavedPoint, b: SavedPoint) = a == b
        }
    }
}
