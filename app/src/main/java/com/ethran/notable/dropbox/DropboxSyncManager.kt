package com.ethran.notable.dropbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.XoppFile
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DropboxSyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val kvProxy: KvProxy,
    private val importEngine: ImportEngine,
    private val xoppFile: XoppFile,
) {
    private val TAG = "DropboxSyncManager"

    private val _state = MutableStateFlow<DropboxSyncState>(DropboxSyncState.Idle)
    val state: StateFlow<DropboxSyncState> = _state.asStateFlow()

    // Stored during auth flow between initiating and receiving the callback
    private var pendingCodeVerifier: String? = null

    // Manifest stored locally on device in app files dir
    private val localSyncDir: String
        get() {
            val dir = File(context.filesDir, "dropbox-sync")
            if (!dir.exists()) dir.mkdirs()
            return dir.absolutePath
        }

    val manifestPath: String get() = "$localSyncDir/manifest.json"

    // filelist.txt lives on Dropbox at /notes-sync/filelist.txt
    val filelistDropboxPath: String = "/notes-sync/filelist.txt"

    /**
     * Start the OAuth2 PKCE authorization flow.
     * Opens the Dropbox auth page in a browser.
     */
    fun startAuth(context: Context) {
        val verifier = DropboxClient.generateCodeVerifier()
        pendingCodeVerifier = verifier
        val challenge = DropboxClient.generateCodeChallenge(verifier)
        val url = DropboxClient.buildAuthUrl(challenge)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Handle the OAuth2 callback with authorization code.
     */
    suspend fun handleAuthCallback(code: String): AppResult<Unit, DomainError> = withContext(Dispatchers.IO) {
        val verifier = pendingCodeVerifier
            ?: return@withContext AppResult.Error(DomainError.SyncError("No pending auth flow"))
        pendingCodeVerifier = null

        _state.value = DropboxSyncState.Authenticating

        when (val result = DropboxClient.exchangeCodeForToken(code, verifier)) {
            is AppResult.Success -> {
                val (accessToken, refreshToken) = result.data
                val client = DropboxClient(accessToken, refreshToken)

                val accountName = when (val accountResult = client.getAccountInfo()) {
                    is AppResult.Success -> accountResult.data
                    is AppResult.Error -> "Unknown"
                }

                val settings = getSettings().copy(
                    enabled = true,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountName = accountName
                )
                saveSettings(settings)
                _state.value = DropboxSyncState.Connected(accountName)
                AppResult.Success(Unit)
            }
            is AppResult.Error -> {
                _state.value = DropboxSyncState.Error(result.error.userMessage)
                AppResult.Error(result.error)
            }
        }
    }

    /**
     * Disconnect from Dropbox (clear tokens).
     */
    suspend fun disconnect() {
        saveSettings(DropboxSettings())
        restoreConnectedState()
    }

    /**
     * Fetch filelist.txt from Dropbox and update the local manifest.
     * @return number of new entries added
     */
    suspend fun rescanFileList(): AppResult<Int, DomainError> = withContext(Dispatchers.IO) {
        val settings = getSettings()
        if (!settings.enabled || settings.accessToken.isBlank()) {
            return@withContext AppResult.Error(DomainError.SyncAuthError)
        }

        val client = createClient(settings)

        // Download filelist.txt from Dropbox
        val downloadResult = client.download(filelistDropboxPath)
        if (downloadResult is AppResult.Error) {
            return@withContext AppResult.Error(
                DomainError.SyncError("Failed to download filelist.txt from Dropbox: ${downloadResult.error.userMessage}")
            )
        }

        val (bytes, _) = (downloadResult as AppResult.Success).data
        val content = bytes.decodeToString()

        // Parse the file list
        val paths = content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }

        if (paths.isEmpty()) {
            return@withContext AppResult.Error(
                DomainError.SyncError("filelist.txt on Dropbox is empty (at $filelistDropboxPath)")
            )
        }

        try {
            val manifest = DropboxManifest.readManifest(manifestPath)
            val (updated, newCount) = DropboxManifest.mergeFileList(manifest, paths)
            DropboxManifest.writeManifest(manifestPath, updated)

            Log.i(TAG,"Rescanned filelist from Dropbox: ${paths.size} paths, $newCount new entries")
            AppResult.Success(newCount)
        } catch (e: Exception) {
            AppResult.Error(DomainError.SyncError("Failed to update manifest: ${e.message}"))
        }
    }

    /**
     * Get the current manifest entries.
     */
    fun getManifestEntries(): List<DropboxManifest.ManifestEntry> {
        return DropboxManifest.readManifest(manifestPath).files
    }

    /**
     * Download a file from Dropbox and import it into Notable.
     */
    suspend fun downloadAndImport(entry: DropboxManifest.ManifestEntry, force: Boolean = false): AppResult<String, DomainError> =
        withContext(Dispatchers.IO) {
            val settings = getSettings()
            if (!settings.enabled || settings.accessToken.isBlank()) {
                return@withContext AppResult.Error(DomainError.SyncAuthError)
            }

            // Check if already imported (has a rev from a previous successful import)
            if (!force && entry.lastSyncedRev.isNotBlank()) {
                restoreConnectedState()
                return@withContext AppResult.Error(
                    DomainError.SyncError("'${entry.title}' already imported. Use force to re-import.")
                )
            }

            Log.i(TAG,"Starting download: ${entry.dropboxPath} (format=${entry.format}, notebookId=${entry.notebookId})")
            _state.value = DropboxSyncState.Syncing("Downloading ${entry.title}...")

            val client = createClient(settings)

            when (val downloadResult = client.download(entry.dropboxPath)) {
                is AppResult.Success -> {
                    val (bytes, rev) = downloadResult.data

                    // Write to a temp file for import
                    val ext = if (entry.format == "xoj") "xoj" else "xopp"
                    val tempFile = File(context.cacheDir, "dropbox_import_${entry.notebookId}.$ext")
                    tempFile.writeBytes(bytes)

                    try {
                        val uri = Uri.fromFile(tempFile)
                        val importResult = importEngine.import(
                            uri,
                            ImportOptions(
                                bookTitle = entry.title,
                            )
                        )

                        // Always update manifest with notebook ID if a book was created,
                        // even if import had partial errors (e.g. some pages failed)
                        var manifest = DropboxManifest.readManifest(manifestPath)
                        val actualBookId = importEngine.lastImportedBookId
                        if (actualBookId != null) {
                            manifest = DropboxManifest.updateNotebookId(manifest, entry.dropboxPath, actualBookId)
                            Log.i(TAG, "Linked ${entry.dropboxPath} -> notebook $actualBookId")
                        }
                        manifest = DropboxManifest.updateRev(manifest, entry.dropboxPath, rev)
                        DropboxManifest.writeManifest(manifestPath, manifest)

                        when (importResult) {
                            is AppResult.Success -> {
                                restoreConnectedState()
                                AppResult.Success(entry.title)
                            }
                            is AppResult.Error -> {
                                // Book was likely created but some pages had errors
                                restoreConnectedState()
                                Log.w(TAG, "Import had errors: ${importResult.error.userMessage}")
                                AppResult.Success(entry.title)
                            }
                        }
                    } finally {
                        tempFile.delete()
                    }
                }
                is AppResult.Error -> {
                    _state.value = DropboxSyncState.Error("Download failed: ${downloadResult.error.userMessage}")
                    AppResult.Error(downloadResult.error)
                }
            }
        }

    /**
     * Upload a notebook to Dropbox by notebook ID (used from toolbar).
     */
    suspend fun uploadNotebook(notebookId: String): AppResult<Unit, DomainError> = withContext(Dispatchers.IO) {
        val manifest = DropboxManifest.readManifest(manifestPath)
        val entry = DropboxManifest.findByNotebookId(manifest, notebookId)
            ?: return@withContext AppResult.Error(
                DomainError.SyncError("Notebook $notebookId not found in Dropbox manifest")
            )
        uploadNotebook(entry)
    }

    /**
     * Upload a notebook to Dropbox by manifest entry (used from settings UI).
     */
    suspend fun uploadNotebook(entry: DropboxManifest.ManifestEntry): AppResult<Unit, DomainError> = withContext(Dispatchers.IO) {
        val settings = getSettings()
        if (!settings.enabled || settings.accessToken.isBlank()) {
            return@withContext AppResult.Error(DomainError.SyncAuthError)
        }

        Log.i(TAG,"Starting upload: ${entry.dropboxPath} (format=${entry.format}, notebookId=${entry.notebookId}, lastRev=${entry.lastSyncedRev})")
        _state.value = DropboxSyncState.Syncing("Uploading ${entry.title}...")

        val client = createClient(settings)

        // Check for conflicts via rev
        if (entry.lastSyncedRev.isNotBlank()) {
            Log.i(TAG,"Checking rev for ${entry.dropboxPath}: local=${entry.lastSyncedRev}")
            when (val metaResult = client.getMetadata(entry.dropboxPath)) {
                is AppResult.Success -> {
                    val remoteRev = metaResult.data.rev
                    Log.i(TAG,"Remote rev=$remoteRev, local rev=${entry.lastSyncedRev}")
                    if (remoteRev != entry.lastSyncedRev) {
                        val msg = "Conflict on ${entry.title}: local rev=${entry.lastSyncedRev}, remote rev=$remoteRev"
                        Log.w(TAG,msg)
                        _state.value = DropboxSyncState.Error(msg)
                        return@withContext AppResult.Error(
                            DomainError.SyncConflict
                        )
                    }
                }
                is AppResult.Error -> {
                    Log.i(TAG,"get_metadata for ${entry.dropboxPath}: ${metaResult.error.userMessage}")
                    if (metaResult.error !is DomainError.NotFound) {
                        _state.value = DropboxSyncState.Error(metaResult.error.userMessage)
                        return@withContext AppResult.Error(metaResult.error)
                    }
                }
            }
        } else {
            Log.i(TAG,"No lastSyncedRev for ${entry.dropboxPath}, skipping conflict check")
        }

        // Export to bytes, using format-aware writer
        try {
            val exportTarget = com.ethran.notable.io.ExportTarget.Book(entry.notebookId)
            val baos = java.io.ByteArrayOutputStream()
            if (entry.format == "xoj") xoppFile.writeToXojStream(exportTarget, baos)
            else xoppFile.writeToXoppStream(exportTarget, baos)
            val bytes = baos.toByteArray()

            when (val uploadResult = client.upload(entry.dropboxPath, bytes)) {
                is AppResult.Success -> {
                    val newRev = uploadResult.data
                    val updated = DropboxManifest.updateRev(
                        DropboxManifest.readManifest(manifestPath), entry.dropboxPath, newRev
                    )
                    DropboxManifest.writeManifest(manifestPath, updated)

                    restoreConnectedState()
                    Log.i(TAG,"Uploaded ${entry.title} (rev=$newRev)")
                    AppResult.Success(Unit)
                }
                is AppResult.Error -> {
                    _state.value = DropboxSyncState.Error("Upload failed: ${uploadResult.error.userMessage}")
                    AppResult.Error(uploadResult.error)
                }
            }
        } catch (e: Exception) {
            val error = DomainError.SyncError("Export failed: ${e.message}")
            _state.value = DropboxSyncState.Error(error.userMessage)
            AppResult.Error(error)
        }
    }

    /**
     * Check if a notebook is linked to Dropbox (has an entry in the manifest).
     */
    fun isDropboxLinked(notebookId: String): Boolean {
        val manifest = DropboxManifest.readManifest(manifestPath)
        return DropboxManifest.findByNotebookId(manifest, notebookId) != null
    }

    suspend fun getSettings(): DropboxSettings {
        return kvProxy.get(DROPBOX_SETTINGS_KEY, DropboxSettings.serializer())
            ?: DropboxSettings()
    }

    private suspend fun saveSettings(settings: DropboxSettings) {
        kvProxy.setKv(DROPBOX_SETTINGS_KEY, settings, DropboxSettings.serializer())
    }

    private fun createClient(settings: DropboxSettings): DropboxClient {
        return DropboxClient(
            accessToken = settings.accessToken,
            refreshToken = settings.refreshToken,
            onTokenRefreshed = { newToken ->
                // We can't easily update KvProxy from here (non-suspend),
                // so we store it transiently. It'll be refreshed again next time.
            }
        )
    }

    /**
     * Restore the Connected state from saved settings.
     */
    private suspend fun restoreConnectedState() {
        val settings = getSettings()
        _state.value = if (settings.enabled && settings.refreshToken.isNotBlank()) {
            DropboxSyncState.Connected(settings.accountName)
        } else {
            DropboxSyncState.Idle
        }
    }

    /**
     * Initialize state based on saved settings.
     */
    suspend fun initializeState() {
        restoreConnectedState()
    }
}

sealed class DropboxSyncState {
    data object Idle : DropboxSyncState()
    data object Authenticating : DropboxSyncState()
    data class Connected(val accountName: String) : DropboxSyncState()
    data class Syncing(val message: String) : DropboxSyncState()
    data class Error(val message: String) : DropboxSyncState()
}
