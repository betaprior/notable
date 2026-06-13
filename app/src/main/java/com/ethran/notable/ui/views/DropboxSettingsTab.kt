package com.ethran.notable.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.dropbox.DropboxManifest
import com.ethran.notable.dropbox.DropboxSyncManager
import com.ethran.notable.dropbox.DropboxSyncState
import com.ethran.notable.utils.AppResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface DropboxSettingsEntryPoint {
    fun dropboxSyncManager(): DropboxSyncManager
}

@Composable
fun DropboxSettingsTab() {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context, DropboxSettingsEntryPoint::class.java)
    }
    val syncManager = remember { entryPoint.dropboxSyncManager() }
    val syncState by syncManager.state.collectAsState()
    // Use a stable scope that won't be cancelled on recomposition
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }

    var manifestEntries by remember { mutableStateOf<List<DropboxManifest.ManifestEntry>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var authCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        syncManager.initializeState()
        withContext(Dispatchers.IO) {
            manifestEntries = syncManager.getManifestEntries()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Dropbox Sync",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Connection section
        EInkSection(
            title = when (syncState) {
                is DropboxSyncState.Connected -> "Connected: ${(syncState as DropboxSyncState.Connected).accountName}"
                is DropboxSyncState.Authenticating -> "Authenticating..."
                is DropboxSyncState.Syncing -> (syncState as DropboxSyncState.Syncing).message
                is DropboxSyncState.Error -> "Error: ${(syncState as DropboxSyncState.Error).message}"
                DropboxSyncState.Idle -> "Not connected"
            },
            icon = Icons.Default.Cloud
        ) {
            if (syncState is DropboxSyncState.Connected) {
                EInkActionButton(
                    text = "Disconnect",
                    onClick = {
                        scope.launch {
                            syncManager.disconnect()
                            statusMessage = "Disconnected from Dropbox"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isSecondary = true
                )
            } else {
                // Step 1: Open browser for auth
                EInkActionButton(
                    text = "1. Open Dropbox Auth in Browser",
                    onClick = { syncManager.startAuth(context) },
                    modifier = Modifier.fillMaxWidth(),
                    isBold = true,
                    enabled = syncState !is DropboxSyncState.Authenticating
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Step 2: Paste the code
                EInkTextField(
                    label = "2. Paste authorization code here",
                    value = authCode,
                    onValueChange = { authCode = it },
                    placeholder = "Paste code from Dropbox..."
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Step 3: Submit
                EInkActionButton(
                    text = "3. Connect",
                    onClick = {
                        scope.launch {
                            val result = syncManager.handleAuthCallback(authCode.trim())
                            statusMessage = when (result) {
                                is AppResult.Success -> {
                                    authCode = ""
                                    "Connected to Dropbox!"
                                }
                                is AppResult.Error -> "Auth failed: ${result.error.userMessage}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isBold = true,
                    enabled = authCode.isNotBlank() && syncState !is DropboxSyncState.Authenticating
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Filelist: Dropbox:${syncManager.filelistDropboxPath}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // File list section
        EInkSection(
            title = "Managed Files (${manifestEntries.size})",
            icon = Icons.Default.List
        ) {
            EInkActionButton(
                text = "Re-scan filelist.txt",
                onClick = {
                    scope.launch {
                        val result = syncManager.rescanFileList()
                        when (result) {
                            is AppResult.Success -> {
                                statusMessage = "Rescanned: ${result.data} new entries"
                                withContext(Dispatchers.IO) {
                                    manifestEntries = syncManager.getManifestEntries()
                                }
                            }
                            is AppResult.Error -> {
                                statusMessage = "Rescan failed: ${result.error.userMessage}"
                            }
                        }
                    }
                },
                isSecondary = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (manifestEntries.isEmpty()) {
                Text(
                    text = "No files in manifest. Add Dropbox paths to filelist.txt and re-scan.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            } else {
                manifestEntries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.body2,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colors.onSurface
                            )
                            Text(
                                text = "${entry.dropboxPath} [${entry.format}]",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                            if (entry.lastSyncedRev.isNotBlank()) {
                                Text(
                                    text = "rev: ${entry.lastSyncedRev.take(8)}...",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                                    fontSize = 9.sp
                                )
                            }
                        }

                        if (syncState is DropboxSyncState.Connected) {
                            val alreadyImported = entry.lastSyncedRev.isNotBlank()
                            EInkActionButton(
                                text = if (alreadyImported) "Re-import" else "Import",
                                onClick = {
                                    scope.launch {
                                        val result = syncManager.downloadAndImport(entry, force = alreadyImported)
                                        statusMessage = when (result) {
                                            is AppResult.Success -> {
                                                withContext(Dispatchers.IO) {
                                                    manifestEntries = syncManager.getManifestEntries()
                                                }
                                                "Imported: ${result.data}"
                                            }
                                            is AppResult.Error -> result.error.userMessage
                                        }
                                    }
                                },
                                isSecondary = alreadyImported,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // Sync actions (only when connected and have imported files)
        if (syncState is DropboxSyncState.Connected && manifestEntries.any { it.lastSyncedRev.isNotBlank() }) {
            Spacer(modifier = Modifier.height(16.dp))

            EInkSection(
                title = "Sync Actions",
                icon = Icons.Default.Cloud
            ) {
                EInkActionButton(
                    text = "Upload All to Dropbox",
                    onClick = {
                        scope.launch {
                            val imported = manifestEntries.filter { it.lastSyncedRev.isNotBlank() }
                            var successCount = 0
                            var failCount = 0
                            for (entry in imported) {
                                val result = syncManager.uploadNotebook(entry)
                                when (result) {
                                    is AppResult.Success -> successCount++
                                    is AppResult.Error -> failCount++
                                }
                            }
                            withContext(Dispatchers.IO) {
                                manifestEntries = syncManager.getManifestEntries()
                            }
                            statusMessage = "Uploaded $successCount, failed $failCount"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isBold = true,
                    enabled = syncState !is DropboxSyncState.Syncing
                )
            }
        }

        // Status message
        statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun EInkActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSecondary: Boolean = false,
    isBold: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    androidx.compose.material.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = eInkButtonColors(isSecondary = isSecondary)
    ) {
        Text(
            text = text,
            fontWeight = if (isBold) FontWeight.Bold else null,
            fontSize = fontSize
        )
    }
}
