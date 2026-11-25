
package dk.pureplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * PurePlayers egen 10-bånds equalizer.
 *
 * Behandler PCM 16-bit lyd med ti uafhængige
 * parametriske peaking-filtre.
 */
@UnstableApi
class TenBandEqualizer : BaseAudioProcessor() {

    companion object {
        const val BAND_COUNT = 10

        val FREQUENCIES = intArrayOf(
            32, 64, 125, 250, 500,
            1000, 2000, 4000, 8000, 16000
        )

        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        private const val FILTER_Q = 1.0
    }

    private data class Settings(
        val enabled: Boolean,
        val gains: FloatArray,
        val generation: Long
    )

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    @Volatile
    private var settings = Settings(
        enabled = true,
        gains = FloatArray(BAND_COUNT),
        generation = 0L
    )

    @Volatile
    private var coefficients = emptyArray<Coefficients>()

    private var sampleRate = 0
    private var channelCount = 0

    private var stateGeneration = -1L
    private var z1 = Array(BAND_COUNT) { DoubleArray(0) }
    private var z2 = Array(BAND_COUNT) { DoubleArray(0) }

    fun setEnabled(enabled: Boolean) {
        val old = settings
        settings = old.copy(
            enabled = enabled,
            generation = old.generation + 1
        )
    }

    fun isEnabled(): Boolean = settings.enabled

    fun setBandGain(index: Int, gainDb: Float) {
        require(index in 0 until BAND_COUNT)

        val old = settings
        val newGains = old.gains.copyOf()
        newGains[index] = gainDb.coerceIn(
            MIN_GAIN_DB,
            MAX_GAIN_DB
        )

        settings = old.copy(
            gains = newGains,
            generation = old.generation + 1
        )

        updateCoefficients()
    }

    fun setAllGains(gains: FloatArray) {
        require(gains.size == BAND_COUNT)

        val old = settings
        settings = old.copy(
            gains = gains.map {
                it.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            }.toFloatArray(),
            generation = old.generation + 1
        )

        updateCoefficients()
    }

    fun getBandGains(): FloatArray =
        settings.gains.copyOf()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            sampleRate = 0
            channelCount = 0
            coefficients = emptyArray()
            return AudioProcessor.AudioFormat.NOT_SET
        }

        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount

        updateCoefficients()

        return inputAudioFormat
    }

    private fun updateCoefficients() {
        if (sampleRate <= 0) return

        val current = settings

        coefficients = Array(BAND_COUNT) { index ->
            createPeakingFilter(
                frequency = FREQUENCIES[index].toDouble(),
                gainDb = current.gains[index].toDouble(),
                rate = sampleRate.toDouble()
            )
        }
    }

    private fun createPeakingFilter(
        frequency: Double,
        gainDb: Double,
        rate: Double
    ): Coefficients {

        val safeFrequency = frequency.coerceAtMost(rate * 0.45)
        val omega = 2.0 * PI * safeFrequency / rate
        val alpha = sin(omega) / (2.0 * FILTER_Q)
        val amplitude = 10.0.pow(gainDb / 40.0)

        val a0 = 1.0 + alpha / amplitude

        return Coefficients(
            b0 = (1.0 + alpha * amplitude) / a0,
            b1 = (-2.0 * cos(omega)) / a0,
            b2 = (1.0 - alpha * amplitude) / a0,
            a1 = (-2.0 * cos(omega)) / a0,
            a2 = (1.0 - alpha / amplitude) / a0
        )
    }

    private fun resetFilterState() {
        z1 = Array(BAND_COUNT) {
            DoubleArray(channelCount)
        }

        z2 = Array(BAND_COUNT) {
            DoubleArray(channelCount)
        }
    }

    override fun onFlush(
        streamMetadata: AudioProcessor.StreamMetadata
    ) {
        resetFilterState()
        stateGeneration = settings.generation
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        coefficients = emptyArray()
        resetFilterState()
        stateGeneration = -1L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val currentSettings = settings

        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.order(ByteOrder.nativeOrder())

        if (stateGeneration != currentSettings.generation) {
            resetFilterState()
            stateGeneration = currentSettings.generation
        }

        val filters = coefficients
        val channels = channelCount

        while (inputBuffer.remaining() >= 2) {
            val channel = if (channels > 0) {
                output.position() / 2 % channels
            } else {
                0
            }

            val inputSample = inputBuffer.short.toDouble()

            var sample = inputSample

            if (currentSettings.enabled &&
                filters.size == BAND_COUNT &&
                channels > 0
            ) {
                for (band in 0 until BAND_COUNT) {
                    val c = filters[band]

                    val processed =
                        c.b0 * sample + z1[band][channel]

                    z1[band][channel] =
                        c.b1 * sample -
                        c.a1 * processed +
                        z2[band][channel]

                    z2[band][channel] =
                        c.b2 * sample -
                        c.a2 * processed

                    sample = processed
                }
            }

            val clipped = sample
                .coerceIn(-32768.0, 32767.0)
                .toInt()

            output.putShort(clipped.toShort())
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}
