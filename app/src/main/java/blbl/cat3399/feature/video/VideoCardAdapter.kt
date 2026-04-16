package blbl.cat3399.feature.video

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemVideoCardBinding
import java.util.concurrent.Executors

class VideoCardAdapter(
    private val onClick: (VideoCard, Int) -> Unit,
    private val onLongClick: ((VideoCard, Int) -> Boolean)? = null,
    private val fixedItemWidthDimenRes: Int? = null,
    private val fixedItemMarginDimenRes: Int? = null,
    private val stableIdKey: ((VideoCard) -> String)? = null,
    private val isSelected: ((VideoCard, Int) -> Boolean)? = null,
) : RecyclerView.Adapter<VideoCardAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var submitGeneration: Int = 0

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<VideoCard>) {
        val generation = ++submitGeneration

        if (items.isEmpty()) {
            if (list.isEmpty()) return
            items.clear()
            items.addAll(list)
            notifyItemRangeInserted(0, list.size)
            return
        }
        if (list.isEmpty()) {
            val oldSize = items.size
            if (oldSize <= 0) return
            items.clear()
            notifyItemRangeRemoved(0, oldSize)
            return
        }

        val oldItems = items.toList()
        val newItems = list.toList()
        diffExecutor.execute {
            val diff =
                DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize(): Int = oldItems.size

                        override fun getNewListSize(): Int = newItems.size

                        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                            val oldItem = oldItems[oldItemPosition]
                            val newItem = newItems[newItemPosition]
                            return oldItem.stableKey() == newItem.stableKey()
                        }

                        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                            return oldItems[oldItemPosition] == newItems[newItemPosition]
                        }
                    },
                    false,
                )
            mainHandler.post {
                if (generation != submitGeneration) return@post
                items.clear()
                items.addAll(newItems)
                diff.dispatchUpdatesTo(this@VideoCardAdapter)
            }
        }
    }

    fun append(list: List<VideoCard>) {
        if (list.isEmpty()) return
        // Cancel any in-flight submit diff result; append should always win in paging flows.
        submitGeneration++
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun snapshot(): List<VideoCard> = items.toList()

    override fun getItemId(position: Int): Long {
        val item = items[position]
        val key = stableIdKey?.invoke(item) ?: item.stableKey()
        return key.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemVideoCardBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding, fixedItemWidthDimenRes, fixedItemMarginDimenRes)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], position, onClick, onLongClick, isSelected)

    override fun getItemCount(): Int = items.size

    class Vh(
        private val binding: ItemVideoCardBinding,
        private val fixedItemWidthDimenRes: Int?,
        private val fixedItemMarginDimenRes: Int?,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val overlayShiftX: Float
        private val overlayShiftYBase: Float
        private var lastOverlayHeight: Int = -1

        init {
            val res = binding.root.resources
            val textMargin = res.getDimensionPixelSize(R.dimen.video_card_text_margin)
            val padH = res.getDimensionPixelSize(R.dimen.video_card_duration_padding_h)
            val padV = res.getDimensionPixelSize(R.dimen.video_card_duration_padding_v)

            val insetX = textMargin + padH
            overlayShiftX = -insetX * 0.5f
            overlayShiftYBase = (textMargin + padV) * 0.2f

            applyFixedSizing()
            applyOverlayTransformIfNeeded(force = true)
            binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyOverlayTransformIfNeeded(force = false)
            }
        }

        fun bind(
            item: VideoCard,
            position: Int,
            onClick: (VideoCard, Int) -> Unit,
            onLongClick: ((VideoCard, Int) -> Boolean)?,
            isSelected: ((VideoCard, Int) -> Boolean)?,
        ) {
            applyFixedSizing()

            binding.root.isSelected = isSelected?.invoke(item, position) == true

            val coverLeftBottomText = item.coverLeftBottomText?.trim()
            val isEpisodeStyleCard = item.coverLeftBottomText != null
            binding.tvCoverLeftBottom.isVisible = coverLeftBottomText?.isNotBlank() == true
            binding.tvCoverLeftBottom.text = coverLeftBottomText.orEmpty()

            binding.tvTitle.text = item.title
            val subtitleText =
                item.pubDateText
                    ?: if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            binding.tvSubtitle.text = subtitleText
            val pubDateText = item.pubDate?.let { Format.pubDateText(it) }.orEmpty()
            binding.tvPubdate.text = pubDateText
            val showSubtitleRow = !isEpisodeStyleCard && (subtitleText.isNotBlank() || pubDateText.isNotBlank())
            binding.llSubtitle.isVisible = showSubtitleRow
            binding.tvSubtitle.isVisible = showSubtitleRow && subtitleText.isNotBlank()
            binding.tvPubdate.isVisible = showSubtitleRow && pubDateText.isNotBlank()

            val showDuration = !isEpisodeStyleCard && item.durationSec > 0
            binding.tvDuration.isVisible = showDuration
            if (showDuration) {
                binding.tvDuration.text = Format.duration(item.durationSec)
            }

            val viewCount = item.view?.takeIf { it > 0 }
            val danmakuCount = item.danmaku?.takeIf { it > 0 }
            val showStats = !isEpisodeStyleCard && (viewCount != null || danmakuCount != null)
            binding.llStats.isVisible = showStats
            binding.ivStatPlay.isVisible = viewCount != null
            binding.tvView.isVisible = viewCount != null
            binding.ivStatDanmaku.isVisible = danmakuCount != null
            binding.tvDanmaku.isVisible = danmakuCount != null
            viewCount?.let { binding.tvView.text = Format.count(it) }
            danmakuCount?.let { binding.tvDanmaku.text = Format.count(it) }

            val accessBadgeText = item.accessBadgeText?.trim()?.takeIf { it.isNotBlank() }
            binding.tvChargeBadge.isVisible = accessBadgeText != null || item.isChargingArc
            binding.tvChargeBadge.text =
                accessBadgeText
                    ?: if (item.isChargingArc) {
                        binding.root.context.getString(R.string.badge_charging)
                    } else {
                        ""
                    }
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            applyOverlayTransformIfNeeded(force = false)

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(item, pos)
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnLongClickListener false
                onLongClick?.invoke(item, pos) ?: false
            }
        }

        private fun applyFixedSizing() {
            if (fixedItemWidthDimenRes != null) {
                val w =
                    binding.root.resources
                        .getDimensionPixelSize(fixedItemWidthDimenRes)
                        .coerceAtLeast(1)
                val lp = binding.root.layoutParams
                if (lp != null && lp.width != w) {
                    lp.width = w
                    binding.root.layoutParams = lp
                }
            }

            if (fixedItemMarginDimenRes != null) {
                val margin = binding.root.resources.getDimensionPixelSize(fixedItemMarginDimenRes).coerceAtLeast(0)
                (binding.root.layoutParams as? MarginLayoutParams)?.let { lp ->
                    if (lp.leftMargin != margin || lp.topMargin != margin || lp.rightMargin != margin || lp.bottomMargin != margin) {
                        lp.setMargins(margin, margin, margin, margin)
                        binding.root.layoutParams = lp
                    }
                }
            }
        }

        private fun applyOverlayTransformIfNeeded(force: Boolean) {
            binding.llStats.translationX = overlayShiftX
            binding.tvDuration.translationX = overlayShiftX

            val overlayHeight = maxOf(binding.llStats.height, binding.tvDuration.height)
            if (!force && overlayHeight == lastOverlayHeight) return

            val shiftY = overlayShiftYBase + overlayHeight * 0.2f
            binding.llStats.translationY = shiftY
            binding.tvDuration.translationY = shiftY
            lastOverlayHeight = overlayHeight
        }
    }

    companion object {
        private val diffExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VideoCardAdapterDiff").apply { isDaemon = true }
        }
    }
}
