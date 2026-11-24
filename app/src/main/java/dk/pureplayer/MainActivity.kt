
package dk.pureplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

data class Track(
    val name: String,
    val uri: Uri
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PurePlayerScreen()
        }
    }
}

suspend fun scanMusicFolder(
    context: Context,
    uri: Uri
): List<Track> = withContext(Dispatchers.IO) {

    val found = mutableListOf<Track>()

    fun scan(folder: DocumentFile) {
        folder.listFiles().forEach { file ->
            if (file.isDirectory) {
                scan(file)
            } else {
                val name = file.name ?: return@forEach

                if (name.endsWith(".mp3", true) ||
                    name.endsWith(".flac", true)
                ) {
                    found.add(Track(name, file.uri))
                }
            }
        }
    }

    try {
        DocumentFile.fromTreeUri(context, uri)?.let {
            scan(it)
        }
    } catch (_: Exception) {
    }

    found.sortedBy { it.name.lowercase() }
}

suspend fun readLibraryTrack(
    context: Context,
    track: Track
): LibraryTrack = withContext(Dispatchers.IO) {

    val fallbackTitle = track.name.substringBeforeLast(".")
    val retriever = MediaMetadataRetriever()

    try {
        retriever.setDataSource(context, track.uri)

        val title = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_TITLE
        )?.takeIf { it.isNotBlank() } ?: fallbackTitle

        val artist = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_ARTIST
        )?.takeIf { it.isNotBlank() } ?: "Ukendt kunstner"

        val album = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_ALBUM
        )?.takeIf { it.isNotBlank() } ?: "Ukendt album"

        val albumArtist = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST
        )?.takeIf { it.isNotBlank() } ?: artist

        LibraryTrack(
            uri = track.uri,
            name = track.name,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist
        )
    } catch (_: Exception) {
        LibraryTrack(
            uri = track.uri,
            name = track.name,
            title = fallbackTitle,
            artist = "Ukendt kunstner",
            album = "Ukendt album",
            albumArtist = "Ukendt kunstner"
        )
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

suspend fun refreshMusicLibrary(
    context: Context,
    folderUri: Uri,
    database: MusicLibraryDatabase
): List<LibraryTrack> = withContext(Dispatchers.IO) {

    val scanned = scanMusicFolder(context, folderUri)
    val cached = database.getTracks()

    val cachedUris = cached.map {
        it.uri.toString()
    }.toSet()

    val newTracks = scanned.filter {
        it.uri.toString() !in cachedUris
    }

    newTracks.forEach { track ->
        val libraryTrack = readLibraryTrack(context, track)
        database.saveTracks(listOf(libraryTrack))
    }

    val validUris = scanned.map {
        it.uri.toString()
    }.toSet()

    database.removeMissingTracks(validUris)

    database.getTracks()
}

suspend fun readArtwork(
    context: Context,
    uri: Uri
): Bitmap? = withContext(Dispatchers.IO) {

    val retriever = MediaMetadataRetriever()

    try {
        retriever.setDataSource(context, uri)

        val bytes = retriever.embeddedPicture

        if (bytes != null) {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size
            )
        } else {
            null
        }
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun PurePlayerScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val preferences = remember {
        context.getSharedPreferences(
            "pureplayer",
            Context.MODE_PRIVATE
        )
    }

    val database = remember {
        MusicLibraryDatabase(context.applicationContext)
    }

    var tracks by remember {
        mutableStateOf(listOf<LibraryTrack>())
    }

    var current by rememberSaveable {
        mutableIntStateOf(-1)
    }

    var playing by remember {
        mutableStateOf(false)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var libraryLoaded by remember {
        mutableStateOf(false)
    }

    var selectedTab by rememberSaveable {
        mutableIntStateOf(0)
    }

    var searchText by remember {
        mutableStateOf("")
    }

    var artwork by remember {
        mutableStateOf<Bitmap?>(null)
    }

    val filteredTracks = remember(tracks, searchText) {
        tracks.filter {
            it.title.contains(searchText, true) ||
            it.artist.contains(searchText, true) ||
            it.album.contains(searchText, true) ||
            it.name.contains(searchText, true)
        }
    }

    val executor = remember {
        Executor { command ->
            Handler(Looper.getMainLooper()).post(command)
        }
    }

    val controllerFuture = remember {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        MediaController.Builder(context, token).buildAsync()
    }

    var controller by remember {
        mutableStateOf<MediaController?>(null)
    }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get()
            } catch (_: Exception) {
                controller = null
            }
        }, executor)

        onDispose {
            MediaController.releaseFuture(controllerFuture)
        }
    }

    DisposableEffect(controller) {

        val listener = object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                current = controller?.currentMediaItemIndex ?: -1
            }
        }

        controller?.addListener(listener)

        onDispose {
            controller?.removeListener(listener)
        }
    }

    LaunchedEffect(Unit) {

        val savedFolder = preferences.getString(
            "music_folder", null
        )

        val lastTrack = preferences.getString(
            "last_track", null
        )

        // Vis det gemte bibliotek med det samme.
        tracks = withContext(Dispatchers.IO) {
            database.getTracks()
        }

        if (tracks.isNotEmpty()) {
            libraryLoaded = true
            current = tracks.indexOfFirst {
                it.uri.toString() == lastTrack
            }
        }

        // Kontroller musikmappen i baggrunden.
        if (savedFolder != null) {
            loading = true

            try {
                tracks = refreshMusicLibrary(
                    context,
                    Uri.parse(savedFolder),
                    database
                )

                libraryLoaded = true

                current = tracks.indexOfFirst {
                    it.uri.toString() == lastTrack
                }
            } catch (_: Exception) {
            }

            loading = false
        }
    }

    LaunchedEffect(current, tracks) {

        val selectedTrack = tracks.getOrNull(current)

        artwork = if (selectedTrack != null) {
            readArtwork(context, selectedTrack.uri)
        } else {
            null
        }
    }

    fun playTrack(index: Int) {

        val mediaController = controller ?: return

        if (index !in tracks.indices) return

        current = index

        mediaController.setMediaItems(
            tracks.map {
                MediaItem.fromUri(it.uri)
            },
            index,
            0L
        )

        mediaController.prepare()
        mediaController.play()

        preferences.edit()
            .putString(
                "last_track",
                tracks[index].uri.toString()
            )
            .apply()
    }

    fun updateLibrary(folderUri: Uri) {
        scope.launch {

            loading = true

            try {
                tracks = refreshMusicLibrary(
                    context,
                    folderUri,
                    database
                )

                libraryLoaded = true

            } catch (_: Exception) {
            }

            loading = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->

        if (treeUri != null) {

            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            preferences.edit()
                .putString("music_folder", treeUri.toString())
                .apply()

            updateLibrary(treeUri)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {

        Scaffold(
            bottomBar = {

                NavigationBar {

                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            Icon(
                                Icons.Default.LibraryMusic,
                                contentDescription = null
                            )
                        },
                        label = { Text("Musik") }
                    )

                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                        },
                        label = { Text("Afspiller") }
                    )

                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = {
                            Icon(
                                Icons.Default.Equalizer,
                                contentDescription = null
                            )
                        },
                        label = { Text("Equalizer") }
                    )
                }
            }
        ) { innerPadding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {

                Text(
                    "PurePlayer",
                    style = MaterialTheme.typography.headlineLarge
                )

                when (selectedTab) {

                    0 -> {

                        Text("Din musik. Din telefon. Ingen abonnementer.")

                        Spacer(Modifier.height(12.dp))

                        if (!libraryLoaded) {
                            Button(
                                onClick = {
                                    folderPicker.launch(null)
                                }
                            ) {
                                Text("Vælg musikmappe")
                            }
                        } else {

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Text(
                                    "${tracks.size} musiknumre",
                                    modifier = Modifier.weight(1f)
                                )

                                TextButton(
                                    onClick = {
                                        folderPicker.launch(null)
                                    }
                                ) {
                                    Text("Skift mappe")
                                }

                                IconButton(
                                    onClick = {
                                        val folder = preferences.getString(
                                            "music_folder", null
                                        )

                                        if (folder != null) {
                                            updateLibrary(Uri.parse(folder))
                                        }
                                    },
                                    enabled = !loading
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Opdater bibliotek"
                                    )
                                }
                            }
                        }

                        if (loading) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Kontrollerer musikbibliotek...")
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = searchText,
                            onValueChange = {
                                searchText = it
                            },
                            label = {
                                Text("Søg i musikken")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f)
                        ) {

                            items(filteredTracks) { track ->

                                val originalIndex = tracks.indexOf(track)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            playTrack(originalIndex)
                                            selectedTab = 1
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            track.title,
                                            maxLines = 1
                                        )

                                        Text(
                                            "${track.artist} · ${track.album}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            playTrack(originalIndex)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Afspil"
                                        )
                                    }
                                }

                                HorizontalDivider()
                            }
                        }
                    }

                    1 -> {

                        Spacer(Modifier.height(14.dp))

                        Text(
                            "AFSPILLER",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(Modifier.height(18.dp))

                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {

                            Box(
                                modifier = Modifier
                                    .size(250.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {

                                if (artwork != null) {

                                    Image(
                                        bitmap = artwork!!.asImageBitmap(),
                                        contentDescription = "Albumcover",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )

                                } else {
                                    Text("♪", style = MaterialTheme.typography.displayLarge)
                                }
                            }
                        }

                        Spacer(Modifier.height(22.dp))

                        Text(
                            tracks.getOrNull(current)?.title
                                ?: "Ingen sang valgt",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            tracks.getOrNull(current)?.artist ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            tracks.getOrNull(current)?.album ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(18.dp))

                        Text(
                            if (playing) "Afspiller nu" else "Klar",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            IconButton(
                                onClick = {
                                    if (current > 0) {
                                        playTrack(current - 1)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = "Forrige",
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    controller?.let {
                                        if (it.isPlaying) {
                                            it.pause()
                                        } else if (current >= 0) {
                                            it.play()
                                        }
                                    }
                                },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(
                                    if (playing) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = "Afspil eller pause",
                                    modifier = Modifier.size(48.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (current + 1 < tracks.size) {
                                        playTrack(current + 1)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Næste",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    2 -> {

                        Spacer(Modifier.height(24.dp))

                        Text(
                            "10-BÅNDS EQUALIZER",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Spacer(Modifier.height(18.dp))

                        Text("Equalizeren er under opbygning.")

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Vi kobler de 10 frekvensbånd direkte " +
                            "til lydmotoren i næste trin. " +
                            "Indtil da ændres lyden ikke."
                        )
                    }
                }
            }
        }
    }
}
