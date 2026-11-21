package io.rustray.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ProfileAdapter(
    private val onSelect: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onShare: (Profile) -> Unit,
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
        private val selectedBar: View = view.findViewById(R.id.viewSelectedBar)
        private val name: TextView = view.findViewById(R.id.tvName)
        private val protocol: TextView = view.findViewById(R.id.tvProtocol)
        private val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        private val edit: ImageButton = view.findViewById(R.id.btnEdit)
        private val share: ImageButton = view.findViewById(R.id.btnShare)
        private val delete: ImageButton = view.findViewById(R.id.btnDelete)

        fun bind(profile: Profile) {
            val context = itemView.context
            val isSelected = profile.id == selectedId
            val expired = profile.isExpired()

            name.text = profile.name
            protocol.text = profile.protocol.label
            protocol.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (profile.protocol == Protocol.SSH) R.color.primary else R.color.accent,
                ),
            )

            subtitle.text = when {
                expired -> context.getString(R.string.badge_expired)
                profile.rrLockServer -> context.getString(R.string.badge_locked)
                else -> buildString {
                    append(profile.endpoint())
                    if (profile.rrLockPayload) append(" · ${context.getString(R.string.badge_locked)}")
                    if (profile.plaintext) append(" · ${context.getString(R.string.badge_no_tls)}")
                }
            }
            subtitle.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (expired) R.color.error else R.color.text_dim,
                ),
            )

            selectedBar.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            card.strokeColor = ContextCompat.getColor(
                context,
                if (isSelected) R.color.primary else R.color.outline,
            )
            card.setCardBackgroundColor(
                ContextCompat.getColor(context, if (isSelected) R.color.surface_2 else R.color.surface),
            )

            card.setOnClickListener { onSelect(profile) }
            edit.visibility = View.VISIBLE
            edit.setOnClickListener { onEdit(profile) }
            share.visibility = if (profile.isRrLocked) View.GONE else View.VISIBLE
            share.setOnClickListener { onShare(profile) }
            delete.setOnClickListener { onDelete(profile) }
        }
    }
}
