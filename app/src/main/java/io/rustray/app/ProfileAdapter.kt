package io.rustray.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ProfileAdapter(
    private val onSelect: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

    private var items: List<Profile> = emptyList()
    private var selectedId: String? = null

    fun submit(profiles: List<Profile>, selected: String?) {
        items = profiles
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.cardProfile)
        private val selectedIcon: ImageView = view.findViewById(R.id.ivSelected)
        private val name: TextView = view.findViewById(R.id.tvName)
        private val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        private val edit: ImageButton = view.findViewById(R.id.btnEdit)

        fun bind(profile: Profile) {
            val context = itemView.context
            val isSelected = profile.id == selectedId
            name.text = profile.name
            val suffix = buildString {
                if (profile.isImported) append(" · imported")
                if (profile.plaintext) append(" · no TLS")
            }
            subtitle.text = "${profile.protocol.label} · ${profile.endpoint()}$suffix"
            selectedIcon.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.strokeColor = ContextCompat.getColor(
                context,
                if (isSelected) R.color.primary else R.color.outline,
            )
            card.setCardBackgroundColor(
                ContextCompat.getColor(context, if (isSelected) R.color.surface_2 else R.color.surface),
            )

            card.setOnClickListener { onSelect(profile) }
            card.setOnLongClickListener {
                onDelete(profile)
                true
            }
            edit.visibility = if (profile.isImported) View.GONE else View.VISIBLE
            edit.setOnClickListener { onEdit(profile) }
        }
    }
}
