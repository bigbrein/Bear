package com.bigbrein.bear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { BearTheme { BearScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::receiveSharedText)
        }
    }
}

@Composable
fun BearTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BearScreen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(topBar = { TopAppBar(title = { Text("Bear") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = s.url,
                onValueChange = vm::setUrl,
                label = { Text("Video, audio or playlist link") },
                singleLine = true,
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(enabled = !s.busy, onClick = {
                        clipboard.getText()?.text?.let(vm::receiveSharedText)
                    }) { Text("Paste") }
                },
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Mode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.mode == mode,
                        onClick = { vm.setMode(mode) },
                        enabled = !s.busy,
                        shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                    ) { Text(if (mode == Mode.VIDEO) "Video (mp4)" else "Audio (mp3)") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::download,
                    enabled = s.engineReady && !s.busy && s.url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Download") }
                if (s.busy) {
                    OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.status, style = MaterialTheme.typography.titleSmall)
                    if (s.busy) {
                        if (s.progress > 0f) {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    if (s.lastLine.isNotBlank() && s.busy) {
                        Text(
                            s.lastLine,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    s.saved.forEach {
                        Text("✓ $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "yt-dlp ${s.ytDlpVersion.ifBlank { "…" }}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                OutlinedButton(onClick = vm::updateYtDlp, enabled = s.engineReady && !s.updating && !s.busy) {
                    Text(if (s.updating) "Updating…" else "Update yt-dlp")
                }
            }
            Text(
                "Files are saved to Download/Bear.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
