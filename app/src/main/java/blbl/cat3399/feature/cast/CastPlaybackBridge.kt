package blbl.cat3399.feature.cast

import blbl.cat3399.core.log.AppLog
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-service playback bridge:
 * - AirPlay/DLNA services call this bridge when receiving control commands
 * - PlayerActivity registers itself as the current controllable playback target
 */
internal object CastPlaybackBridge {
    private const val TAG = "CastPlaybackBridge"
    private const val SNAPSHOT_TIMEOUT_MS = 250L
    private val mainHandler = Handler(Looper.getMainLooper())

    internal interface Controller {
        fun play()

        fun pause()

        fun stop()

        fun seekTo(positionMs: Long)

        fun currentPositionMs(): Long

        fun durationMs(): Long

        fun isPlaying(): Boolean
    }

    @Volatile
    private var controller: Controller? = null

    fun register(controller: Controller) {
        this.controller = controller
        AppLog.i(TAG, "register controller")
    }

    fun unregister(controller: Controller) {
        if (this.controller === controller) {
            this.controller = null
            AppLog.i(TAG, "unregister controller")
        }
    }

    fun hasController(): Boolean = controller != null

    fun play(): Boolean = withController("play") { it.play() }

    fun pause(): Boolean = withController("pause") { it.pause() }

    fun stop(): Boolean = withController("stop") { it.stop() }

    fun seekTo(positionMs: Long): Boolean = withController("seekTo") { it.seekTo(positionMs.coerceAtLeast(0L)) }

    fun snapshot(): Snapshot? {
        val c = controller ?: return null
        return runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return@runCatching Snapshot(
                    positionMs = c.currentPositionMs().coerceAtLeast(0L),
                    durationMs = c.durationMs().coerceAtLeast(0L),
                    isPlaying = c.isPlaying(),
                )
            }

            val result = AtomicReference<Snapshot?>()
            val error = AtomicReference<Throwable?>()
            val latch = CountDownLatch(1)
            mainHandler.post {
                runCatching {
                    Snapshot(
                        positionMs = c.currentPositionMs().coerceAtLeast(0L),
                        durationMs = c.durationMs().coerceAtLeast(0L),
                        isPlaying = c.isPlaying(),
                    )
                }.onSuccess { result.set(it) }
                    .onFailure { error.set(it) }
                latch.countDown()
            }
            val finished = latch.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                AppLog.w(TAG, "snapshot timeout on main thread")
                return@runCatching null
            }
            val thrown = error.get()
            if (thrown != null) throw thrown
            result.get()
        }.onFailure { AppLog.w(TAG, "snapshot failed", it) }.getOrNull()
    }

    data class Snapshot(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean,
    )

    private inline fun withController(
        actionName: String,
        action: (Controller) -> Unit,
    ): Boolean {
        val c = controller ?: return false
        return runCatching {
            action(c)
            true
        }.onFailure { AppLog.w(TAG, "action failed name=$actionName", it) }.getOrDefault(false)
    }
}
