package com.example.localdroid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.localdroid.service.DownloadService
import com.example.localdroid.viewmodel.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialUrl: String? = null,
    vm: DownloadViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { initialUrl?.let(vm::onUrlChange) }

    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* يمكن تنبيه المستخدم هنا عند الرفض */ }

    LaunchedEffect(Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalDroid", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Video URL", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = vm::onUrlChange,
                        label = { Text("Paste YouTube / TikTok / Instagram…") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { vm.onUrlChange(clipboard.getText()?.text.orEmpty()) }) {
                                Icon(Icons.Default.ContentPaste, "Paste")
                            }
                        }
                    )
                    Button(
                        onClick = vm::fetchInfo,
                        enabled = !state.isFetching && state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isFetching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isFetching) "Fetching…" else "Fetch info")
                    }
                }
            }

            state.error?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }

            AnimatedVisibility(visible = state.info != null) {
                state.info?.let { info ->
                    OutlinedCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                info.thumbnailUrl?.let {
                                    AsyncImage(
                                        model = it,
                                        contentDescription = null,
                                        modifier = Modifier.size(width = 120.dp, height = 68.dp)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    info.uploader?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("Duration: ${fmtDuration(info.durationSeconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Text("Select quality", style = MaterialTheme.typography.titleSmall)

                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 240.dp)) {
                                itemsIndexed(info.qualities) { idx, q ->
                                    FilterChip(
                                        selected = idx == state.selectedQuality,
                                        onClick = { vm.onQualitySelect(idx) },
                                        label = { Text(q.label) },
                                        leadingIcon = if (idx == state.selectedQuality)
                                            {{ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }} else null
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    val q = info.qualities[state.selectedQuality]
                                    DownloadService.start(context, state.url, q.formatCode, q.label)
                                    vm.clearError()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start download")
                            }
                        }
                    }
                }
            }

            if (state.info == null && !state.isFetching && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Paste a URL and tap Fetch info to begin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun fmtDuration(sec: Int): String {
    val m = sec / 60; val s = sec % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}
