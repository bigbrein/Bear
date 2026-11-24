package com.bigbrein.bear

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private val standardHeights = listOf(2160, 1440, 1080, 720, 480, 360)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BearScreen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Bear") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            s.engineError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            OutlinedTextField(
                value = s.url,
                onValueChange = vm::setUrl,
                label = { Text("Video, audio or playlist link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (s.url.isEmpty()) {
                        TextButton(onClick = { clipboard.getText()?.text?.let(vm::receiveSharedText) }) {
                            Text("Paste")
                        }
                    } else {
                        TextButton(onClick = { vm.setUrl("") }) { Text("Clear") }
                    }
                },
            )

            if (s.url.isNotBlank()) PreviewCard(s)

            OptionsSection(s.options, s.info, vm::setOptions)

            Button(
                onClick = vm::download,
                enabled = s.engineReady && s.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (s.engineReady) "Download" else "Starting engine…")
            }

            if (tasks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (tasks.any { it.state.isFinished }) {
                        TextButton(onClick = vm::clearFinished) { Text("Clear finished") }
                    }
                }
                tasks.asReversed().forEach { task ->
                    TaskRow(task, onCancel = { vm.cancel(task.id) }, onRetry = { vm.retry(task.id) },
                        onRemove = { vm.remove(task.id) })
                }
            }

            BatteryHint()

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp ${s.ytDlpVersion.ifBlank { "…" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Files are saved to Download/Bear", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = vm::updateYtDlp, enabled = s.engineReady && !s.updating) {
                    Text(if (s.updating) "Updating…" else "Update yt-dlp")
                }
            }
        }
    }
}

/**
 * Xiaomi/HyperOS and other aggressive battery savers can kill background downloads.
 * Offer the system "don't optimize" dialog until the user allows it.
 */
@Composable
private fun BatteryHint() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Keep downloads running in the background", style = MaterialTheme.typography.titleSmall)
            Text("Your phone's battery saver may stop downloads when Bear is closed.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }.onFailure {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }) { Text("Allow") }
        }
    }
}

@Composable
private fun PreviewCard(s: UiState) {
    Card(Modifier.fillMaxWidth()) {
        when {
            s.info != null -> Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumbnail(s.info.thumbnail, Modifier.width(112.dp).height(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.info.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val details = buildList {
                        if (s.info.isPlaylist) add("Playlist · ${s.info.entryCount} items")
                        s.info.uploader?.let { add(it) }
                        s.info.durationSeconds?.takeIf { it > 0 }?.let { add(formatDuration(it.toLong())) }
                        s.info.site?.let { add(it) }
                    }
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            s.fetching -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking up link…", style = MaterialTheme.typography.bodyMedium)
            }
            s.fetchError != null -> Text(
                "Couldn't read this link: ${s.fetchError}\nYou can still try downloading it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
            else -> Text("Waiting for a link…", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OptionsSection(
    o: DownloadOptions,
    info: MediaInfo?,
    onChange: ((DownloadOptions) -> DownloadOptions) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Mode.entries.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = o.mode == mode,
                onClick = { onChange { it.copy(mode = mode) } },
                shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
            ) { Text(if (mode == Mode.VIDEO) "Video" else "Audio") }
        }
    }

    when (o.mode) {
        Mode.VIDEO -> {
            // Only offer resolutions the link actually has (when we know them).
            val top = info?.heights?.firstOrNull()
            val heights = if (top == null) standardHeights else standardHeights.filter { it <= top }
            Text("Quality", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = o.maxHeight == null, onClick = { onChange { it.copy(maxHeight = null) } },
                    label = { Text("Best") })
                heights.forEach { h ->
                    FilterChip(selected = o.maxHeight == h, onClick = { onChange { it.copy(maxHeight = h) } },
                        label = { Text(if (h == 2160) "4K" else "${h}p") })
                }
            }
        }
        Mode.AUDIO -> {
            Text("Format", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioFormat.entries.forEach { f ->
                    FilterChip(selected = o.audioFormat == f, onClick = { onChange { it.copy(audioFormat = f) } },
                        label = { Text(f.label) })
                }
            }
            Text("Quality", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioQuality.entries.forEach { q ->
                    FilterChip(selected = o.audioQuality == q, onClick = { onChange { it.copy(audioQuality = q) } },
                        label = { Text(q.label) })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier) {
    val shaped = modifier.clip(RoundedCornerShape(8.dp))
    if (url == null) {
        Box(shaped.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = shaped.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun TaskRow(task: DownloadTask, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumbnail(task.thumbnail, Modifier.width(80.dp).height(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(statusText(task), style = MaterialTheme.typography.bodySmall,
                        color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            when (task.state) {
                TaskState.DOWNLOADING -> if (task.progress > 0f) {
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TaskState.SAVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (task.state) {
                    TaskState.QUEUED, TaskState.DOWNLOADING -> TextButton(onClick = onCancel) { Text("Cancel") }
                    TaskState.FAILED, TaskState.CANCELLED -> {
                        TextButton(onClick = onRemove) { Text("Remove") }
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    TaskState.DONE -> TextButton(onClick = onRemove) { Text("Remove") }
                    TaskState.SAVING -> Unit
                }
            }
        }
    }
}

private fun statusText(t: DownloadTask): String {
    val opts = t.options.describe()
    return when (t.state) {
        TaskState.QUEUED -> "Queued · $opts"
        TaskState.DOWNLOADING -> buildList {
            t.playlistItem?.let { add("Item $it") }
            if (t.progress > 0f) add("${(t.progress * 100).toInt()}%") else add("Starting…")
            if (t.etaSeconds > 0) add("${formatDuration(t.etaSeconds)} left")
        }.joinToString(" · ")
        TaskState.SAVING -> "Saving…"
        TaskState.DONE -> t.message ?: "Done"
        TaskState.FAILED -> "Failed: ${t.message ?: "unknown error"}"
        TaskState.CANCELLED -> "Cancelled"
    }
}
