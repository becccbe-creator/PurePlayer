
package dk.pureplayer

import android.net.Uri
import androidx.media3.cast.CastPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
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

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var connectionPool: ExecutorService? = null

    private val musicFiles = ConcurrentHashMap<String, Uri>()

    override fun onCreate() {
        super.onCreate()

        activeInstance = this

        val exoPlayer = ExoPlayer.Builder(this).build()
        localPlayer = exoPlayer

        val castPlayer = CastPlayer.Builder(this)
            .setLocalPlayer(exoPlayer)
            .build()

        mediaSession = MediaSession.Builder(this, castPlayer).build()

        startMusicServer()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    private fun createStreamUrl(uri: Uri): String? {
        val port = serverSocket?.localPort ?: return null
        val ipAddress = getLocalWifiAddress() ?: return null

        val token = UUID.randomUUID().toString()
        musicFiles[token] = uri

        return "http://$ipAddress:$port/stream/$token"
    }

    private fun findOriginalUri(streamUrl: String): Uri? {
        return try {
            val token = Uri.parse(streamUrl).lastPathSegment ?: return null
            musicFiles[token]
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

            connectionPool = Executors.newFixedThreadPool(4)

            serverThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = serverSocket?.accept() ?: break

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
                    !it.isLoopbackAddress && it.isSiteLocalAddress
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

                val reader = BufferedReader(
                    InputStreamReader(
                        client.getInputStream(),
                        Charsets.UTF_8
                    )
                )

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
                            line.substring(0, separator).trim().lowercase()
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

                val token = path.removePrefix("/stream/").substringBefore("?")
                val uri = musicFiles[token]

                if (uri == null) {
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
                    this,
                    uri,
                    headers["range"],
                    method == "HEAD",
                    client.getOutputStream()
                )

            } catch (_: Exception) {
                // Klienten kan afbryde forbindelsen under streaming.
            }
        }
    }

    private fun streamFile(
        context: Context,
        uri: Uri,
        rangeHeader: String?,
        headOnly: Boolean,
        output: OutputStream
    ) {
        var descriptor: AssetFileDescriptor? = null

        try {
            descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")

            if (descriptor == null) {
                sendResponse(output, 404, "Not Found", "text/plain", 0)
                return
            }

            val startOffset = descriptor.startOffset

            val fileLength = if (
                descriptor.length != AssetFileDescriptor.UNKNOWN_LENGTH
            ) {
                descriptor.length
            } else {
                descriptor.parcelFileDescriptor.statSize - startOffset
            }

            if (fileLength <= 0) {
                sendResponse(output, 500, "File Size Unknown", "text/plain", 0)
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

                    start = (fileLength - suffixLength).coerceAtLeast(0)
                } else {
                    start = first.toLongOrNull() ?: -1L
                    end = if (last.isEmpty()) {
                        fileLength - 1
                    } else {
                        last.toLongOrNull() ?: -1L
                    }
                }

                if (start < 0 || end < start || start >= fileLength) {
                    sendRangeError(output, fileLength)
                    return
                }

                end = end.coerceAtMost(fileLength - 1)
                partial = true
            }

            val contentLength = end - start + 1

            val mimeType = context.contentResolver.getType(uri)
                ?: when (
                    uri.lastPathSegment
                        ?.substringAfterLast(".", "")
                        ?.lowercase()
                ) {
                    "mp3" -> "audio/mpeg"
                    "flac" -> "audio/flac"
                    else -> "application/octet-stream"
                }

            val status = if (partial) 206 else 200
            val statusText = if (partial) "Partial Content" else "OK"

            val header = buildString {
                append("HTTP/1.1 $status $statusText\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n")

                if (partial) {
                    append("Content-Range: bytes $start-$end/$fileLength\r\n")
                }

                append("Access-Control-Allow-Origin: *\r\n")
                append("\r\n")
            }

            output.write(header.toByteArray(Charsets.UTF_8))
            output.flush()

            if (headOnly) return

            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.channel.position(startOffset + start)

                val buffer = ByteArray(32 * 1024)
                var remaining = contentLength

                while (remaining > 0) {
                    val bytesToRead = minOf(
                        buffer.size.toLong(),
                        remaining
                    ).toInt()

                    val count = input.read(buffer, 0, bytesToRead)
                    if (count <= 0) break

                    output.write(buffer, 0, count)
                    remaining -= count
                }

                output.flush()
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

        @JvmStatic
        fun getStreamUrl(uri: Uri): String? {
            return activeInstance?.createStreamUrl(uri)
        }

        @JvmStatic
        fun getOriginalUri(streamUrl: String): Uri? {
            return activeInstance?.findOriginalUri(streamUrl)
        }
    }
}
