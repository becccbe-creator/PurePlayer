
package dk.pureplayer.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PurePlayer Cast Audio Transformer.
 *
 * Behandler musik med PurePlayers egen equalizer
 * og eksporterer resultatet som AAC i MP4.
 *
 * Originalfilen bliver aldrig ændret.
 */
@UnstableApi
class CastAudioTransformer(context: Context) {

    interface Callback {
        fun onSuccess(outputFile: File)

        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val activeTransformers =
        ConcurrentHashMap<String, Transformer>()

    /**
     * Starter en separat lydkonvertering.
     *
     * inputUri:
     * Original MP3- eller FLAC-fil fra brugerens mappe.
     *
     * enabled:
     * Om equalizeren er aktiveret.
     *
     * gains:
     * De ti aktuelle EQ-indstillinger i dB.
     */
    fun transform(
        inputUri: Uri,
        enabled: Boolean,
        gains: FloatArray,
        callback: Callback
    ) {
        require(gains.size == TenBandEqualizer.BAND_COUNT) {
            "Equalizeren skal have præcis 10 bånd."
        }

        val savedGains = gains.copyOf()

        mainHandler.post {
            startTransform(
                inputUri = inputUri,
                enabled = enabled,
                gains = savedGains,
                callback = callback
            )
        }
    }

    private fun startTransform(
        inputUri: Uri,
        enabled: Boolean,
        gains: FloatArray,
        callback: Callback
    ) {
        val outputDirectory =
            File(appContext.cacheDir, "cast_audio")

        if (!outputDirectory.exists() &&
            !outputDirectory.mkdirs()
        ) {
            callback.onError(
                "Kunne ikke oprette Cast-mappen."
            )
            return
        }

        val outputFile = File(
            outputDirectory,
            "cast_${UUID.randomUUID()}.mp4"
        )

        val transformId = outputFile.name

        val equalizer = TenBandEqualizer().apply {
            setAllGains(gains)
            setEnabled(enabled)
        }

        val audioProcessors = listOf(
            ToInt16PcmAudioProcessor(),
            equalizer
        )

        val effects = Effects(
            audioProcessors,
            emptyList()
        )

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(inputUri)
        )
            .setEffects(effects)
            .build()

        val listener = object : Transformer.Listener {

            override fun onCompleted(
                composition: Composition,
                exportResult: ExportResult
            ) {
                activeTransformers.remove(transformId)

                if (outputFile.exists() &&
                    outputFile.length() > 0L
                ) {
                    callback.onSuccess(outputFile)
                } else {
                    outputFile.delete()

                    callback.onError(
                        "Konverteringen blev færdig, " +
                        "men lydfilen er tom."
                    )
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                activeTransformers.remove(transformId)
                outputFile.delete()

                callback.onError(
                    "Lydkonverteringen fejlede: " +
                    (exportException.message
                        ?: "Ukendt fejl")
                )
            }
        }

        try {
            val transformer = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()

            activeTransformers[transformId] = transformer

            transformer.start(
                editedMediaItem,
                outputFile.absolutePath
            )

        } catch (exception: Exception) {
            activeTransformers.remove(transformId)
            outputFile.delete()

            callback.onError(
                "Kunne ikke starte lydkonverteringen: " +
                (exception.message ?: "Ukendt fejl")
            )
        }
    }

    /**
     * Stopper alle aktive konverteringer.
     */
    fun cancelAll() {
        mainHandler.post {
            activeTransformers.values.forEach { transformer ->
                try {
                    transformer.cancel()
                } catch (_: Exception) {
                    // Ignorer fejl ved annullering.
                }
            }

            activeTransformers.clear()
        }
    }

    /**
     * Sletter gamle midlertidige Cast-filer.
     */
    fun cleanCache() {
        val directory = File(
            appContext.cacheDir,
            "cast_audio"
        )

        if (directory.exists()) {
            directory.listFiles()?.forEach { file ->
                try {
                    file.delete()
                } catch (_: Exception) {
                    // Ignorer oprydningsfejl.
                }
            }
        }
    }
}
