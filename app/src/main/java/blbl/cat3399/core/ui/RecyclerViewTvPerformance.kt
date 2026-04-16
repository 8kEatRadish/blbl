package blbl.cat3399.core.ui

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal fun RecyclerView.applyTvPerformanceDefaults(
    itemViewCacheSize: Int = 14,
    initialPrefetchItemCount: Int = 12,
) {
    setHasFixedSize(true)
    setItemViewCacheSize(itemViewCacheSize.coerceAtLeast(2))
    // TV content lists prefer deterministic scroll/focus over item change animations.
    // Removing animator avoids jank during fast paging/append/update.
    itemAnimator = null
    recycledViewPool.setMaxRecycledViews(0, (itemViewCacheSize.coerceAtLeast(2) * 3).coerceAtLeast(12))

    when (val lm = layoutManager) {
        is GridLayoutManager -> {
            lm.initialPrefetchItemCount = initialPrefetchItemCount.coerceAtLeast(4)
            lm.isItemPrefetchEnabled = true
        }
        is LinearLayoutManager -> {
            lm.initialPrefetchItemCount = initialPrefetchItemCount.coerceAtLeast(4)
            lm.isItemPrefetchEnabled = true
        }
    }
}
