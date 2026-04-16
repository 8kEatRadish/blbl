package blbl.cat3399.feature.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import blbl.cat3399.BlblApp
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.feature.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONObject

internal object DlnaCastIntentRouter {
    private const val TAG = "DlnaCastIntentRouter"
    private const val DUPLICATE_WINDOW_MS = 1500L
    private const val MAX_CID_CANDIDATES = 3
    private const val MAX_SEARCH_ITEMS_TO_VERIFY = 8
    private val BVID_REGEX = Regex("(?i)\\bBV[0-9A-Za-z]{10}\\b")
    private val AID_PATH_REGEX = Regex("(?i)(?:/|\\b)av(\\d{1,20})(?:\\b|/)")
    private val AID_QUERY_REGEX = Regex("(?i)(?:\\?|&)(?:aid|avid)=(\\d{1,20})(?:&|$)")
    private val CID_QUERY_REGEX = Regex("(?i)(?:\\?|&)cid=(\\d{1,20})(?:&|$)")
    private val EP_PATH_REGEX = Regex("(?i)/ep(\\d{1,20})(?:\\b|/)")
    private val EP_QUERY_REGEX = Regex("(?i)(?:\\?|&)(?:ep_id|epid)=(\\d{1,20})(?:&|$)")
    private val SEASON_PATH_REGEX = Regex("(?i)/ss(\\d{1,20})(?:\\b|/)")
    private val SEASON_QUERY_REGEX = Regex("(?i)(?:\\?|&)season_id=(\\d{1,20})(?:&|$)")

    private data class ParsedCastLink(
        val bvid: String? = null,
        val aid: Long? = null,
        val cid: Long? = null,
        val epId: Long? = null,
        val seasonId: Long? = null,
        val directUrl: String? = null,
    )

    private data class ResolvedCastTarget(
        val bvid: String? = null,
        val aid: Long? = null,
        val cid: Long? = null,
        val epId: Long? = null,
        val seasonId: Long? = null,
        val needsBangumiDetail: Boolean = false,
        val directUrl: String? = null,
    )

    @Volatile
    private var lastRawUri: String = ""

    @Volatile
    private var lastRawUriAtMs: Long = 0L

    fun handleIncomingUri(context: Context, rawUri: String) {
        val appContext = context.applicationContext
        val safeRaw = rawUri.trim()
        if (safeRaw.isBlank()) {
            AppLog.w(TAG, "ignore empty uri")
            return
        }
        val now = System.currentTimeMillis()
        val duplicated = lastRawUri == safeRaw && now - lastRawUriAtMs in 0..DUPLICATE_WINDOW_MS
        if (duplicated) {
            AppLog.i(TAG, "drop duplicate cast uri within ${DUPLICATE_WINDOW_MS}ms")
            return
        }
        lastRawUri = safeRaw
        lastRawUriAtMs = now
        AppLog.i(TAG, "incoming cast uri len=${safeRaw.length}")

        BlblApp.launchIo {
            val target = resolveTarget(safeRaw)
            withContext(Dispatchers.Main) {
                if (target == null) {
                    AppLog.w(TAG, "unsupported cast uri=$safeRaw")
                    AppToast.show(appContext, "投屏链接解析失败")
                    return@withContext
                }
                launchTarget(appContext, target)
            }
        }
    }

