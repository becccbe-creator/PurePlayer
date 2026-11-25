
package dk.pureplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

private data class EqualizerPreset(
    val name: String,
    val gains: List<Float>
)

private val equalizerPresets = listOf(
    EqualizerPreset(
        "Flat",
        listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    ),
    EqualizerPreset(
        "Rock",
        listOf(5f, 4f, 3f, 1f, -1f, 1f, 3f, 4f, 5f, 5f)
    ),
    EqualizerPreset(
        "Pop",
        listOf(-1f, 1f, 3f, 4f, 2f, -1f, -1f, 1f, 3f, 3f)
    ),
    EqualizerPreset(
        "Jazz",
        listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)
    ),
    EqualizerPreset(
        "Classical",
        listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)
    ),
    EqualizerPreset(
        "Vocal",
        listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)
    ),
    EqualizerPreset(
        "Bass Boost",
        listOf(8f, 7f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)
    )
)

private val bandLabels = listOf(
    "32 Hz",
    "64 Hz",
    "125 Hz",
    "250 Hz",
    "500 Hz",
    "1 kHz",
    "2 kHz",
    "4 kHz",
    "8 kHz",
    "16 kHz"
)

@Composable
fun EqualizerScreen() {

    val context = LocalContext.current

    val preferences = remember {
        context.getSharedPreferences(
            "pureplayer_equalizer",
            android.content.Context.MODE_PRIVATE
        )
    }

    var gains by remember {
        mutableStateOf(
            PlaybackService.getEqualizerBandGains().toList()
        )
    }

    var enabled by remember {
        mutableStateOf(true)
    }

    var selectedPreset by remember {
        mutableStateOf("Custom")
    }

    LaunchedEffect(Unit) {

        enabled = preferences.getBoolean(
            "enabled",
            true
        )

        val savedValues = preferences.getString(
            "gains",
            null
        )

        if (savedValues != null) {
            val restored = savedValues
                .split(",")
                .mapNotNull { it.toFloatOrNull() }

            if (restored.size == 10) {
                gains = restored.map {
                    it.coerceIn(-12f, 12f)
                }

                PlaybackService.setEqualizerGains(
                    gains.toFloatArray()
                )
            }
        }

        PlaybackService.setEqualizerEnabled(enabled)

        selectedPreset = equalizerPresets
            .firstOrNull { it.gains == gains }
            ?.name ?: "Custom"
    }

    fun saveGains(values: List<Float>) {
        gains = values

        PlaybackService.setEqualizerGains(
            values.toFloatArray()
        )

        preferences.edit()
            .putString(
                "gains",
                values.joinToString(",")
            )
            .apply()

        selectedPreset = equalizerPresets
            .firstOrNull { it.gains == values }
            ?.name ?: "Custom"
    }

    fun selectPreset(preset: EqualizerPreset) {
        saveGains(preset.gains)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {

        Text(
            "10-BÅNDS EQUALIZER",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "Tilpas lyden præcis, som du ønsker den.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(
                    modifier = Modifier.weight(1f)
                ) {

                    Text(
                        "Equalizer",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        if (enabled) "Lydbehandling aktiv" else "Lydbehandling slået fra",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->

                        enabled = value

                        PlaybackService.setEqualizerEnabled(value)

                        preferences.edit()
                            .putBoolean("enabled", value)
                            .apply()
                    }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            "FORUDINDSTILLINGER",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(Modifier.height(8.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            equalizerPresets.chunked(3).forEach { rowPresets ->

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {

                    rowPresets.forEach { preset ->

                        FilterChip(
                            selected = selectedPreset == preset.name,
                            onClick = {
                                selectPreset(preset)
                            },
                            label = {
                                Text(preset.name)
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                "FREKVENSBÅND",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )

            Text(
                selectedPreset,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(Modifier.height(12.dp))

        bandLabels.forEachIndexed { index, label ->

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    label,
                    modifier = Modifier.width(62.dp),
                    style = MaterialTheme.typography.bodyMedium
                )

                Slider(
                    value = gains[index],
                    onValueChange = { value ->

                        val updated = gains.toMutableList()
                        updated[index] = value

                        gains = updated

                        PlaybackService.setEqualizerBandGain(
                            index,
                            value
                        )

                        selectedPreset = "Custom"
                    },
                    onValueChangeFinished = {
                        preferences.edit()
                            .putString(
                                "gains",
                                gains.joinToString(",")
                            )
                            .apply()
                    },
                    valueRange = -12f..12f,
                    steps = 23,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    String.format(
                        Locale.US,
                        "%.0f",
                        gains[index]
                    ) + " dB",
                    modifier = Modifier.width(48.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                selectPreset(equalizerPresets.first())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Nulstil til Flat")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Indstillingerne gemmes automatisk på telefonen. " +
                "Equalizeren påvirker lokal afspilning på telefonen, " +
                "ikke lyd, der afspilles direkte på Chromecast.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
