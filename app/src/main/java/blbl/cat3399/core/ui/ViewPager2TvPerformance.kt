package blbl.cat3399.core.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

internal fun ViewPager2.applyTvPerformanceDefaults(
    pageCacheLimit: Int = 1,
) {
    offscreenPageLimit = pageCacheLimit.coerceIn(1, 3)

    val recycler = getChildAt(0) as? RecyclerView ?: return
    recycler.setHasFixedSize(true)
    recycler.itemAnimator = null
    recycler.overScrollMode = View.OVER_SCROLL_NEVER
}

