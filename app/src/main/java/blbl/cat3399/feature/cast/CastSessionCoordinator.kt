package blbl.cat3399.feature.cast

import blbl.cat3399.core.log.AppLog

/**
 * Keeps one active cast owner at a time to avoid multi-device control thrashing.
 * State is bounded (single session) to avoid memory growth.
 */
internal object CastSessionCoordinator {
    private const val TAG = "CastSession"
    private const val SESSION_IDLE_TIMEOUT_MS = 90_000L

    private var active: ActiveSession? = null

    fun tryAcquireOrTouch(
        protocol: String,
        ownerId: String,
        reason: String,
    ): Boolean =
        synchronized(this) {
            val now = System.currentTimeMillis()
            val current = active
            if (current == null || now - current.lastActiveAtMs > SESSION_IDLE_TIMEOUT_MS) {
                active = ActiveSession(protocol = protocol, ownerId = ownerId, startedAtMs = now, lastActiveAtMs = now)
                AppLog.i(TAG, "acquire protocol=$protocol owner=$ownerId reason=$reason")
                return@synchronized true
            }
            if (current.protocol == protocol && current.ownerId == ownerId) {
                active = current.copy(lastActiveAtMs = now)
                return@synchronized true
            }
            AppLog.i(
                TAG,
                "reject protocol=$protocol owner=$ownerId reason=$reason activeProtocol=${current.protocol} activeOwner=${current.ownerId}",
            )
            false
        }

    fun releaseIfOwner(
        protocol: String,
        ownerId: String,
        reason: String,
    ) {
        synchronized(this) {
            val current = active ?: return
            if (current.protocol == protocol && current.ownerId == ownerId) {
                active = null
                AppLog.i(TAG, "release protocol=$protocol owner=$ownerId reason=$reason")
            }
        }
    }

    fun clearProtocol(protocol: String) {
        synchronized(this) {
            val current = active ?: return
            if (current.protocol == protocol) {
                active = null
                AppLog.i(TAG, "clear protocol=$protocol")
            }
        }
    }

    private data class ActiveSession(
        val protocol: String,
        val ownerId: String,
        val startedAtMs: Long,
        val lastActiveAtMs: Long,
    )
}
