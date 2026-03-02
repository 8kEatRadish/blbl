package blbl.cat3399.feature.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import blbl.cat3399.feature.player.AudioBalanceLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

@UnstableApi
internal class VolumeBalanceAudioProcessor(
    level: AudioBalanceLevel = AudioBalanceLevel.Off,
) : BaseAudioProcessor() {
    @Volatile
    private var level: AudioBalanceLevel = level

    @Volatile
    private var params: Params = paramsFor(level)

    private var sampleRateHz: Int = 48_000
    private var channelCount: Int = 2
    private var inputEncoding: Int = C.ENCODING_PCM_16BIT
    private var signalAccumulatedSec: Double = 0.0
    private var rmsEmaDb: Double? = null
    private var currentGain: Float = 1.0f

    fun setLevel(level: AudioBalanceLevel) {
        if (this.level == level) return
        this.level = level
        this.params = paramsFor(level)
        resetAdaptiveState()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        out.order(ByteOrder.nativeOrder())

        val currentLevel = level
        if (currentLevel == AudioBalanceLevel.Off || !isActive) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val p = params
        val bytesPerSample =
            when (inputEncoding) {
                C.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }

        val inBuf = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val startPos = inBuf.position()
        val endPos = inBuf.limit()

        val sampleCount = (endPos - startPos) / bytesPerSample
        if (sampleCount <= 0) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        var sumSquares = 0.0
        var peakAbs = 0.0
        when (inputEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                while (inBuf.remaining() >= 4) {
                    val f = inBuf.getFloat().toDouble()
                    val a = abs(f)
                    peakAbs = max(peakAbs, a)
                    sumSquares += f * f
                }
            }

            else -> {
                while (inBuf.remaining() >= 2) {
                    val s = inBuf.getShort()
                    val v = s.toDouble() / 32768.0
                    val a = abs(v)
                    peakAbs = max(peakAbs, a)
                    sumSquares += v * v
                }
            }
        }
        val rms = sqrt(sumSquares / sampleCount.toDouble())
        val rmsDb = if (rms > 0.0) (20.0 * log10(rms)) else -120.0

        val sr = sampleRateHz.coerceAtLeast(8_000)
        val ch = channelCount.coerceAtLeast(1)
        val frameCount = max(1, sampleCount / ch)
        val dtSec = frameCount.toDouble() / sr.toDouble()

        if (rmsDb > p.silenceGateDb) {
            val a = alphaForTimeConstant(dtSec, p.rmsIntegrationSec)
            val prev = rmsEmaDb
            rmsEmaDb =
                if (prev == null) {
                    rmsDb
                } else {
                    prev + (a * (rmsDb - prev))
                }
            signalAccumulatedSec += dtSec
        }

        val desiredGainDb =
            if (rmsEmaDb == null || signalAccumulatedSec < WARMUP_SIGNAL_SEC) {
                0.0
            } else {
                (TARGET_RMS_DB - (rmsEmaDb ?: TARGET_RMS_DB)).coerceIn(p.minGainDb, p.maxGainDb)
            }
        val desiredGainLinear = dbToLinear(desiredGainDb)

        val peakSafeGain =
            if (peakAbs > 0.0) {
                (LIMITER_CEILING / peakAbs).toFloat()
            } else {
                Float.POSITIVE_INFINITY
            }

        val desired = min(desiredGainLinear, peakSafeGain)
        val tau = if (desired < currentGain) p.gainAttackSec else p.gainReleaseSec
        val gainAlpha = alphaForTimeConstant(dtSec, tau).toFloat()
        currentGain = (currentGain + gainAlpha * (desired - currentGain)).coerceIn(p.minGainLinear, p.maxGainLinear)
        if (peakSafeGain.isFinite() && peakSafeGain < currentGain) {
            currentGain = peakSafeGain
        }

        val appliedGain = currentGain
        inBuf.position(startPos)
        when (inputEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                while (inBuf.remaining() >= 4) {
                    val f = inBuf.getFloat()
                    val scaled = f * appliedGain
                    out.putFloat(scaled.coerceIn(-1.0f, 1.0f))
                }
            }

            else -> {
                while (inBuf.remaining() >= 2) {
                    val s = inBuf.getShort().toInt()
                    val scaled = (s.toFloat() * appliedGain).toInt()
                    val clipped = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(clipped.toShort())
                }
            }
        }

        inputBuffer.position(endPos)
        out.flip()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            -> {
                inputEncoding = inputAudioFormat.encoding
                sampleRateHz = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 48_000
                channelCount = inputAudioFormat.channelCount.takeIf { it > 0 } ?: 2
                resetAdaptiveState()
                return inputAudioFormat
            }

            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        resetAdaptiveState()
    }

    override fun onReset() {
        resetAdaptiveState()
    }

    private fun resetAdaptiveState() {
        signalAccumulatedSec = 0.0
        rmsEmaDb = null
        currentGain = 1.0f
    }

    private data class Params(
        val maxGainDb: Double,
        val minGainDb: Double,
        val silenceGateDb: Double,
        val rmsIntegrationSec: Double,
        val gainAttackSec: Double,
        val gainReleaseSec: Double,
        val maxGainLinear: Float,
        val minGainLinear: Float,
    )

    private companion object {
        private const val TARGET_RMS_DB = -20.0

        // Warm up on actual signal before applying any gain changes.
        private const val WARMUP_SIGNAL_SEC = 0.25

        // Hard ceiling to avoid clipping.
        private const val LIMITER_CEILING = 0.98f

        private fun paramsFor(level: AudioBalanceLevel): Params {
            // Safety first:
            // - Keep max amplification small (avoid "sudden loud").
            // - Allow stronger attenuation for higher levels (tame loud videos more).
            // - Rising gain is always slow; falling gain is faster.
            val maxGainDb =
                when (level) {
                    AudioBalanceLevel.High -> 3.0
                    AudioBalanceLevel.Medium -> 3.0
                    AudioBalanceLevel.Low -> 1.5
                    AudioBalanceLevel.Off -> 0.0
                }
            val minGainDb =
                when (level) {
                    AudioBalanceLevel.High -> -24.0
                    AudioBalanceLevel.Medium -> -18.0
                    AudioBalanceLevel.Low -> -12.0
                    AudioBalanceLevel.Off -> 0.0
                }

            val silenceGateDb =
                when (level) {
                    AudioBalanceLevel.High -> -57.0
                    AudioBalanceLevel.Medium -> -55.0
                    AudioBalanceLevel.Low -> -52.0
                    AudioBalanceLevel.Off -> -120.0
                }

            val rmsIntegrationSec =
                when (level) {
                    AudioBalanceLevel.High -> 0.25
                    AudioBalanceLevel.Medium -> 0.40
                    AudioBalanceLevel.Low -> 0.60
                    AudioBalanceLevel.Off -> 0.40
                }

            val attackSec =
                when (level) {
                    AudioBalanceLevel.High -> 0.03
                    AudioBalanceLevel.Medium -> 0.05
                    AudioBalanceLevel.Low -> 0.08
                    AudioBalanceLevel.Off -> 0.05
                }

            val releaseSec =
                when (level) {
                    AudioBalanceLevel.High -> 1.40
                    AudioBalanceLevel.Medium -> 1.20
                    AudioBalanceLevel.Low -> 1.60
                    AudioBalanceLevel.Off -> 1.20
                }

            return Params(
                maxGainDb = maxGainDb,
                minGainDb = minGainDb,
                silenceGateDb = silenceGateDb,
                rmsIntegrationSec = rmsIntegrationSec,
                gainAttackSec = attackSec,
                gainReleaseSec = releaseSec,
                maxGainLinear = dbToLinear(maxGainDb),
                minGainLinear = dbToLinear(minGainDb),
            )
        }

        private fun dbToLinear(db: Double): Float {
            if (!db.isFinite()) return 1.0f
            return 10.0.pow(db / 20.0).toFloat()
        }

        private fun alphaForTimeConstant(dtSec: Double, tauSec: Double): Double {
            if (!dtSec.isFinite() || !tauSec.isFinite()) return 1.0
            if (dtSec <= 0.0) return 0.0
            if (tauSec <= 0.0) return 1.0
            val a = 1.0 - exp(-dtSec / tauSec)
            return a.coerceIn(0.0, 1.0)
        }
    }
}
