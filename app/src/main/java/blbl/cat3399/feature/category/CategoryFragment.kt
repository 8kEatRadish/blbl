package blbl.cat3399.feature.category

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.ui.RefreshKeyHandler
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Zone
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.applyTvPerformanceDefaults
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.postDelayedIfAlive
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentCategoryBinding
import blbl.cat3399.feature.video.VideoGridFragment
import blbl.cat3399.feature.video.VideoGridTabSwitchFocusHost
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.SidebarFocusHost

class CategoryFragment : Fragment(), VideoGridTabSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentCategoryBinding? = null
    private val binding get() = _binding!!
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var pendingBackToTab0RequestToken: Int = 0
    private var pendingBackToTab0AttemptsLeft: Int = 0

    private val zones: List<Zone> = listOf(
        Zone("全站", null),
        Zone("动画", 1),
        Zone("音乐", 3),
        Zone("舞蹈", 129),
        Zone("游戏", 4),
        Zone("知识", 36),
        Zone("科技", 188),
        Zone("运动", 234),
        Zone("汽车", 223),
        Zone("生活", 160),
        Zone("美食", 211),
        Zone("动物圈", 217),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.viewPager.adapter = CategoryPagerAdapter(this, zones)
        binding.viewPager.applyTvPerformanceDefaults(pageCacheLimit = 1)
        AppLog.d(
            "Category",
            "pager init count=${zones.size} offscreen=${binding.viewPager.offscreenPageLimit} t=${SystemClock.uptimeMillis()}",
        )
        TabLayoutMediator(binding.tabLayout, binding.viewPager, true, false) { tab, position ->
            tab.text = zones[position].title
        }.attach()
        binding.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = Unit

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) {
                    refreshCurrentPageFromTabReselect()
                }
            },
        )
        val b = binding
        val tabLayout = b.tabLayout
        tabLayout.postIfAlive(isAlive = { _binding === b }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus }) { position ->
                val zone = zones.getOrNull(position)
                AppLog.d(
                    "Category",
                    "tab focus pos=$position title=${zone?.title} tid=${zone?.tid} t=${SystemClock.uptimeMillis()}",
                )
            }
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusCurrentPageFirstCardFromTab()
                        return@setOnKeyListener true
                    }
                    false
                }
            }
        }
        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val zone = zones.getOrNull(position)
                    AppLog.d(
                        "Category",
                        "page selected pos=$position title=${zone?.title} tid=${zone?.tid} t=${SystemClock.uptimeMillis()}",
                    )
                    if (pendingFocusFirstCardFromBackToTab0) {
                        maybeRequestTab0FocusFromBackToTab0()
                    } else if (pendingFocusFirstCardFromContentSwitch) {
                        if (focusCurrentPageFirstCardFromContentSwitch()) {
                            pendingFocusFirstCardFromContentSwitch = false
                        }
                    }
                }
            }
        binding.viewPager.registerOnPageChangeCallback(pageCallback!!)
    }

    override fun onResume() {
        super.onResume()
    }

    private fun refreshCurrentPageFromTabReselect(): Boolean {
        val pagerAdapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = pagerAdapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is RefreshKeyHandler -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is RefreshKeyHandler } as? RefreshKeyHandler
            } ?: return false
        return pageFragment.handleRefreshKey()
    }

    private fun focusCurrentPageFirstCardFromTab(): Boolean {
        val pagerAdapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = pagerAdapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is VideoGridFragment -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is VideoGridFragment } as? VideoGridFragment
            } ?: return false
        return pageFragment.requestFocusFirstCardFromTab()
    }

    private fun focusCurrentPageFirstCardFromContentSwitch(): Boolean {
        val pagerAdapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = pagerAdapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is VideoGridFragment -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is VideoGridFragment } as? VideoGridFragment
            } ?: return false
        return pageFragment.requestFocusFirstCardFromContentSwitch()
    }

    private fun maybeRequestTab0FocusFromBackToTab0(): Boolean {
        val b = _binding ?: return false
        if (!pendingFocusFirstCardFromBackToTab0) return false
        // "Back -> tab0 content" is only meaningful when tab0 is selected.
        if (b.viewPager.currentItem != 0) return false

        val pagerAdapter = b.viewPager.adapter as? FragmentStateAdapter ?: return false
        val itemId = pagerAdapter.getItemId(0)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val tab0 = byTag as? VideoGridFragment
        if (tab0 != null) {
            tab0.requestFocusFirstCardFromBackToTab0()
            pendingFocusFirstCardFromBackToTab0 = false
            pendingBackToTab0AttemptsLeft = 0
            return true
        }

        // Page fragment not ready yet: retry for a few frames.
        if (pendingBackToTab0AttemptsLeft <= 0) return false
        val token = pendingBackToTab0RequestToken
        pendingBackToTab0AttemptsLeft--
        b.viewPager.postDelayedIfAlive(
            delayMillis = 16L,
            isAlive = { _binding === b && pendingFocusFirstCardFromBackToTab0 && pendingBackToTab0RequestToken == token },
        ) {
            maybeRequestTab0FocusFromBackToTab0()
        }
        return true
    }

    private fun focusSelectedTab(): Boolean {
        val b = _binding ?: return false
        val tabStrip = b.tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = b.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: b.viewPager.currentItem
        if (pos < 0 || pos >= tabStrip.childCount) return false
        b.tabLayout.postIfAlive(isAlive = { _binding != null }) {
            tabStrip.getChildAt(pos)?.requestFocus()
        }
        return true
    }

    override fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (focusCurrentPageFirstCardFromContentSwitch()) {
            pendingFocusFirstCardFromContentSwitch = false
        }
        return true
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        val scheme = BiliClient.prefs.mainBackFocusScheme

        // Tab strip is a navigation layer: Back should always return to the left sidebar.
        if (b.tabLayout.hasFocus()) {
            return (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
        }

        // Only handle the Back key when focus is inside the page content area.
        val inContent = b.viewPager.hasFocus() && !b.tabLayout.hasFocus()
        if (!inContent) return false

        return when (scheme) {
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_A -> focusSelectedTab()
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_B -> {
                if (b.viewPager.currentItem != 0) {
                    pendingFocusFirstCardFromBackToTab0 = true
                    pendingFocusFirstCardFromContentSwitch = false
                    pendingBackToTab0RequestToken++
                    pendingBackToTab0AttemptsLeft = 30
                    // Use non-smooth switch: smooth scrolling may trigger intermediate onPageSelected callbacks
                    // and consume the pending focus restore on the wrong page.
                    b.viewPager.setCurrentItem(0, false)
                    true
                } else {
                    (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
                }
            }
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_C -> {
                (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
            }
            else -> focusSelectedTab()
        }
    }

    override fun onDestroyView() {
        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = CategoryFragment()
    }
}
