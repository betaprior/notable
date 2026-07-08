package com.ethran.notable.ui.views

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog
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
    var scanFolder by remember { mutableStateOf("/notes-sync") }
    var detailEntry by remember { mutableStateOf<DropboxManifest.ManifestEntry?>(null) }

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

            EInkTextField(
                label = "Scan folder (Dropbox path)",
                value = scanFolder,
                onValueChange = { scanFolder = it },
                placeholder = "/notes-sync"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // File list section
        EInkSection(
            title = "Managed Files (${manifestEntries.size})",
            icon = Icons.Default.List
        ) {
            EInkActionButton(
                text = "Scan Dropbox folder",
                onClick = {
                    scope.launch {
                        val result = syncManager.scanFolder(scanFolder.trim().ifBlank { "/" })
                        when (result) {
                            is AppResult.Success -> {
                                statusMessage = "Catalogued ${result.data} file(s)"
                                withContext(Dispatchers.IO) {
                                    manifestEntries = syncManager.getManifestEntries()
                                }
                            }
                            is AppResult.Error -> {
                                statusMessage = "Scan failed: ${result.error.userMessage}"
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
                    text = "No files catalogued. Set a scan folder above and Scan Dropbox folder.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            } else {
                manifestEntries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Tap the entry to open a large-text details modal -- the
                        // inline path is small and unreadable on e-ink.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { detailEntry = entry }
                        ) {
                            Text(
                                text = entry.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colors.onSurface
                            )
                            // filename + format, readable; full path is in the modal
                            Text(
                                text = "${entry.dropboxPath.substringAfterLast('/')} · ${entry.format}" +
                                    if (entry.lastSyncedRev.isNotBlank()) " · imported" else "",
                                fontSize = 15.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "tap for details",
                                fontSize = 12.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f)
                            )
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
                                val result = syncManager.uploadByPath(entry.dropboxPath)
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

    // Large-text details modal for a managed file (readable on e-ink).
    detailEntry?.let { entry ->
        Dialog(onDismissRequest = { detailEntry = null }) {
            androidx.compose.material.Surface(
                color = MaterialTheme.colors.surface,
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colors.onSurface),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                        color = MaterialTheme.colors.onSurface)
                    Spacer(Modifier.height(16.dp))
                    DetailRow("Dropbox path", entry.dropboxPath)
                    DetailRow("Format", entry.format)
                    DetailRow("Imported rev",
                        entry.lastSyncedRev.ifBlank { "not imported yet" })
                    Spacer(Modifier.height(20.dp))
                    EInkActionButton(
                        text = "Remove from list",
                        onClick = {
                            val path = entry.dropboxPath
                            detailEntry = null
                            scope.launch {
                                syncManager.removeFromCatalog(path)
                                withContext(Dispatchers.IO) {
                                    manifestEntries = syncManager.getManifestEntries()
                                }
                                statusMessage = "Removed $path from the catalog"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        isSecondary = true,
                    )
                    Text(
                        text = "Only removes it from this list — the Dropbox file and any imported notebook are untouched.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                    )
                    EInkActionButton(
                        text = "Close",
                        onClick = { detailEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                        isBold = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(label, fontSize = 14.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface)
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
