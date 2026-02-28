package blbl.cat3399.feature.video

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityRegionDetailBinding
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RegionDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityRegionDetailBinding

    private lateinit var adapter: VideoCardAdapter

    private val rid: Int by lazy { intent.getIntExtra(EXTRA_RID, -1).takeIf { it > 0 } ?: -1 }
    private val title: String by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty().trim() }

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var pendingFocusFirstItem: Boolean = false

    private var dpadGridController: DpadGridController? = null
    private var upFetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegionDetailBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        if (rid <= 0) {
            AppToast.show(this, "缺少 rid")
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = title.ifBlank { "分区" }

        if (!this::adapter.isInitialized) {
            adapter =
                VideoCardAdapter(
                    onClick = { card, pos ->
                        val cards = adapter.snapshot()
                        val playlistItems =
                            cards.map {
                                PlayerPlaylistItem(
                                    bvid = it.bvid,
                                    cid = it.cid,
                                    title = it.title,
                                )
                            }
                        val token =
                            PlayerPlaylistStore.put(
                                items = playlistItems,
                                index = pos,
                                source = "RegionDetail:$rid",
                                uiCards = cards,
                            )
                        if (BiliClient.prefs.playerOpenDetailBeforePlay) {
                            startActivity(
                                Intent(this, VideoDetailActivity::class.java)
                                    .putExtra(VideoDetailActivity.EXTRA_BVID, card.bvid)
                                    .putExtra(VideoDetailActivity.EXTRA_CID, card.cid ?: -1L)
                                    .apply { card.aid?.let { putExtra(VideoDetailActivity.EXTRA_AID, it) } }
                                    .putExtra(VideoDetailActivity.EXTRA_TITLE, card.title)
                                    .putExtra(VideoDetailActivity.EXTRA_COVER_URL, card.coverUrl)
                                    .apply {
                                        card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_NAME, it) }
                                        card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_AVATAR, it) }
                                        card.ownerMid?.takeIf { it > 0L }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_MID, it) }
                                    }
                                    .putExtra(VideoDetailActivity.EXTRA_PLAYLIST_TOKEN, token)
                                    .putExtra(VideoDetailActivity.EXTRA_PLAYLIST_INDEX, pos),
                            )
                        } else {
                            startActivity(
                                Intent(this, PlayerActivity::class.java)
                                    .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                                    .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                            )
                        }
                    },
                    onLongClick = { card, _ ->
                        openUpDetailFromVideoCard(card)
                        true
                    },
                )
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(this, spanCountForWidth())
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { !isFinishing && !isDestroyed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            binding.recycler.requestFocus()
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
    }

    override fun onDestroy() {
        dpadGridController?.release()
        dpadGridController = null
        super.onDestroy()
    }

    private fun resetAndLoad() {
        loadedBvids.clear()
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        AppLog.d("RegionDetail", "load start rid=$rid page=$page refresh=$isRefresh t=$startAt")

        lifecycleScope.launch {
            try {
                val items = BiliApi.regionLatest(rid = rid, pn = page, ps = 24)
                if (token != requestToken) return@launch
                val filtered = items.filter { loadedBvids.add(it.bvid) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)
                maybeFocusFirstItem()
                if (filtered.isEmpty()) endReached = true
                page++
                AppLog.i(
                    "RegionDetail",
                    "load ok rid=$rid add=${filtered.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms",
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("RegionDetail", "load failed rid=$rid page=$page", t)
                AppToast.show(this@RegionDetailActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (token == requestToken) binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun maybeFocusFirstItem() {
        if (!pendingFocusFirstItem) return
        if (adapter.itemCount <= 0) return

        val focused = currentFocus
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItem = false
            return
        }

        val recycler = binding.recycler
        recycler.post {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItem = false
                return@post
            }
            recycler.scrollToPosition(0)
            recycler.post {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                pendingFocusFirstItem = false
            }
        }
    }

    private fun openUpDetailFromVideoCard(card: VideoCard) {
        val mid = card.ownerMid?.takeIf { it > 0L }
        if (mid != null) {
            startUpDetail(mid = mid, card = card)
            return
        }

        val safeAid = card.aid?.takeIf { it > 0L }
        if (card.bvid.isBlank() && safeAid == null) {
            AppToast.show(this, "未获取到 UP 主信息")
            return
        }
        if (upFetchJob?.isActive == true) return
        val requestBvid = card.bvid

        upFetchJob =
            lifecycleScope.launch {
                try {
                    val json = if (requestBvid.isNotBlank()) BiliApi.view(requestBvid) else BiliApi.view(safeAid ?: 0L)
                    val code = json.optInt("code", 0)
                    if (code != 0) {
                        val msg = json.optString("message", json.optString("msg", ""))
                        throw BiliApiException(apiCode = code, apiMessage = msg)
                    }
                    val owner = json.optJSONObject("data")?.optJSONObject("owner")
                    val viewMid = owner?.optLong("mid") ?: 0L
                    if (viewMid <= 0L) {
                        AppToast.show(this@RegionDetailActivity, "未获取到 UP 主信息")
                        return@launch
                    }
                    startUpDetail(mid = viewMid, card = card)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                    AppToast.show(this@RegionDetailActivity, "未获取到 UP 主信息")
                } finally {
                    upFetchJob = null
                }
            }
    }

    private fun startUpDetail(mid: Long, card: VideoCard) {
        startActivity(
            Intent(this, UpDetailActivity::class.java)
                .putExtra(UpDetailActivity.EXTRA_MID, mid)
                .apply {
                    card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                    card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
                },
        )
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    companion object {
        const val EXTRA_RID: String = "rid"
        const val EXTRA_TITLE: String = "title"
    }
}

