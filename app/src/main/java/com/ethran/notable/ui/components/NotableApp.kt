package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.AppRepository
import com.ethran.notable.gestures.quickNavGesture
import com.ethran.notable.ink.StreamRequestBus
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.navigation.NotableNavHost
import com.ethran.notable.navigation.rememberNotableAppState
import com.ethran.notable.ui.SnackBar
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.SnackState


@Composable
fun NotableApp(
    exportEngine: ExportEngine,
    snackState: SnackState,
    snackDispatcher: SnackDispatcher,
    appRepository: AppRepository
) {
    val appNavState = rememberNotableAppState()

    // Laptop-initiated streaming: MainActivity resolved/imported the notebook and
    // emits it here; open its page in the editor (EditorViewModel then auto-starts
    // streaming via StreamRequestBus.pendingAutoStreamBookId).
    LaunchedEffect(Unit) {
        StreamRequestBus.openRequests.collect { req ->
            appNavState.goToEditor(req.pageId, req.bookId)
        }
    }

    // In-app prompt when a stream request arrives while the app is foregrounded
    // (avoids the notification-tap background->foreground surface re-init).
    var streamPromptDoc by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        StreamRequestBus.promptRequests.collect { streamPromptDoc = it }
    }
    streamPromptDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { streamPromptDoc = null },
            title = { Text("Stream from laptop") },
            text = { Text("Open “${doc.substringAfterLast('/')}” and stream to xournal?") },
            confirmButton = {
                TextButton(onClick = {
                    StreamRequestBus.postOpenDoc(doc)
                    streamPromptDoc = null
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { streamPromptDoc = null }) { Text("Dismiss") }
            }
        )
    }

    Box(
        Modifier
            .background(Color.White)
            .fillMaxSize()
            .quickNavGesture { appNavState.openQuickNav() }
    ) {
        NotableNavHost(
            exportEngine = exportEngine,
            appRepository = appRepository,
            appNavigator = appNavState
        )


        // overlays
        if (appNavState.isQuickNavOpen) {
            QuickNav(
                appRepository = appRepository,
                currentPageId = appNavState.currentPageId,
                quickNavSourcePageId = appNavState.quickNavSourcePageId,
                onClose = { appNavState.closeQuickNav() },
                goToPage = { pageId -> appNavState.goToPage(appRepository, pageId) },
                goToFolder = { folderId -> appNavState.goToLibrary(folderId) }
            )
        }

        if (appNavState.shouldAnchorBeVisible()) {
            Anchor(
                onClose = {
                    appNavState.goToAnchor(appRepository)
                    appNavState.closeQuickNav()
                }
            )
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Black)
    )
    SnackBar(state = snackState, dispatcher = snackDispatcher)
}