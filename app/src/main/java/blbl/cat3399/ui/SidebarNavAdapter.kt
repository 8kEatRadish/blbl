package blbl.cat3399.ui

import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.databinding.ItemSidebarNavBinding

class SidebarNavAdapter(
    private val onClick: (NavItem) -> Boolean,
) : RecyclerView.Adapter<SidebarNavAdapter.Vh>() {
    data class NavItem(
        val id: Int,
        val title: String,
        val iconRes: Int,
    )

    private val items = ArrayList<NavItem>()
    private var selectedId: Int = ID_HOME
    private var showLabelsAlways: Boolean = true

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<NavItem>, selectedId: Int) {
        items.clear()
        items.addAll(list)
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    fun setShowLabelsAlways(enabled: Boolean) {
        if (showLabelsAlways == enabled) return
        showLabelsAlways = enabled
        notifyDataSetChanged()
    }

    fun select(id: Int, trigger: Boolean) {
        if (selectedId == id) {
            if (trigger) items.firstOrNull { it.id == id }?.let { onClick(it) }
            return
        }
        val prevId = selectedId
        selectedId = id
        AppLog.d(
            "Nav",
            "select prev=$prevId new=$id trigger=$trigger t=${SystemClock.uptimeMillis()}",
        )

        val prevPos = items.indexOfFirst { it.id == prevId }
        val newPos = items.indexOfFirst { it.id == id }
        if (prevPos >= 0) notifyItemChanged(prevPos, PAYLOAD_SELECTION)
        if (newPos >= 0) notifyItemChanged(newPos, PAYLOAD_SELECTION)

        if (trigger) items.firstOrNull { it.id == id }?.let { onClick(it) }
    }

    fun selectedAdapterPosition(): Int = items.indexOfFirst { it.id == selectedId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemSidebarNavBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = items[position]
        val selected = item.id == selectedId
        AppLog.d(
            "Nav",
            "bind pos=$position id=${item.id} selected=$selected labels=$showLabelsAlways t=${SystemClock.uptimeMillis()}",
        )
        holder.bind(item, selected, showLabelsAlways) {
            val handled = onClick(item)
            if (handled) select(item.id, trigger = false)
        }
    }

    override fun onBindViewHolder(holder: Vh, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val item = items[position]
        val selected = item.id == selectedId
        holder.bindSelectionState(selected, showLabelsAlways)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemSidebarNavBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: NavItem,
            selected: Boolean,
            showLabelsAlways: Boolean,
            onClick: () -> Unit,
        ) {
            binding.ivIcon.setImageResource(item.iconRes)
            binding.tvLabel.text = item.title
            bindSelectionState(selected, showLabelsAlways)
            binding.root.setOnClickListener { onClick() }
        }

        fun bindSelectionState(
            selected: Boolean,
            showLabelsAlways: Boolean,
        ) {
            binding.tvLabel.isVisible = showLabelsAlways || selected
            val ctx = binding.root.context
            binding.card.setCardBackgroundColor(
                if (selected) ThemeColor.resolve(ctx, R.attr.blblAccent, R.color.blbl_accent_yt_red) else 0x00000000,
            )
            binding.card.isSelected = selected
            binding.card.isActivated = selected
            val iconTint =
                if (selected) {
                    ThemeColor.resolve(ctx, com.google.android.material.R.attr.colorOnSecondary, android.R.color.white)
                } else {
                    ThemeColor.resolve(ctx, android.R.attr.textColorSecondary, R.color.blbl_text_secondary)
                }
            binding.ivIcon.imageTintList = ColorStateList.valueOf(iconTint)
            val labelColor =
                if (selected) {
                    ThemeColor.resolve(ctx, com.google.android.material.R.attr.colorOnSecondary, android.R.color.white)
                } else {
                    ThemeColor.resolve(ctx, com.google.android.material.R.attr.colorOnSurface, R.color.blbl_text)
                }
            binding.tvLabel.setTextColor(labelColor)

            val heightRes =
                when {
                    showLabelsAlways -> R.dimen.sidebar_nav_item_height_labeled
                    selected -> R.dimen.sidebar_nav_item_height_selected
                    else -> R.dimen.sidebar_nav_item_height_default
                }
            val heightPx = binding.root.resources.getDimensionPixelSize(heightRes).coerceAtLeast(1)
            val lp = binding.card.layoutParams
            if (lp.height != heightPx) {
                lp.height = heightPx
                binding.card.layoutParams = lp
            }
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"

        const val ID_SEARCH = 0
        const val ID_HOME = 1
        const val ID_CATEGORY = 2
        const val ID_DYNAMIC = 3
        const val ID_LIVE = 4
        const val ID_MY = 5
    }
}
