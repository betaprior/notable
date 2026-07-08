package com.ethran.notable.dropbox

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages the manifest of Dropbox-synced xoj/xopp files.
 * The manifest lives locally at ~/Dropbox/notes-sync/manifest.json
 * and is generated/updated from filelist.txt in the same directory.
 */
object DropboxManifest {
    private const val TAG = "DropboxManifest"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class ManifestEntry(
        val dropboxPath: String,
        val format: String,  // "xoj" or "xopp"
        val notebookId: String,
        val title: String,
        val lastSyncedRev: String = ""
    )

    @Serializable
    data class Manifest(
        val version: Int = 1,
        val files: List<ManifestEntry> = emptyList()
    )

    /**
     * Read manifest from disk. Returns empty manifest if file doesn't exist.
     */
    fun readManifest(manifestPath: String): Manifest {
        val file = File(manifestPath)
        if (!file.exists()) return Manifest()
        return try {
            json.decodeFromString<Manifest>(file.readText())
        } catch (e: Exception) {
            Log.e(TAG,"Failed to read manifest: ${e.message}")
            Manifest()
        }
    }

    /**
     * Write manifest to disk.
     */
    fun writeManifest(manifestPath: String, manifest: Manifest) {
        val file = File(manifestPath)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(manifest))
    }

    /**
     * Read filelist.txt — one Dropbox path per line.
     * Blank lines and lines starting with # are ignored.
     */
    fun readFileList(filelistPath: String): List<String> {
        val file = File(filelistPath)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }

    /**
     * Merge filelist.txt entries into the existing manifest.
     * - New entries (paths not already in manifest) get added with a generated notebookId
     * - Existing entries are preserved (keeps their rev and notebookId)
     * - Entries removed from filelist are kept in manifest (pruning is separate)
     * @return updated manifest and count of new entries added
     */
    fun mergeFileList(manifest: Manifest, filelistPaths: List<String>): Pair<Manifest, Int> {
        val existingByPath = manifest.files.associateBy { it.dropboxPath }
        var newCount = 0

        val merged = filelistPaths.map { path ->
            existingByPath[path] ?: run {
                newCount++
                ManifestEntry(
                    dropboxPath = path,
                    format = inferFormat(path),
                    notebookId = UUID.randomUUID().toString(),
                    title = inferTitle(path)
                )
            }
        }

        // Keep entries that were in manifest but removed from filelist
        val filelistSet = filelistPaths.toSet()
        val removed = manifest.files.filter { it.dropboxPath !in filelistSet }

        return Manifest(version = 1, files = merged + removed) to newCount
    }

    /**
     * Update the rev for a specific entry after successful sync.
     */
    fun updateRev(manifest: Manifest, dropboxPath: String, rev: String): Manifest {
        return manifest.copy(
            files = manifest.files.map {
                if (it.dropboxPath == dropboxPath) it.copy(lastSyncedRev = rev) else it
            }
        )
    }

    /**
     * Add a Dropbox file to the catalog if not already present, leaving its rev
     * blank (nothing has been imported/synced yet). Existing entries -- imported
     * or previously catalogued -- are kept untouched so their rev survives.
     */
    fun upsertCatalog(manifest: Manifest, dropboxPath: String, format: String): Manifest {
        if (findByPath(manifest, dropboxPath) != null) return manifest
        val entry = ManifestEntry(
            dropboxPath = dropboxPath,
            format = format,
            notebookId = "",
            title = dropboxPath.substringAfterLast('/').removeSuffix(".$format"),
        )
        return manifest.copy(files = manifest.files + entry)
    }

    /**
     * Upsert a path's rev in the manifest-as-rev-cache: update the entry if the
     * path exists, otherwise add one. notebookId is left blank -- identity lives
     * on the notebook (linkedExternalUri), not here.
     */
    fun upsertRev(manifest: Manifest, dropboxPath: String, format: String, rev: String): Manifest {
        if (findByPath(manifest, dropboxPath) != null) {
            return updateRev(manifest, dropboxPath, rev)
        }
        val entry = ManifestEntry(
            dropboxPath = dropboxPath,
            format = format,
            notebookId = "",
            title = dropboxPath.substringAfterLast('/'),
            lastSyncedRev = rev,
        )
        return manifest.copy(files = manifest.files + entry)
    }

    /**
     * Update the notebookId for a specific entry (link manifest to actual Notable notebook).
     */
    fun updateNotebookId(manifest: Manifest, dropboxPath: String, notebookId: String): Manifest {
        return manifest.copy(
            files = manifest.files.map {
                if (it.dropboxPath == dropboxPath) it.copy(notebookId = notebookId) else it
            }
        )
    }

    /**
     * Find manifest entry by notebookId.
     */
    fun findByNotebookId(manifest: Manifest, notebookId: String): ManifestEntry? {
        return manifest.files.find { it.notebookId == notebookId }
    }

    /**
     * Find manifest entry by Dropbox path.
     */
    fun findByPath(manifest: Manifest, path: String): ManifestEntry? {
        return manifest.files.find { it.dropboxPath == path }
    }

    /** Drop a path from the catalog. Does not touch Dropbox or any notebook. */
    fun removeByPath(manifest: Manifest, path: String): Manifest {
        return manifest.copy(files = manifest.files.filterNot { it.dropboxPath == path })
    }

    private fun inferFormat(path: String): String {
        return when {
            path.endsWith(".xoj", ignoreCase = true) -> "xoj"
            path.endsWith(".xopp", ignoreCase = true) -> "xopp"
            else -> "xopp"
        }
    }

    private fun inferTitle(path: String): String {
        return path.substringAfterLast("/")
            .removeSuffix(".xopp")
            .removeSuffix(".xoj")
            .replace("_", " ")
            .replace("-", " ")
    }
}
