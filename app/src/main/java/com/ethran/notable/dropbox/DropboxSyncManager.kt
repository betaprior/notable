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
import kotlinx.coroutines.launch
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
    private val inkStreamClient: com.ethran.notable.ink.InkStreamClient,
    private val bookRepo: com.ethran.notable.data.db.BookRepository,
    @param:com.ethran.notable.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
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

    // Auto-linked notebooks (created by "start streaming") get paths here
    val autoLinkFolder: String = "/notes-sync"

    init {
        // one-time bridge from the old manifest-notebookId links to the
        // notebook-carried linkedExternalUri identity
        appScope.launch { migrateLegacyLinks() }
    }

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
     * Catalog the Dropbox folder [root] (recursively): find every .xoj/.xopp
     * and upsert it into the manifest (path + format + rev). This is the
     * list_folder replacement for filelist.txt -- the catalog is "what's
     * actually in Dropbox", and each file's rev seeds the rev cache.
     * @return number of files catalogued.
     */
    suspend fun scanFolder(root: String): AppResult<Int, DomainError> = withContext(Dispatchers.IO) {
        val settings = getSettings()
        if (!settings.enabled || settings.accessToken.isBlank()) {
            return@withContext AppResult.Error(DomainError.SyncAuthError)
        }
        val client = createClient(settings)
        when (val res = client.listFolder(root, recursive = true)) {
            is AppResult.Error -> AppResult.Error(res.error)
            is AppResult.Success -> {
                val notes = res.data.filter {
                    it.path_display.endsWith(".xoj", true) || it.path_display.endsWith(".xopp", true)
                }
                var manifest = DropboxManifest.readManifest(manifestPath)
                for (f in notes) {
                    val format = if (f.path_display.endsWith(".xopp", true)) "xopp" else "xoj"
                    manifest = DropboxManifest.upsertCatalog(manifest, f.path_display, format)
                }
                DropboxManifest.writeManifest(manifestPath, manifest)
                Log.i(TAG, "Scanned $root: ${notes.size} note files catalogued")
                AppResult.Success(notes.size)
            }
        }
    }

    /**
     * Get the current manifest entries.
     */
    fun getManifestEntries(): List<DropboxManifest.ManifestEntry> {
        return DropboxManifest.readManifest(manifestPath).files
    }

    /**
     * Remove a path from the local catalog (manifest). Does NOT delete the
     * Dropbox file or any imported notebook -- it only stops listing the path.
     * A later folder scan will re-add it if it still exists in Dropbox.
     */
    suspend fun removeFromCatalog(path: String) = withContext(Dispatchers.IO) {
        DropboxManifest.writeManifest(
            manifestPath,
            DropboxManifest.removeByPath(DropboxManifest.readManifest(manifestPath), path)
        )
        Log.i(TAG, "Removed from catalog: $path")
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

            // Identity is the Dropbox path: if a notebook is already linked to
            // it, this path was imported before. Re-import (force) replaces it
            // in place (delete cascades pages/strokes) instead of duplicating.
            val linkUri = DropboxLink.uriFor(entry.dropboxPath)
            val existing = bookRepo.getByLinkedUri(linkUri)
            if (existing != null) {
                if (!force) {
                    restoreConnectedState()
                    return@withContext AppResult.Error(
                        DomainError.SyncError("'${entry.title}' already imported. Use force to re-import.")
                    )
                }
                Log.i(TAG, "Re-importing $linkUri: replacing notebook ${existing.id}")
                bookRepo.delete(existing.id)
            }

            Log.i(TAG,"Starting download: ${entry.dropboxPath} (format=${entry.format})")
            _state.value = DropboxSyncState.Syncing("Downloading ${entry.title}...")

            val client = createClient(settings)

            when (val downloadResult = client.download(entry.dropboxPath)) {
                is AppResult.Success -> {
                    val (bytes, rev) = downloadResult.data

                    // Write to a temp file for import
                    val ext = if (entry.format == "xoj") "xoj" else "xopp"
                    val tempFile = File(context.cacheDir, "dropbox_import_${entry.dropboxPath.hashCode()}.$ext")
                    tempFile.writeBytes(bytes)

                    try {
                        val uri = Uri.fromFile(tempFile)
                        val importResult = importEngine.import(
                            uri,
                            ImportOptions(
                                bookTitle = entry.title,
                            )
                        )

                        // Stamp the Dropbox path onto the created notebook (its
                        // identity), and cache the rev by path. Done even on
                        // partial import errors so the (created) book is linked.
                        val actualBookId = importEngine.lastImportedBookId
                        if (actualBookId != null) {
                            bookRepo.update(
                                bookRepo.getById(actualBookId)!!.copy(linkedExternalUri = linkUri)
                            )
                            Log.i(TAG, "Linked ${entry.dropboxPath} -> notebook $actualBookId")
                        }
                        DropboxManifest.writeManifest(
                            manifestPath,
                            DropboxManifest.upsertRev(
                                DropboxManifest.readManifest(manifestPath),
                                entry.dropboxPath, entry.format, rev
                            )
                        )

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
     * The Dropbox path a notebook is linked to (from its linkedExternalUri
     * "dropbox://<path>" identity), or null if it isn't Dropbox-linked.
     */
    suspend fun linkedPath(notebookId: String): String? =
        DropboxLink.pathOf(bookRepo.getById(notebookId)?.linkedExternalUri)

    /**
     * Bridge legacy links (manifest notebookId -> path, from before identity
     * moved onto the notebook) into linkedExternalUri. Only migrates entries
     * that were genuinely synced (non-blank rev), so failed-upload orphans
     * don't link a notebook to a phantom remote file. Idempotent: skips any
     * notebook that already has a linkedExternalUri.
     */
    suspend fun migrateLegacyLinks() = withContext(Dispatchers.IO) {
        val manifest = DropboxManifest.readManifest(manifestPath)
        for (entry in manifest.files) {
            if (entry.notebookId.isBlank() || entry.lastSyncedRev.isBlank()) continue
            val nb = bookRepo.getById(entry.notebookId) ?: continue
            if (nb.linkedExternalUri != null) continue
            bookRepo.update(nb.copy(linkedExternalUri = DropboxLink.uriFor(entry.dropboxPath)))
            Log.i(TAG, "Migrated legacy link: ${entry.notebookId} -> ${entry.dropboxPath}")
        }
    }

    suspend fun isDropboxLinked(notebookId: String): Boolean = linkedPath(notebookId) != null

    /**
     * Upload a notebook to Dropbox using the path stored on the notebook.
     * Fails if the notebook isn't Dropbox-linked (see [linkAndUpload]).
     */
    suspend fun uploadNotebook(notebookId: String): AppResult<Unit, DomainError> = withContext(Dispatchers.IO) {
        val path = linkedPath(notebookId)
            ?: return@withContext AppResult.Error(
                DomainError.SyncError("Notebook is not linked to a Dropbox file")
            )
        doUpload(notebookId, path, formatForPath(path))
    }

    /** Upload the notebook currently linked to [path] (used by the settings catalog). */
    suspend fun uploadByPath(path: String): AppResult<Unit, DomainError> = withContext(Dispatchers.IO) {
        val notebook = bookRepo.getByLinkedUri(DropboxLink.uriFor(path))
            ?: return@withContext AppResult.Error(
                DomainError.SyncError("No local notebook linked to $path")
            )
        doUpload(notebook.id, path, formatForPath(path))
    }

    /**
     * Assign a Dropbox path for a not-yet-linked notebook, upload it, and only
     * on success stamp the link (linkedExternalUri + manifest rev). A failed
     * upload leaves the notebook unlinked so the next attempt retries cleanly
     * -- no orphaned link, no phantom remote file.
     */
    suspend fun linkAndUpload(notebookId: String, title: String): AppResult<String, DomainError> =
        withContext(Dispatchers.IO) {
            val path = assignPath(title)
            when (val r = doUpload(notebookId, path, "xoj")) {
                is AppResult.Success -> {
                    bookRepo.update(
                        bookRepo.getById(notebookId)!!.copy(
                            linkedExternalUri = DropboxLink.uriFor(path)
                        )
                    )
                    Log.i(TAG, "Linked notebook $notebookId -> $path (after successful upload)")
                    AppResult.Success(path)
                }
                is AppResult.Error -> AppResult.Error(r.error)
            }
        }

    /** Pick a fresh Dropbox path under [autoLinkFolder] from [title]. */
    private fun assignPath(title: String): String {
        val manifest = DropboxManifest.readManifest(manifestPath)
        val base = title.lowercase()
            .removeSuffix(".xoj").removeSuffix(".xopp")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "untitled" }
        var path = "$autoLinkFolder/$base.xoj"
        var n = 2
        while (DropboxManifest.findByPath(manifest, path) != null) {
            path = "$autoLinkFolder/$base-$n.xoj"
            n++
        }
        return path
    }

    private fun formatForPath(path: String): String =
        if (path.endsWith(".xopp", ignoreCase = true)) "xopp" else "xoj"

    /**
     * Core upload: export [notebookId], push to [path], update the rev cache,
     * and fire a SYNC_MARKER so a streaming receiver knows those strokes are
     * committed. Does not touch linkedExternalUri (callers own linkage).
     */
    private suspend fun doUpload(notebookId: String, path: String, format: String): AppResult<Unit, DomainError> {
        val settings = getSettings()
        if (!settings.enabled || settings.accessToken.isBlank()) {
            return AppResult.Error(DomainError.SyncAuthError)
        }

        _state.value = DropboxSyncState.Syncing("Uploading ${path.substringAfterLast('/')}...")
        val client = createClient(settings)

        // Conflict check against the cached rev for this path
        val knownRev = DropboxManifest.findByPath(DropboxManifest.readManifest(manifestPath), path)
            ?.lastSyncedRev.orEmpty()
        if (knownRev.isNotBlank()) {
            when (val metaResult = client.getMetadata(path)) {
                is AppResult.Success -> {
                    if (metaResult.data.rev != knownRev) {
                        val msg = "Conflict on $path: local rev=$knownRev, remote rev=${metaResult.data.rev}"
                        Log.w(TAG, msg)
                        _state.value = DropboxSyncState.Error(msg)
                        return AppResult.Error(DomainError.SyncConflict)
                    }
                }
                is AppResult.Error -> {
                    if (metaResult.error !is DomainError.NotFound) {
                        _state.value = DropboxSyncState.Error(metaResult.error.userMessage)
                        return AppResult.Error(metaResult.error)
                    }
                }
            }
        }

        return try {
            val exportTarget = com.ethran.notable.io.ExportTarget.Book(notebookId)
            val baos = java.io.ByteArrayOutputStream()
            val exportedStrokeIds = mutableListOf<String>()
            if (format == "xoj") xoppFile.writeToXojStream(exportTarget, baos, exportedStrokeIds)
            else xoppFile.writeToXoppStream(exportTarget, baos, exportedStrokeIds)
            val bytes = baos.toByteArray()

            when (val uploadResult = client.upload(path, bytes)) {
                is AppResult.Success -> {
                    val newRev = uploadResult.data
                    DropboxManifest.writeManifest(
                        manifestPath,
                        DropboxManifest.upsertRev(
                            DropboxManifest.readManifest(manifestPath), path, format, newRev
                        )
                    )
                    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    inkStreamClient.syncMarker(sha, exportedStrokeIds)
                    restoreConnectedState()
                    Log.i(TAG, "Uploaded $path (rev=$newRev)")
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
                // Persist the refreshed access token so later operations don't
                // each have to re-refresh. Re-read settings first so we don't
                // clobber a concurrent change.
                appScope.launch {
                    val current = getSettings()
                    if (current.refreshToken == settings.refreshToken &&
                        current.accessToken != newToken) {
                        saveSettings(current.copy(accessToken = newToken))
                    }
                }
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
