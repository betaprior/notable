package com.ethran.notable.dropbox

/**
 * A notebook's Dropbox identity is stored in Notebook.linkedExternalUri as a
 * "dropbox://<path>" URI (the path is the file's absolute Dropbox path, itself
 * starting with '/', so the URI reads dropbox:///notes-sync/foo.xoj).
 *
 * This makes the Dropbox path the notebook's identity: streaming and re-import
 * read it straight off the notebook, with no manifest notebookId lookup. The
 * manifest is reduced to a sync-state cache (rev per path).
 *
 * linkedExternalUri is shared with the older SAF "linked file export" feature,
 * which stores a content:// / file:// folder URI; the scheme discriminates,
 * and export-on-close skips the dropbox:// scheme.
 */
object DropboxLink {
    private const val SCHEME = "dropbox://"

    /** Build the linkedExternalUri value for a Dropbox [path] (e.g. "/notes/a.xoj"). */
    fun uriFor(path: String): String = SCHEME + path

    /** The Dropbox path encoded in [linkedExternalUri], or null if it isn't a Dropbox link. */
    fun pathOf(linkedExternalUri: String?): String? =
        if (linkedExternalUri != null && linkedExternalUri.startsWith(SCHEME))
            linkedExternalUri.removePrefix(SCHEME)
        else null

    fun isDropbox(linkedExternalUri: String?): Boolean =
        linkedExternalUri != null && linkedExternalUri.startsWith(SCHEME)
}
