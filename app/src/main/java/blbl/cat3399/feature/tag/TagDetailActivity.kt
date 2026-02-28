package blbl.cat3399.feature.tag

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
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
import blbl.cat3399.databinding.ActivityTagDetailBinding
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoDetailActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TagDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityTagDetailBinding

    private lateinit var adapter: VideoCardAdapter

    private val tagId: Long by lazy { intent.getLongExtra(EXTRA_TAG_ID, -1L).takeIf { it > 0L } ?: -1L }
    private val tagName: String by lazy { intent.getStringExtra(EXTRA_TAG_NAME).orEmpty().trim() }
    private val rid: Int by lazy { intent.getIntExtra(EXTRA_RID, -1).takeIf { it > 0 } ?: -1 }

    private enum class DataSource { DYNAMIC_TAG, SEARCH }

    private var dataSource: DataSource = DataSource.DYNAMIC_TAG

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
        binding = ActivityTagDetailBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        dataSource =
            when {
                tagId > 0L && rid > 0 -> DataSource.DYNAMIC_TAG
                tagName.isNotBlank() -> DataSource.SEARCH
                else -> {
                    AppToast.show(this, "缺少 tag_id/rid/tag_name")
                    finish()
                    return
                }
            }

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = tagName.ifBlank { "标签" }

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
                                source = "TagDetail:$rid/$tagId",
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
                        isEnabled = { this@TagDetailActivity.isFinishing.not() && this@TagDetailActivity.isDestroyed.not() },
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
        val requestPage = page
        val startAt = SystemClock.uptimeMillis()
        AppLog.d("TagDetail", "load start src=$dataSource rid=$rid tagId=$tagId page=$requestPage refresh=$isRefresh t=$startAt")

        lifecycleScope.launch {
            try {
                val res =
                    when (dataSource) {
                        DataSource.SEARCH -> fetchSearchPage(page = requestPage)
                        DataSource.DYNAMIC_TAG ->
                            try {
                                fetchDynamicTagPage(page = requestPage)
                            } catch (t: Throwable) {
                                if (requestPage == 1 && adapter.itemCount <= 0 && shouldFallbackToSearchOnDynamicError(t)) {
                                    if (fallbackToSearch(token = token, isRefresh = isRefresh, startAt = startAt, reason = "api_error")) return@launch
                                }
                                throw t
                            }
                    }
                if (token != requestToken) return@launch
                if (dataSource == DataSource.DYNAMIC_TAG && requestPage == 1 && res.items.isEmpty() && adapter.itemCount <= 0) {
                    if (fallbackToSearch(token = token, isRefresh = isRefresh, startAt = startAt, reason = "empty")) return@launch
                }

                val filtered = res.items.filter { loadedBvids.add(it.bvid) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)
                maybeFocusFirstItem()
                if (!res.hasMore || res.items.isEmpty()) endReached = true
                page++
                AppLog.i(
                    "TagDetail",
                    "load ok src=$dataSource rid=$rid tagId=$tagId add=${filtered.size} total=${adapter.itemCount} hasMore=${res.hasMore} cost=${SystemClock.uptimeMillis() - startAt}ms",
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("TagDetail", "load failed rid=$rid tagId=$tagId page=$page", t)
                AppToast.show(this@TagDetailActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (token == requestToken) binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun shouldFallbackToSearchOnDynamicError(t: Throwable): Boolean {
        val e = t as? BiliApiException ?: return false
        return e.apiCode == -404
    }

    private suspend fun fallbackToSearch(
        token: Int,
        isRefresh: Boolean,
        startAt: Long,
        reason: String,
    ): Boolean {
        val keyword = tagName.trim()
        if (keyword.isBlank()) return false

        dataSource = DataSource.SEARCH
        loadedBvids.clear()
        endReached = false
        page = 1
        val search = fetchSearchPage(page = 1)
        if (token != requestToken) return true
        val filtered = search.items.filter { loadedBvids.add(it.bvid) }
        if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)
        maybeFocusFirstItem()
        if (!search.hasMore || search.items.isEmpty()) endReached = true
        page = 2
        AppLog.i(
            "TagDetail",
            "load ok fallbackToSearch reason=$reason rid=$rid tagId=$tagId keyword=${keyword.take(20)} add=${filtered.size} total=${adapter.itemCount} hasMore=${search.hasMore} cost=${SystemClock.uptimeMillis() - startAt}ms",
        )
        return true
    }

    private suspend fun fetchDynamicTagPage(page: Int): BiliApi.HasMorePage<VideoCard> {
        val safeRid = rid.takeIf { it > 0 } ?: error("dynamic_tag_invalid_rid")
        val safeTagId = tagId.takeIf { it > 0L } ?: error("dynamic_tag_invalid_tag_id")
        return BiliApi.dynamicTag(rid = safeRid, tagId = safeTagId, pn = page, ps = 24)
    }

    private suspend fun fetchSearchPage(page: Int): BiliApi.HasMorePage<VideoCard> {
        val keyword = tagName.trim()
        if (keyword.isBlank()) {
            return BiliApi.HasMorePage(items = emptyList(), page = page.coerceAtLeast(1), hasMore = false, total = 0)
        }
        val res = BiliApi.searchVideo(keyword = keyword, page = page, order = "totalrank")
        val hasMore = res.items.isNotEmpty() && res.pages > 0 && res.page < res.pages
        return BiliApi.HasMorePage(items = res.items, page = res.page, hasMore = hasMore, total = res.total)
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
                        AppToast.show(this@TagDetailActivity, "未获取到 UP 主信息")
                        return@launch
                    }
                    startUpDetail(mid = viewMid, card = card)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                    AppToast.show(this@TagDetailActivity, "未获取到 UP 主信息")
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
        const val EXTRA_TAG_ID: String = "tag_id"
        const val EXTRA_TAG_NAME: String = "tag_name"
        const val EXTRA_RID: String = "rid"
    }
}
