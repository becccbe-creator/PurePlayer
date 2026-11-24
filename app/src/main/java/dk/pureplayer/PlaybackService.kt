
package dk.pureplayer

import androidx.media3.cast.CastPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var localPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        // Lokal musikafspiller
        val exoPlayer = ExoPlayer.Builder(this).build()
        localPlayer = exoPlayer

        // Google Cast-afspiller med lokal afspilning som fallback
        val castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(exoPlayer)
            .build()

        // Fælles MediaSession til lokal og fjern afspilning
        mediaSession = MediaSession.Builder(this, castPlayer)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null

        localPlayer?.release()
        localPlayer = null

        super.onDestroy()
    }
}
