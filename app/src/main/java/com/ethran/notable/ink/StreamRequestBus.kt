package com.ethran.notable.ink

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Bridges a laptop-initiated stream request across the app:
 *  - InkFcmService receives the push. If the app is in the FOREGROUND
 *    ([appForeground]) it posts to [promptRequests] so we show an in-app prompt
 *    (no background->foreground surface re-init, so no editor-open race);
 *    otherwise it posts an Android notification.
 *  - MainActivity resolves/imports the notebook and posts an [OpenBook];
 *  - NotableApp collects [openRequests] and navigates to the editor;
 *  - EditorViewModel, once that notebook has loaded, sees its id in
 *    [pendingAutoStreamBookId] and auto-starts streaming.
 *
 * Channels (not replay-less SharedFlows) are used so a request posted before its
 * collector subscribes is buffered and delivered exactly once, not dropped.
 */
object StreamRequestBus {
    data class OpenBook(val pageId: String, val bookId: String)

    /** True while an activity is at least STARTED (surface is live). */
    @Volatile
    var appForeground: Boolean = false

    // Dropbox path -> show an in-app "stream from laptop?" prompt (foreground).
    private val prompts = Channel<String>(Channel.BUFFERED)
    val promptRequests = prompts.receiveAsFlow()
    fun postPrompt(doc: String) {
        prompts.trySend(doc)
    }

    // Dropbox path the user accepted in the in-app modal -> MainActivity resolves/
    // imports it (same path as a notification tap). Kept separate from the
    // notification Intent so both routes share MainActivity's open logic.
    private val openDocs = Channel<String>(Channel.BUFFERED)
    val openDocRequests = openDocs.receiveAsFlow()
    fun postOpenDoc(doc: String) {
        openDocs.trySend(doc)
    }

    // Resolved notebook -> navigate to its editor.
    private val opens = Channel<OpenBook>(Channel.BUFFERED)
    val openRequests = opens.receiveAsFlow()
    fun postOpen(book: OpenBook) {
        opens.trySend(book)
    }

    /** Set just before posting an open; the editor consumes it once (clears it). */
    @Volatile
    var pendingAutoStreamBookId: String? = null
}
