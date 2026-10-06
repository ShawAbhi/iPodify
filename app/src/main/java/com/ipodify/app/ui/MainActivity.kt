package com.ipodify.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ipodify.app.PodSettings
import com.ipodify.app.media.MediaListenerService
import com.ipodify.app.media.NowPlaying
import com.ipodify.app.pod.FloatingPodService

/**
 * Setup and settings: the two permissions iPodify needs, a live check that it
 * can see what is playing, the button that opens the iPod, and the lock
 * screen switch.
 */
class MainActivity : ComponentActivity() {

    /** Bumped on every resume, so the permission checks are re-read on return from Settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IPodifyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SetupScreen(resumes = resumes.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NowPlaying.ensureConnected(this)
        resumes.intValue++
    }
}

@Composable
private fun SetupScreen(resumes: Int) {
    val context = LocalContext.current
    // Re-read on each resume: both are granted in the system's Settings.
    val musicAccess = remember(resumes) { NowPlaying.hasAccess(context) }
    val overlay = remember(resumes) { Settings.canDrawOverlays(context) }
    val player by NowPlaying.state.collectAsState()
    val podOpen by FloatingPodService.isRunning
    val lockScreen by PodSettings.lockScreen.collectAsState()
    val autoShow by PodSettings.autoShow.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("iPodify", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "A click-wheel iPod that floats over any music app — Spotify, YouTube Music, " +
                "Apple Music, SoundCloud and the rest — and controls whatever is playing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Step(
            number = 1,
            title = "Music access",
            body = "Lets iPodify see what is playing and control it, through Android's notification " +
                "access. iPodify doesn't read your notifications. If Android greys this out as a " +
                "restricted setting, open iPodify's App info, tap ⋮ and choose \"Allow restricted settings\".",
            done = musicAccess,
            action = "Allow",
            onAction = { openNotificationAccess(context) },
        )
        Step(
            number = 2,
            title = "Display over other apps",
            body = "Lets the iPod float over whatever app you're in.",
            done = overlay,
            action = "Allow",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Now playing", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                val song = player.song
                Text(
                    text = when {
                        !musicAccess -> "Allow music access to see what's playing."
                        song == null -> "Nothing playing. Start music in any app."
                        else -> song.title.ifBlank { "Unknown track" }
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (song != null && musicAccess) {
                    Text(
                        listOfNotNull(song.artist.takeIf { it.isNotBlank() }, player.appName).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (player.queue.size > 1) "${player.queue.size} tracks in the queue" else "No queue shared by this app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (podOpen) {
            OutlinedButton(
                onClick = { FloatingPodService.stop(context) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Close iPod") }
        } else {
            Button(
                onClick = { FloatingPodService.start(context) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Open iPod") }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show when music plays", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (autoShow) {
                            "The bubble appears by itself, tucked into the edge where you last left it, " +
                                "whenever music starts in any app."
                        } else {
                            "Open the iPod from here when you want it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = autoShow, onCheckedChange = PodSettings::setAutoShow)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("iPod lock screen", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (lockScreen) {
                            "While the iPod is open, locking the phone shows it full screen. Swipe up to unlock."
                        } else {
                            "The system lock screen is used."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = lockScreen, onCheckedChange = PodSettings::setLockScreen)
            }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    body: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number. $title",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    Text("✓ Allowed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                } else {
                    FilledTonalButton(onClick = onAction) { Text(action) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Straight to iPodify's own switch where the phone supports it, else the list. */
private fun openNotificationAccess(context: Context) {
    val component = ComponentName(context, MediaListenerService::class.java)
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else {
        null
    }
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (intent in listOfNotNull(detail, list)) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}
