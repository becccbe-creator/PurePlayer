
package dk.pureplayer

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dk.pureplayer.audio.TenBandEqualizer
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var localPlayer: ExoPlayer? = null

    private val equalizer = TenBandEqualizer()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var connectionPool: ExecutorService? = null

    /**
     * En stream kan enten være en original SAF-fil
     * eller en færdigbehandlet Cast-fil.
     */
    private data class StreamSource(
        val originalUri: Uri,
        val originalFileUri: Uri? = null,
        val file: File? = null
    )

    private val musicFiles =
        ConcurrentHashMap<String, StreamSource>()

    override fun onCreate() {
        super.onCreate()

        activeInstance = this

        val renderersFactory =
            object : DefaultRenderersFactory(this) {

                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean
                ): AudioSink? {

                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(
                            enableAudioOutputPlaybackParams
                        )
                        .setAudioProcessors(
                            arrayOf<AudioProcessor>(equalizer)
                        )
                        .build()
                }
            }

        val exoPlayer = ExoPlayer.Builder(
            this,
            renderersFactory
        ).build()

        localPlayer = exoPlayer

        val castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(exoPlayer)
            .build()

        mediaSession = MediaSession.Builder(
            this,
            castPlayer
        ).build()

        startMusicServer()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    /**
     * Opretter stream-adresse til en original SAF-fil.
     */
    private fun createStreamUrl(uri: Uri): String? {
        val port = serverSocket?.localPort ?: return null
        val ipAddress = getLocalWifiAddress() ?: return null

        val token = UUID.randomUUID().toString()

        musicFiles[token] = StreamSource(
            originalUri = uri,
            originalFileUri = uri
        )

        return "http://$ipAddress:$port/stream/$token"
    }

    /**
     * Registrerer en EQ-behandlet MP4-fil til Cast.
     *
     * originalUri bruges til at identificere det
     * oprindelige musiknummer i PurePlayer.
     */
    private fun createCastStreamUrl(
        file: File,
        originalUri: Uri
    ): String? {

        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            return null
        }

        val port = serverSocket?.localPort ?: return null
        val ipAddress = getLocalWifiAddress() ?: return null

        val token = UUID.randomUUID().toString()

        musicFiles[token] = StreamSource(
            originalUri = originalUri,
            file = file
        )

        return "http://$ipAddress:$port/stream/$token"
    }

    /**
     * Finder originalfilen ud fra en stream-adresse.
     */
    private fun findOriginalUri(streamUrl: String): Uri? {
        return try {
            val token = Uri.parse(streamUrl).lastPathSegment
                ?: return null

            musicFiles[token]?.originalUri

        } catch (_: Exception) {
            null
        }
    }

    private fun startMusicServer() {
        try {
            serverSocket = ServerSocket(
                0,
                50,
                InetAddress.getByName("0.0.0.0")
            )

            connectionPool =
                Executors.newFixedThreadPool(4)

            serverThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket =
                            serverSocket?.accept() ?: break

                        connectionPool?.execute {
                            handleConnection(socket)
                        } ?: socket.close()

                    } catch (_: Exception) {
                        break
                    }
                }
            }.apply {
                name = "PurePlayer-MusicServer"
                isDaemon = true
                start()
            }

        } catch (_: Exception) {
            serverSocket?.close()
            serverSocket = null
        }
    }

    private fun getLocalWifiAddress(): String? {
        return try {
            val interfaces = Collections.list(
                NetworkInterface.getNetworkInterfaces()
            )

            val addresses = interfaces
                .filter { it.isUp && !it.isLoopback }
                .sortedBy {
                    if (it.name.startsWith("wlan")) 0 else 1
                }
                .flatMap {
                    Collections.list(it.inetAddresses)
                }

            addresses
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress &&
                    it.isSiteLocalAddress
                }
                ?.hostAddress

        } catch (_: Exception) {
            null
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15000

                val reader = client.getInputStream()
                    .bufferedReader(Charsets.UTF_8)

                val requestLine = reader.readLine() ?: return
                val requestParts = requestLine.split(" ")

                if (requestParts.size < 2) return

                val method = requestParts[0]
                val path = requestParts[1]

                val headers = mutableMapOf<String, String>()

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break

                    val separator = line.indexOf(':')

                    if (separator > 0) {
                        headers[
                            line.substring(0, separator)
                                .trim()
                                .lowercase()
                        ] = line.substring(separator + 1).trim()
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    sendResponse(
                        client.getOutputStream(),
                        405,
                        "Method Not Allowed",
                        "text/plain",
                        0
                    )
                    return
                }

                if (!path.startsWith("/stream/")) {
                    sendResponse(
                        client.getOutputStream(),
                        404,
                        "Not Found",
                        "text/plain",
                        0
                    )
                    return
                }

                val token = path
                    .removePrefix("/stream/")
                    .substringBefore("?")

                val source = musicFiles[token]

                if (source == null) {
                    sendResponse(
                        client.getOutputStream(),
                        404,
                        "Not Found",
                        "text/plain",
                        0
                    )
                    return
                }

                streamFile(
                    source = source,
                    rangeHeader = headers["range"],
                    headOnly = method == "HEAD",
                    output = client.getOutputStream()
                )

            } catch (_: Exception) {
                // Klienten kan afbryde forbindelsen.
            }
        }
    }

    /**
     * Serverer enten en original SAF-fil eller en
     * færdigbehandlet MP4-fil.
     *
     * Understøtter HTTP Range, så Cast kan søge
     * frem og tilbage i musikken.
     */
    private fun streamFile(
        source: StreamSource,
        rangeHeader: String?,
        headOnly: Boolean,
        output: OutputStream
    ) {

        var descriptor: AssetFileDescriptor? = null

        try {
            val file = source.file

            val fileLength: Long
            val startOffset: Long
            val mimeType: String

            if (file != null) {

                if (!file.exists() || !file.isFile) {
                    sendResponse(
                        output,
                        404,
                        "Not Found",
                        "text/plain",
                        0
                    )
                    return
                }

                fileLength = file.length()
                startOffset = 0L
                mimeType = "audio/mp4"

            } else {

                val uri = source.originalFileUri
                    ?: source.originalUri

                descriptor = contentResolver
                    .openAssetFileDescriptor(uri, "r")

                if (descriptor == null) {
                    sendResponse(
                        output,
                        404,
                        "Not Found",
                        "text/plain",
                        0
                    )
                    return
                }

                startOffset = descriptor.startOffset

                fileLength = if (
                    descriptor.length !=
                    AssetFileDescriptor.UNKNOWN_LENGTH
                ) {
                    descriptor.length
                } else {
                    descriptor.parcelFileDescriptor.statSize -
                    startOffset
                }

                mimeType = contentResolver.getType(uri)
                    ?: when (
                        uri.lastPathSegment
                            ?.substringAfterLast(".", "")
                            ?.lowercase()
                    ) {
                        "mp3" -> "audio/mpeg"
                        "flac" -> "audio/flac"
                        else -> "application/octet-stream"
                    }
            }

            if (fileLength <= 0L) {
                sendResponse(
                    output,
                    500,
                    "File Size Unknown",
                    "text/plain",
                    0
                )
                return
            }

            var start = 0L
            var end = fileLength - 1
            var partial = false

            if (rangeHeader != null) {

                val match = Regex("""bytes=(\d*)-(\d*)""")
                    .matchEntire(rangeHeader)

                if (match == null) {
                    sendRangeError(output, fileLength)
                    return
                }

                val first = match.groupValues[1]
                val last = match.groupValues[2]

                if (first.isEmpty() && last.isEmpty()) {
                    sendRangeError(output, fileLength)
                    return
                }

                if (first.isEmpty()) {

                    val suffixLength = last.toLongOrNull()

                    if (suffixLength == null || suffixLength <= 0) {
                        sendRangeError(output, fileLength)
                        return
                    }

                    start = (
                        fileLength - suffixLength
                    ).coerceAtLeast(0)

                } else {

                    start = first.toLongOrNull() ?: -1L

                    end = if (last.isEmpty()) {
                        fileLength - 1
                    } else {
                        last.toLongOrNull() ?: -1L
                    }
                }

                if (
                    start < 0 ||
                    end < start ||
                    start >= fileLength
                ) {
                    sendRangeError(output, fileLength)
                    return
                }

                end = end.coerceAtMost(fileLength - 1)
                partial = true
            }

            val contentLength = end - start + 1

            val status = if (partial) 206 else 200
            val statusText = if (partial) {
                "Partial Content"
            } else {
                "OK"
            }

            val header = buildString {
                append("HTTP/1.1 $status $statusText\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n")

                if (partial) {
                    append(
                        "Content-Range: bytes " +
                        "$start-$end/$fileLength\r\n"
                    )
                }

                append("Access-Control-Allow-Origin: *\r\n")
                append("\r\n")
            }

            output.write(header.toByteArray(Charsets.UTF_8))
            output.flush()

            if (headOnly) return

            if (file != null) {

                FileInputStream(file).use { input ->
                    input.channel.position(start)

                    copyStream(
                        input = input,
                        output = output,
                        contentLength = contentLength
                    )
                }

            } else {

                val activeDescriptor = descriptor
                    ?: return

                FileInputStream(
                    activeDescriptor.fileDescriptor
                ).use { input ->

                    input.channel.position(startOffset + start)

                    copyStream(
                        input = input,
                        output = output,
                        contentLength = contentLength
                    )
                }
            }

        } catch (_: Exception) {
            // Filen kan være utilgængelig eller forbindelsen afbrudt.
        } finally {
            try {
                descriptor?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyStream(
        input: FileInputStream,
        output: OutputStream,
        contentLength: Long
    ) {
        val buffer = ByteArray(32 * 1024)
        var remaining = contentLength

        while (remaining > 0) {
            val bytesToRead = minOf(
                buffer.size.toLong(),
                remaining
            ).toInt()

            val count = input.read(
                buffer,
                0,
                bytesToRead
            )

            if (count <= 0) break

            output.write(buffer, 0, count)
            remaining -= count
        }

        output.flush()
    }

    private fun sendResponse(
        output: OutputStream,
        status: Int,
        message: String,
        contentType: String,
        length: Long
    ) {
        val response = buildString {
            append("HTTP/1.1 $status $message\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        try {
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun sendRangeError(
        output: OutputStream,
        fileLength: Long
    ) {
        try {
            val response =
                "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                "Content-Range: bytes */$fileLength\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"

            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()

        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        activeInstance = null

        // Slet kun midlertidige Cast-filer.
        musicFiles.values.forEach { source ->
            try {
                source.file?.delete()
            } catch (_: Exception) {
            }
        }

        musicFiles.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        serverThread?.interrupt()
        serverThread = null

        connectionPool?.shutdownNow()
        connectionPool = null

        mediaSession?.release()
        mediaSession = null

        localPlayer?.release()
        localPlayer = null

        super.onDestroy()
    }

    companion object {

        @Volatile
        private var activeInstance: PlaybackService? = null

        /**
         * Eksisterende metode til originale musikfiler.
         */
        @JvmStatic
        fun getStreamUrl(uri: Uri): String? {
            return activeInstance?.createStreamUrl(uri)
        }

        /**
         * Ny metode til EQ-behandlede Cast-filer.
         */
        @JvmStatic
        fun getCastStreamUrl(
            file: File,
            originalUri: Uri
        ): String? {
            return activeInstance?.createCastStreamUrl(
                file,
                originalUri
            )
        }

        /**
         * Finder original URI fra både gamle og nye streams.
         */
        @JvmStatic
        fun getOriginalUri(streamUrl: String): Uri? {
            return activeInstance?.findOriginalUri(streamUrl)
        }

        @JvmStatic
        fun setEqualizerEnabled(enabled: Boolean) {
            activeInstance?.equalizer?.setEnabled(enabled)
        }

        @JvmStatic
        fun isEqualizerEnabled(): Boolean {
            return activeInstance?.equalizer?.isEnabled() ?: true
        }

        @JvmStatic
        fun setEqualizerBandGain(
            index: Int,
            gainDb: Float
        ) {
            activeInstance?.equalizer?.setBandGain(
                index,
                gainDb
            )
        }

        @JvmStatic
        fun setEqualizerGains(gains: FloatArray) {
            activeInstance?.equalizer?.setAllGains(gains)
        }

        @JvmStatic
        fun getEqualizerBandGains(): FloatArray {
            return activeInstance?.equalizer?.getBandGains()
                ?: FloatArray(TenBandEqualizer.BAND_COUNT)
        }
    }
}