    private suspend fun resolveTarget(rawUri: String): ResolvedCastTarget? {
        // Parse as many URL variants as possible first; only call network APIs when needed (ep/ss only).
        val parsed =
            parse(rawUri)
                ?: parseDirectMediaUrl(rawUri)?.let { direct ->
                    val resolved = tryResolveStationTargetFromDirectUrl(direct)
                    return resolved ?: ResolvedCastTarget(directUrl = direct)
                }
                ?: return null
        AppLog.d(
            TAG,
            "parsed target bvid=${parsed.bvid.orEmpty()} aid=${parsed.aid ?: -1L} cid=${parsed.cid ?: -1L} ep=${parsed.epId ?: -1L} ss=${parsed.seasonId ?: -1L} direct=${parsed.directUrl.orEmpty()}",
        )

        if (!parsed.directUrl.isNullOrBlank()) {
            val direct = parsed.directUrl
            val resolved = tryResolveStationTargetFromDirectUrl(direct)
            if (resolved != null) return resolved
            return ResolvedCastTarget(
                directUrl = direct,
            )
        }

        if (!parsed.bvid.isNullOrBlank() || (parsed.aid ?: 0L) > 0L) {
            return ResolvedCastTarget(
                bvid = parsed.bvid,
                aid = parsed.aid,
                cid = parsed.cid,
                epId = parsed.epId,
                seasonId = parsed.seasonId,
                needsBangumiDetail = false,
            )
        }

        val epId = parsed.epId?.takeIf { it > 0L }
        if (epId != null) {
            val detail =
                runCatching { BiliApi.bangumiSeasonDetailByEpId(epId) }
                    .onFailure { AppLog.w(TAG, "resolve epId failed ep=$epId", it) }
                    .getOrNull()
            val picked =
                detail?.episodes?.firstOrNull { it.epId == epId }
                    ?: detail?.episodes?.firstOrNull()
            val bvid = picked?.bvid?.trim().takeIf { !it.isNullOrBlank() }
            val aid = picked?.aid?.takeIf { it > 0L }
            val cid = picked?.cid?.takeIf { it > 0L } ?: parsed.cid
            if (!bvid.isNullOrBlank() || aid != null) {
                return ResolvedCastTarget(
                    bvid = bvid,
                    aid = aid,
                    cid = cid,
                    epId = epId,
                    seasonId = detail?.seasonId?.takeIf { it > 0L } ?: parsed.seasonId,
                    needsBangumiDetail = false,
                )
            }
            return ResolvedCastTarget(
                epId = epId,
                seasonId = detail?.seasonId?.takeIf { it > 0L } ?: parsed.seasonId,
                needsBangumiDetail = true,
            )
        }

        val seasonId = parsed.seasonId?.takeIf { it > 0L } ?: return null
        val detail =
            runCatching { BiliApi.bangumiSeasonDetail(seasonId) }
                .onFailure { AppLog.w(TAG, "resolve seasonId failed ss=$seasonId", it) }
                .getOrNull()
        val picked = detail?.episodes?.firstOrNull()
        val bvid = picked?.bvid?.trim().takeIf { !it.isNullOrBlank() }
        val aid = picked?.aid?.takeIf { it > 0L }
        val cid = picked?.cid?.takeIf { it > 0L } ?: parsed.cid
        if (!bvid.isNullOrBlank() || aid != null) {
            return ResolvedCastTarget(
                bvid = bvid,
                aid = aid,
                cid = cid,
                epId = picked?.epId?.takeIf { it > 0L },
                seasonId = detail?.seasonId?.takeIf { it > 0L } ?: seasonId,
                needsBangumiDetail = false,
            )
        }
        return ResolvedCastTarget(
            seasonId = seasonId,
            needsBangumiDetail = true,
        )
    }

