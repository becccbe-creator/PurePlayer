
package dk.pureplayer

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PurePlayerScreen()
        }
    }
}

@Composable
fun PurePlayerScreen() {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    var tracks by remember {
        mutableStateOf(listOf<Pair<String, Uri>>())
    }
    var current by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            val found = mutableListOf<Pair<String, Uri>>()

            fun scan(folder: DocumentFile) {
                folder.listFiles().forEach { file ->
                    if (file.isDirectory) {
                        scan(file)
                    } else {
                        val name = file.name ?: return@forEach

                        if (name.endsWith(".mp3", true) ||
                            name.endsWith(".flac", true)
                        ) {
                            found.add(name to file.uri)
                        }
                    }
                }
            }

            DocumentFile.fromTreeUri(context, treeUri)?.let {
                scan(it)
            }

            tracks = found.sortedBy { it.first.lowercase() }
            current = -1
            player.stop()
            playing = false
        }
    }

    fun playTrack(index: Int) {
        if (index !in tracks.indices) return

        current = index
        player.setMediaItem(
            MediaItem.fromUri(tracks[index].second)
        )
        player.prepare()
        player.play()
        playing = true
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    "PurePlayer",
                    style = MaterialTheme.typography.headlineLarge
                )

                Spacer(Modifier.height(6.dp))

                Text("Din musik. Din telefon. Ingen abonnementer.")

                Spacer(Modifier.height(22.dp))

                Button(
                    onClick = { folderPicker.launch(null) }
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Vælg musikmappe")
                }

                Spacer(Modifier.height(16.dp))

                Text("${tracks.size} musikfiler fundet")

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(tracks) { index, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playTrack(index)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(track.first)
                                if (index == current) {
                                    Text(
                                        "Afspilles",
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            IconButton(
                                onClick = { playTrack(index) }
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (current > 0) playTrack(current - 1)
                        }
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Forrige"
                        )
                    }

                    IconButton(
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else if (current >= 0) {
                                player.play()
                                playing = true
                            }
                        }
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = "Afspil eller pause"
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
                            contentDescription = "Næste"
                        )
                    }
                }
            }
        }
    }
}
