package blbl.cat3399.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.collection.LruCache
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

object ImageLoader {
    private const val TAG = "ImageLoader"
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val viewAttachedUrl = WeakHashMap<ImageView, String>()
    private val inFlightByUrl = HashMap<String, InFlightRequest>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private data class InFlightRequest(
        val targets: MutableSet<ImageView>,
        val job: Job,
    )

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)

        if (normalized == null) {
            view.setTag(R.id.tag_image_loader_url, null)
            detachView(view)
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            return
        }

        val lastUrl = view.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == normalized) {
            // If we already have a non-placeholder image for the same URL, keep it to prevent
            // flicker on rebind (e.g. switching tabs triggers notifyItemRangeChanged).
            val drawable = view.drawable
            if (drawable != null && drawable !== placeholder) {
                return
            }
        } else {
            view.setTag(R.id.tag_image_loader_url, normalized)
            detachView(view)
        }

        val cached = cache.get(normalized)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }

        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)

        inFlightByUrl[normalized]?.let { request ->
            request.targets.add(view)
            viewAttachedUrl[view] = normalized
            return
        }

        val targets = linkedSetOf(view)
        val job = scope.launch {
            val bitmap =
                runCatching {
                    val bytes = withContext(Dispatchers.IO) { BiliClient.getBytes(normalized) }
                    withContext(Dispatchers.Default) {
                        decodeBestEffort(
                            bytes = bytes,
                            reqWidth = view.width,
                            reqHeight = view.height,
                        )
                    }
                }.onFailure { t ->
                    AppLog.w(TAG, "load failed url=$normalized", t)
                }.getOrNull()

            val request = inFlightByUrl.remove(normalized)
            val boundTargets = request?.targets?.toList().orEmpty()
            if (bitmap == null) {
                boundTargets.forEach { target ->
                    if ((target.getTag(R.id.tag_image_loader_url) as? String) == normalized && target.drawable == null) {
                        target.setImageDrawable(placeholder)
                    }
                    if (viewAttachedUrl[target] == normalized) {
                        viewAttachedUrl.remove(target)
                    }
                }
                return@launch
            }

            cache.put(normalized, bitmap)
            boundTargets.forEach { target ->
                if ((target.getTag(R.id.tag_image_loader_url) as? String) == normalized) {
                    target.setImageBitmap(bitmap)
                }
                if (viewAttachedUrl[target] == normalized) {
                    viewAttachedUrl.remove(target)
                }
            }
        }
        inFlightByUrl[normalized] = InFlightRequest(targets = targets, job = job)
        viewAttachedUrl[view] = normalized
    }

    private fun detachView(view: ImageView) {
        val oldUrl = viewAttachedUrl.remove(view) ?: return
        val request = inFlightByUrl[oldUrl] ?: return
        request.targets.remove(view)
        if (request.targets.isEmpty()) {
            request.job.cancel()
            inFlightByUrl.remove(oldUrl)
        }
    }

    private fun decodeBestEffort(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap? {
        val safeReqWidth = reqWidth.coerceAtLeast(0)
        val safeReqHeight = reqHeight.coerceAtLeast(0)
        if (safeReqWidth <= 0 || safeReqHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = calculateInSampleSize(bounds, safeReqWidth, safeReqHeight)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(
        bounds: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val rawHeight = bounds.outHeight
        val rawWidth = bounds.outWidth
        if (rawHeight <= 0 || rawWidth <= 0) return 1
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return maxMemory / 16
    }
}
