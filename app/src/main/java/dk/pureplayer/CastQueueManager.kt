
package dk.pureplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import dk.pureplayer.audio.CastAudioTransformer

class CastQueueManager(
    context: Context,
    private val controller: MediaController,
    private val onError: (String) -> Unit = {}
) {
    private val transformer =
        CastAudioTransformer(context.applicationContext)

    private var generation = 0
    private var queue = emptyList<LibraryTrack>()
    private var nextPosition = 0
    private var firstItemLoaded = false
    private var waitingForNextItem = false

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (
                playbackState == Player.STATE_ENDED &&
                firstItemLoaded &&
                nextPosition < queue.size &&
                !controller.hasNextMediaItem()
            ) {
                waitingForNextItem = true
            }
        }
    }

    init {
        controller.addListener(playerListener)
    }

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

        if (validQueue.isEmpty()) {
            this.onError("Afspilningskøen er tom.")
            return
        }

        val startPosition = validQueue.indexOf(startTrackIndex)

        if (startPosition < 0) {
            this.onError("Sangen findes ikke i afspilningskøen.")
            return
        }

        val orderedIndices =
            validQueue.drop(startPosition) +
            validQueue.take(startPosition)

        queue = orderedIndices.map { tracks[it] }
        nextPosition = 0
        firstItemLoaded = false
        waitingForNextItem = false

        transformNext()
    }

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

                    val streamUrl =
                        PlaybackService.getCastStreamUrl(
                            file,
                            track.uri
                        )

                    if (streamUrl.isNullOrBlank()) {
                        this@CastQueueManager.onError(
                            "Kunne ikke oprette Cast-stream."
                        )
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

                    val shouldStartNewItem =
                        !firstItemLoaded ||
                        waitingForNextItem ||
                        controller.playbackState == Player.STATE_ENDED

                    if (shouldStartNewItem) {
                        firstItemLoaded = true
                        waitingForNextItem = false

                        controller.setMediaItem(mediaItem)
                        controller.prepare()
                        controller.play()
                    } else {
                        controller.addMediaItem(mediaItem)
                    }

                    nextPosition++
                    transformNext()
                }

                override fun onError(message: String) {
                    if (requestGeneration != generation) return

                    this@CastQueueManager.onError(
                        "Kunne ikke behandle '${track.title}': $message"
                    )

                    nextPosition++
                    transformNext()
                }
            }
        )
    }

    fun cancel() {
        generation++
        transformer.cancelAll()

        queue = emptyList()
        nextPosition = 0
        firstItemLoaded = false
        waitingForNextItem = false
    }

    fun release() {
        cancel()
        controller.removeListener(playerListener)
    }
}
