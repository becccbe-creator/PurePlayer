
package dk.pureplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import dk.pureplayer.audio.CastAudioTransformer

/**
 * Håndterer konvertering af musik til Google Cast.
 *
 * Musikken bliver behandlet med PurePlayers equalizer,
 * inden den sendes videre til Cast-enheden.
 */
class CastQueueManager(
    context: Context,
    private val controller: MediaController,
    private val onError: (String) -> Unit = {}
) {

    private val transformer = CastAudioTransformer(
        context.applicationContext
    )

    private var generation = 0
    private var queue = emptyList<LibraryTrack>()
    private var nextPosition = 0
    private var firstItemLoaded = false

    /**
     * Starter en Cast-kø.
     *
     * startTrackIndex er nummerets position i tracks.
     * queueIndices indeholder rækkefølgen, der skal afspilles.
     */
    fun playQueue(
        tracks: List<LibraryTrack>,
        queueIndices: List<Int>,
        startTrackIndex: Int
    ) {
        cancel()

        if (tracks.isEmpty()) return

        val validQueue = queueIndices
            .distinct()
            .filter { it in tracks.indices }

        val startPosition = validQueue.indexOf(startTrackIndex)

        if (startPosition < 0) {
            onError("Sangen findes ikke i afspilningskøen.")
            return
        }

        // Start med den valgte sang og fortsæt derefter i køens rækkefølge.
        val orderedIndices = validQueue.drop(startPosition) +
            validQueue.take(startPosition)

        queue = orderedIndices.map { tracks[it] }
        nextPosition = 0
        firstItemLoaded = false

        transformNext()
    }

    /**
     * Konverterer næste sang.
     * Der behandles kun én sang ad gangen.
     */
    private fun transformNext() {

        if (nextPosition >= queue.size) return

        val requestGeneration = generation
        val track = queue[nextPosition]

        val equalizerEnabled =
            PlaybackService.isEqualizerEnabled()

        val equalizerGains =
            PlaybackService.getEqualizerBandGains()

        transformer.transform(
            track.uri,
            equalizerEnabled,
            equalizerGains,
            object : CastAudioTransformer.Callback {

                override fun onSuccess(file: java.io.File) {

                    if (requestGeneration != generation) {
                        file.delete()
                        return
                    }

                    val streamUrl = PlaybackService.getCastStreamUrl(
                        file,
                        track.uri
                    )

                    if (streamUrl.isNullOrBlank()) {
                        onError("Kunne ikke oprette Cast-stream.")
                        nextPosition++
                        transformNext()
                        return
                    }

                    val metadata = MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setMediaId(track.uri.toString())
                        .setUri(streamUrl)
                        .setMimeType("audio/mp4")
                        .setMediaMetadata(metadata)
                        .build()

                    if (!firstItemLoaded) {

                        firstItemLoaded = true

                        controller.setMediaItem(mediaItem)
                        controller.prepare()
                        controller.play()

                    } else {

                        controller.addMediaItem(mediaItem)
                    }

                    nextPosition++

                    // Gør næste sang klar i baggrunden.
                    transformNext()
                }

                override fun onError(message: String) {

                    if (requestGeneration != generation) {
                        return
                    }

                    onError(
                        "Kunne ikke behandle '${track.title}': $message"
                    )

                    nextPosition++
                    transformNext()
                }
            }
        )
    }

    /**
     * Stopper igangværende konvertering og gør køen ugyldig.
     */
    fun cancel() {
        generation++
        transformer.cancelAll()

        queue = emptyList()
        nextPosition = 0
        firstItemLoaded = false
    }
}