    private fun launchTarget(context: Context, target: ResolvedCastTarget) {
        val direct = target.directUrl?.trim().orEmpty()
        if (direct.isNotBlank()) {
            val intent =
                Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(PlayerActivity.EXTRA_DIRECT_URL, direct)
            AppLog.i(TAG, "launch direct url len=${direct.length}")
            context.startActivity(intent)
            AppToast.show(context, "接收到投屏，正在播放")
            return
        }

        if (!target.needsBangumiDetail && (!target.bvid.isNullOrBlank() || (target.aid ?: 0L) > 0L)) {
            val intent =
                Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .apply {
                        target.bvid?.takeIf { it.isNotBlank() }?.let { putExtra(PlayerActivity.EXTRA_BVID, it) }
                        target.aid?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_AID, it) }
                        target.cid?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_CID, it) }
                        target.epId?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_EP_ID, it) }
                        target.seasonId?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_SEASON_ID, it) }
                    }
            AppLog.i(
                TAG,
                "launch player bvid=${target.bvid.orEmpty()} aid=${target.aid ?: -1L} cid=${target.cid ?: -1L} ep=${target.epId ?: -1L} ss=${target.seasonId ?: -1L}",
            )
            context.startActivity(intent)
            AppToast.show(context, "接收到投屏，正在播放")
            return
        }

        val intent =
            Intent(context, BangumiDetailActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, false)
                .apply {
                    target.epId?.takeIf { it > 0L }?.let {
                        putExtra(BangumiDetailActivity.EXTRA_EP_ID, it)
                        putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_ID, it)
                    }
                    target.seasonId?.takeIf { it > 0L }?.let {
                        putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, it)
                    }
                }
        AppLog.i(TAG, "launch bangumi detail ep=${target.epId ?: -1L} ss=${target.seasonId ?: -1L}")
        context.startActivity(intent)
        AppToast.show(context, "接收到投屏，正在打开番剧页")
    }

    private fun parse(rawUri: String): ParsedCastLink? {
        // AirPlay/DLNA senders may wrap URL repeatedly (query nesting + percent-encoding).
        val seeds = LinkedHashSet<String>()
        seeds += rawUri.trim()
        seeds += decodeCandidates(rawUri)

        val expanded = LinkedHashSet<String>()
        for (seed in seeds) {
            expanded += seed
            expanded += extractUrlParameters(seed)
        }

        var best: ParsedCastLink? = null
        for (candidate in expanded) {
            val parsed = parseSingleCandidate(candidate) ?: continue
            if (best == null) best = parsed
            if (!parsed.bvid.isNullOrBlank() || (parsed.aid ?: 0L) > 0L) return parsed
            if ((parsed.epId ?: 0L) > 0L) best = parsed
        }
        if (best == null) {
            AppLog.w(TAG, "parse failed after candidates=${expanded.size}")
        } else {
            AppLog.d(TAG, "parse fallback picked ep=${best.epId ?: -1L} ss=${best.seasonId ?: -1L}")
        }
        return best
    }

    private fun parseSingleCandidate(raw: String): ParsedCastLink? {
        val candidate = raw.trim()
        if (candidate.isBlank()) return null

        val bvid = BVID_REGEX.find(candidate)?.value?.uppercase(Locale.US)
        val aid =
            (AID_QUERY_REGEX.find(candidate)?.groupValues?.getOrNull(1)
                ?: AID_PATH_REGEX.find(candidate)?.groupValues?.getOrNull(1))
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        val cid = CID_QUERY_REGEX.find(candidate)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        val epId =
            (EP_QUERY_REGEX.find(candidate)?.groupValues?.getOrNull(1)
                ?: EP_PATH_REGEX.find(candidate)?.groupValues?.getOrNull(1))
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        val seasonId =
            (SEASON_QUERY_REGEX.find(candidate)?.groupValues?.getOrNull(1)
                ?: SEASON_PATH_REGEX.find(candidate)?.groupValues?.getOrNull(1))
                ?.toLongOrNull()
                ?.takeIf { it > 0L }

        if (bvid == null && aid == null && epId == null && seasonId == null) {
            val direct = parseDirectMediaUrl(candidate)
            if (direct != null) {
                return ParsedCastLink(directUrl = direct)
            }
            return null
        }
        return ParsedCastLink(
            bvid = bvid,
            aid = aid,
            cid = cid,
            epId = epId,
            seasonId = seasonId,
        )
    }

    private fun extractUrlParameters(raw: String): Set<String> {
        val out = LinkedHashSet<String>()
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return out
        val names = uri.queryParameterNames
        for (name in names) {
            val lower = name.lowercase(Locale.US)
            val isUrlLike =
                lower.contains("url") ||
                    lower.contains("target") ||
                    lower.contains("play") ||
                    lower.contains("stream")
            if (!isUrlLike) continue
            val value = uri.getQueryParameter(name)?.trim().orEmpty()
            if (value.isBlank()) continue
            out += value
            out += decodeCandidates(value)
        }
        return out
    }

    private fun decodeCandidates(raw: String): Set<String> {
        val out = LinkedHashSet<String>()
        var current = raw
        repeat(3) {
            val decoded =
                runCatching { URLDecoder.decode(current, Charsets.UTF_8.name()) }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
            if (decoded.isBlank() || decoded == current) return@repeat
            out += decoded
            current = decoded
        }
        return out
    }

    private fun parseDirectMediaUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (!looksLikeDirectMediaUrl(trimmed)) return null
        return trimmed
    }

    /**
     * Prefer in-app playback when possible:
     * 1) Try infer cid from direct media url
     * 2) Use cid as search keyword
     * 3) Verify cid against /view pages data
     * If any step fails, return null and caller will fallback to direct-url playback.
     */
    private suspend fun tryResolveStationTargetFromDirectUrl(directUrl: String): ResolvedCastTarget? {
        val cidCandidates = extractCidCandidatesFromDirectUrl(directUrl)
        if (cidCandidates.isEmpty()) {
            AppLog.d(TAG, "direct url resolve skip: no cid candidate")
            return null
        }
        AppLog.d(TAG, "direct url resolve cid candidates=${cidCandidates.joinToString(limit = 5)}")

        for (cid in cidCandidates.take(MAX_CID_CANDIDATES)) {
            val matched = queryStationTargetByCid(cid)
            if (matched != null) {
                AppLog.i(
                    TAG,
                    "direct url resolved to station target cid=$cid bvid=${matched.bvid.orEmpty()} aid=${matched.aid ?: -1L}",
                )
                return matched
            }
        }
        AppLog.i(TAG, "direct url station resolve failed, fallback to direct playback")
        return null
    }

    private suspend fun queryStationTargetByCid(cid: Long): ResolvedCastTarget? {
        val search =
            runCatching { BiliApi.searchVideo(keyword = cid.toString(), page = 1, order = "totalrank") }
                .onFailure { AppLog.w(TAG, "search by cid failed cid=$cid", it) }
                .getOrNull()
                ?: return null

        for (card in search.items.take(MAX_SEARCH_ITEMS_TO_VERIFY)) {
            val bvid = card.bvid.trim()
            if (bvid.isBlank()) continue
            if (card.cid == cid) {
                return ResolvedCastTarget(
                    bvid = bvid,
                    aid = card.aid?.takeIf { it > 0L },
                    cid = cid,
                    needsBangumiDetail = false,
                )
            }
            val viewData =
                runCatching { BiliApi.view(bvid).optJSONObject("data") ?: JSONObject() }
                    .onFailure { AppLog.w(TAG, "view verify failed bvid=$bvid cid=$cid", it) }
                    .getOrNull()
                    ?: continue
            val matchedCid = findMatchedCidInView(viewData, cid) ?: continue
            val aid = viewData.optLong("aid").takeIf { it > 0L } ?: card.aid?.takeIf { it > 0L }
            return ResolvedCastTarget(
                bvid = bvid,
                aid = aid,
                cid = matchedCid,
                needsBangumiDetail = false,
            )
        }
        return null
    }

    private fun findMatchedCidInView(viewData: JSONObject, expectedCid: Long): Long? {
        val rootCid = viewData.optLong("cid").takeIf { it > 0L }
        if (rootCid == expectedCid) return expectedCid
        val pages = viewData.optJSONArray("pages") ?: return null
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            val cid = page.optLong("cid").takeIf { it > 0L } ?: continue
            if (cid == expectedCid) return cid
        }
        return null
    }

    private fun extractCidCandidatesFromDirectUrl(directUrl: String): List<Long> {
        val out = LinkedHashSet<Long>()
        val uri = runCatching { Uri.parse(directUrl) }.getOrNull()
        val cidFromQuery = uri?.getQueryParameter("cid")?.toLongOrNull()?.takeIf { it > 0L }
        if (cidFromQuery != null) out += cidFromQuery

        val path = uri?.path.orEmpty()
        // Typical bilibili direct path: .../<cid>/<cid>-1-192.mp4
        Regex("(?i)/(\\d{6,18})-[0-9]+-[0-9]+\\.(?:mp4|flv|m3u8|mkv|mov)$")
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { out += it }

        Regex("/(\\d{6,18})(?:/|$)")
            .findAll(path)
            .forEach { m ->
                m.groupValues.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }?.let { out += it }
            }
        return out.toList()
    }

    private fun looksLikeDirectMediaUrl(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (host.isBlank()) return false
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".flv") || path.endsWith(".mkv") || path.endsWith(".mov")) {
            return true
        }
        if (host.contains("bilivideo.com") || host.contains("hdslb.com")) {
            return true
        }
        return false
    }

}
