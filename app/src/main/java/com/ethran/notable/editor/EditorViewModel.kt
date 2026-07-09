package com.ethran.notable.editor

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.editor.EditorViewModel.Companion.DEFAULT_PEN_SETTINGS
import com.ethran.notable.editor.canvas.CanvasEventBus
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private val log = ShipBook.getLogger("EditorViewModel")

// --------------------------------------------------------
// 1. UI STATE
// --------------------------------------------------------

/**
 * Flat toolbar/editor UI state exposed to Compose.
 * Also used as `EditorUiState` via typealias for backward compatibility.
 */
data class ToolbarUiState(
    // Document info
    val notebookId: String? = null,
    val pageId: String? = null,
    val isBookActive: Boolean = false,
    val pageNumberInfo: String = "1/1",
    val currentPageNumber: Int = 0,

    // Background
    val backgroundType: String = "native",
    val backgroundPath: String = "blank",
    val backgroundPageNumber: Int = 0,

    // Toolbar visibility & menus
    val isToolbarOpen: Boolean = false,
    val isMenuOpen: Boolean = false,
    val isStrokeSelectionOpen: Boolean = false,
    val isBackgroundSelectorModalOpen: Boolean = false,
    val showResetView: Boolean = false,
    val zoomLevel: Float = 1.0f,

    // Canvas / drawing
    val mode: Mode = Mode.Draw,
    val pen: Pen = Pen.BALLPEN,
    val eraser: Eraser = Eraser.PEN,
    // TODO: if it is an  emptyMap(), the DrawCanvas crashes, to be fixed.
    val penSettings: Map<String, PenSetting> = DEFAULT_PEN_SETTINGS,
    val isSelectionActive: Boolean = false,
    val hasClipboard: Boolean = false,
    val hasDropboxLink: Boolean = false,
    val streamingEnabled: Boolean = false,
    val isDrawing: Boolean = true,
    val isQuickNavOpen: Boolean = false,
    // Paginated ("snap") page height for this notebook, or null in legacy mode.
    val fixedPageHeightPt: Int? = null,
    // Render paginated notebooks as a continuous stack of discrete pages (gated).
    val continuousScroll: Boolean = false,
    // Ordered pageIds of the notebook (for the continuous-view compositor).
    val continuousPageIds: List<String> = emptyList(),
    // last width entered via the "C" custom stroke-width chip (pre-fills the modal)
    val lastCustomWidth: Float = 15f,
) {
    val isDrawingAllowed: Boolean
        get() = !isSelectionActive &&
                !(isMenuOpen || isStrokeSelectionOpen || isBackgroundSelectorModalOpen)
                && !isQuickNavOpen
}


// --------------------------------------------------------
// 2. USER ACTIONS (Intents)
// --------------------------------------------------------

sealed class ToolbarAction {
    object ToggleToolbar : ToolbarAction()
    data class ChangeMode(val mode: Mode) : ToolbarAction()
    data class ChangePen(val pen: Pen) : ToolbarAction()
    data class ChangePenSetting(val pen: Pen, val setting: PenSetting) : ToolbarAction()
    data class ChangeEraser(val eraser: Eraser) : ToolbarAction()
    object ToggleMenu : ToolbarAction()
    data class ToggleEraserManu(val isOpen: Boolean) : ToolbarAction()
    data class ToggleBackgroundSelector(val isOpen: Boolean) : ToolbarAction()
    data class ToggleScribbleToErase(val enabled: Boolean) : ToolbarAction()

    object Undo : ToolbarAction()
    object Redo : ToolbarAction()
    object Paste : ToolbarAction()
    object ResetView : ToolbarAction()
    object ClearAllStrokes : ToolbarAction()

    data class ImagePicked(val uri: Uri) : ToolbarAction()
    data class ExportPage(val format: ExportFormat) : ToolbarAction()
    data class ExportBook(val format: ExportFormat) : ToolbarAction()
    data class BackgroundChanged(val type: String, val path: String?) : ToolbarAction()

    object NavigateToLibrary : ToolbarAction()
    object NavigateToBugReport : ToolbarAction()
    object NavigateToPages : ToolbarAction()
    object NavigateToHome : ToolbarAction()

    object CloseAllMenus : ToolbarAction()
    data class UpdateQuickNavOpen(val isOpen: Boolean) : ToolbarAction()

    object SaveToDropbox : ToolbarAction()
    object ToggleInkStream : ToolbarAction()

    /**
     * Open this document in xournal on the laptop (via the inkhub broker) and
     * stream to it; auto-links the notebook to Dropbox first if needed.
     */
    object StartStreaming : ToolbarAction()

    /** Set an absolute zoom level, anchored on the current view center. */
    data class SetZoom(val level: Float) : ToolbarAction()

    /** Fit the page width to the visible view (fixes right-edge clipping). */
    object ZoomFitWidth : ToolbarAction()

    /** Page navigation from the toolbar (same as a swipe). */
    object PreviousPage : ToolbarAction()
    object NextPage : ToolbarAction()

    /** Set the current pen's stroke width. isCustom=true persists it as the last custom width. */
    data class SetPenWidth(val width: Float, val isCustom: Boolean) : ToolbarAction()
}


// --------------------------------------------------------
// 3. CANVAS COMMANDS (Imperative drawing actions)
// --------------------------------------------------------

sealed class CanvasCommand {
    object Undo : CanvasCommand()
    object Redo : CanvasCommand()
    object Paste : CanvasCommand()
    object ResetView : CanvasCommand()
    data class SetZoom(val level: Float) : CanvasCommand()
    object ZoomFitWidth : CanvasCommand()
    data class SetSelectionWidth(val width: Float, val isCustom: Boolean) : CanvasCommand()
    object ClearAllStrokes : CanvasCommand()
    object RefreshCanvas : CanvasCommand()

    /** Continuous view: displace the viewport by one page (+1 next, -1 prev). */
    data class ShiftContinuousViewport(val dir: Int) : CanvasCommand()
    data class CopyImageToCanvas(val uri: Uri) : CanvasCommand()
}

// --------------------------------------------------------
// 4. UI EVENTS (Navigation, Snackbars)
// --------------------------------------------------------

sealed class EditorUiEvent {
    data class NavigateToLibrary(val folderId: String?) : EditorUiEvent()
    data class NavigateToPages(val bookId: String) : EditorUiEvent()
    object NavigateToBugReport : EditorUiEvent()
    object SaveToDropbox : EditorUiEvent()
}

// --------------------------------------------------------
// 5. VIEW MODEL
// --------------------------------------------------------

@HiltViewModel
class EditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val editorSettingCacheManager: EditorSettingCacheManager,
    private val exportEngine: ExportEngine,
    val pageDataManager: PageDataManager,
    private val syncOrchestrator: SyncOrchestrator,
    val snackDispatcher: SnackDispatcher,
    private val historyFactory: History.Factory,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val dropboxSyncManager: com.ethran.notable.dropbox.DropboxSyncManager,
    // Injected so the singleton is created (and its persisted settings loaded)
    // when the editor opens, rather than lazily on the first stroke -- otherwise
    // streaming stays off after an app restart until the settings tab is reopened.
    private val inkStreamClient: com.ethran.notable.ink.InkStreamClient
) : ViewModel() {
    // ---- Toolbar / UI State (single flat flow) ----
    private val _toolbarState = MutableStateFlow(ToolbarUiState())
    val toolbarState: StateFlow<ToolbarUiState> = _toolbarState.asStateFlow()

    init {
        viewModelScope.launch {
            ClipboardStore.content.collect { setHasClipboard(it != null) }
        }
        // Keep the toolbar toggle in sync with the streaming setting.
        viewModelScope.launch {
            inkStreamClient.settings.collect { s ->
                _toolbarState.update { it.copy(streamingEnabled = s.enabled) }
            }
        }
    }

    // ---- One-Time Events (Channels) ----
    private val uiEventChannel = Channel<EditorUiEvent>(Channel.BUFFERED)
    val uiEvents = uiEventChannel.receiveAsFlow()

    private val canvasCommandChannel = Channel<CanvasCommand>(Channel.BUFFERED)
    val canvasCommands = canvasCommandChannel.receiveAsFlow()

    // ---- Internal document context ----
    private var bookId: String? = null
    private val currentPageId: String get() = _toolbarState.value.pageId.orEmpty()

    // ---- Init guard ----
    private val didInitSettings = AtomicBoolean(false)

    // ---- Selection state (kept for drawing logic compatibility) ----
    val selectionState = SelectionState()

    // --------------------------------------------------------
    // Initialization from persisted settings
    // --------------------------------------------------------

    /**
     * Restores editor settings from the persisted cache.
     * Idempotent: only applies settings on first call; subsequent calls are no-ops.
     */
    fun initFromPersistedSettings() {
        if (!didInitSettings.compareAndSet(false, true)) return
        val settings = editorSettingCacheManager.getEditorSettings()
        _toolbarState.update {
            it.copy(
                mode = settings?.mode ?: Mode.Draw,
                pen = settings?.pen ?: Pen.BALLPEN,
                eraser = settings?.eraser ?: Eraser.PEN,
                isToolbarOpen = settings?.isToolbarOpen ?: false,
                penSettings = settings?.penSettings ?: DEFAULT_PEN_SETTINGS,
                lastCustomWidth = settings?.lastCustomWidth ?: 15f
            )
        }
    }

    /**
     * Called when the EditorView is being disposed.
     * Performs cleanup, exports linked files, and triggers auto-sync.
     */
    fun onDispose(page: PageView) {
        // 1. Finish selection operation
        selectionState.applySelectionDisplace(page)
        bookId?.let { bookId ->
            exportEngine.exportToLinkedFileAsync(bookId)
        }

        // 3. Cleanup page resources
        page.disposeOldPage()
    }

    fun createHistory(page: PageView): History = historyFactory.create(page)

    // --------------------------------------------------------
    // Toolbar Action Dispatch
    // --------------------------------------------------------

    fun onToolbarAction(action: ToolbarAction) {
        log.v("onToolbarAction: $action")
        when (action) {
            is ToolbarAction.ToggleToolbar -> {
                _toolbarState.update { it.copy(isToolbarOpen = !it.isToolbarOpen) }
                updateDrawingState()
                saveToolbarState()
            }

            is ToolbarAction.ChangeMode -> {
                _toolbarState.update { it.copy(mode = action.mode) }
                updateDrawingState()
                saveToolbarState()
            }

            is ToolbarAction.ChangePen -> handlePenChange(action.pen)
            is ToolbarAction.ChangePenSetting -> handlePenSettingChange(action.pen, action.setting)
            is ToolbarAction.ChangeEraser -> handleEraserChange(action.eraser)
            is ToolbarAction.ToggleMenu -> {
                _toolbarState.update { it.copy(isMenuOpen = !it.isMenuOpen) }
//                updateDrawingState() // on focus change is doing this
            }

            is ToolbarAction.ToggleEraserManu -> {
                _toolbarState.update { it.copy(isStrokeSelectionOpen = action.isOpen) }
//                updateDrawingState() // on focus change is doing this
            }

            is ToolbarAction.ToggleBackgroundSelector -> {
                _toolbarState.update { it.copy(isBackgroundSelectorModalOpen = action.isOpen) }
//                updateDrawingState() // on focus change is doing this
            }

            is ToolbarAction.ToggleScribbleToErase -> updateScribbleToErase(action.enabled)
            is ToolbarAction.ImagePicked -> handleImagePicked(action.uri)
            is ToolbarAction.ExportPage -> handleExport(
                ExportTarget.Page(currentPageId),
                action.format
            )

            is ToolbarAction.ExportBook -> {
                bookId?.let { handleExport(ExportTarget.Book(it), action.format) }
            }

            is ToolbarAction.BackgroundChanged -> handleBackgroundChange(action.type, action.path)

            ToolbarAction.Undo -> sendCanvasCommand(CanvasCommand.Undo)
            ToolbarAction.Redo -> sendCanvasCommand(CanvasCommand.Redo)
            ToolbarAction.Paste -> sendCanvasCommand(CanvasCommand.Paste)
            ToolbarAction.ResetView -> sendCanvasCommand(CanvasCommand.ResetView)
            is ToolbarAction.SetZoom -> sendCanvasCommand(CanvasCommand.SetZoom(action.level))
            ToolbarAction.ZoomFitWidth -> sendCanvasCommand(CanvasCommand.ZoomFitWidth)
            ToolbarAction.PreviousPage -> goToPreviousPage()
            ToolbarAction.NextPage -> goToNextPage()
            is ToolbarAction.SetPenWidth -> {
                applyPenWidth(action.width, action.isCustom)
                // With a selection active, the width widget also applies to it.
                if (_toolbarState.value.isSelectionActive)
                    sendCanvasCommand(CanvasCommand.SetSelectionWidth(action.width, action.isCustom))
            }
            ToolbarAction.ClearAllStrokes -> sendCanvasCommand(CanvasCommand.ClearAllStrokes)

            ToolbarAction.NavigateToLibrary -> handleNavigateToLibrary()
            ToolbarAction.NavigateToBugReport -> sendUiEvent(EditorUiEvent.NavigateToBugReport)
            ToolbarAction.NavigateToPages -> handleNavigateToPages()
            ToolbarAction.NavigateToHome -> sendUiEvent(EditorUiEvent.NavigateToLibrary(null))

            ToolbarAction.CloseAllMenus -> handleCloseAllMenus()
            is ToolbarAction.UpdateQuickNavOpen -> {
                _toolbarState.update { it.copy(isQuickNavOpen = action.isOpen) }
                updateDrawingState()
            }

            ToolbarAction.SaveToDropbox -> sendUiEvent(EditorUiEvent.SaveToDropbox)
            ToolbarAction.ToggleInkStream -> handleToggleInkStream()
            ToolbarAction.StartStreaming -> handleStartStreaming()
        }
    }

    // --------------------------------------------------------
    // Toolbar Action Handlers (private)
    // --------------------------------------------------------

    private fun handlePenChange(pen: Pen) {
        val state = _toolbarState.value
        if (state.mode == Mode.Draw && state.pen == pen) {
            _toolbarState.update { it.copy(isStrokeSelectionOpen = true) }
        } else {
            _toolbarState.update {
                it.copy(pen = pen, mode = Mode.Draw)
            }
            saveToolbarState()
        }
        updateDrawingState()
        viewModelScope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    private fun handleEraserChange(eraser: Eraser) {
        _toolbarState.update { it.copy(eraser = eraser) }
        updateDrawingState()
        saveToolbarState()
    }

    private fun handlePenSettingChange(pen: Pen, setting: PenSetting) {
        val newSettings = _toolbarState.value.penSettings.toMutableMap()
        newSettings[pen.penName] = setting
        _toolbarState.update { it.copy(penSettings = newSettings) }
        saveToolbarState()
    }

    /** Set the current pen's stroke width; optionally remember it as the last custom width. */
    fun applyPenWidth(width: Float, isCustom: Boolean) {
        val pen = _toolbarState.value.pen
        val cur = _toolbarState.value.penSettings[pen.penName] ?: return
        val newSettings = _toolbarState.value.penSettings.toMutableMap()
        newSettings[pen.penName] = cur.copy(strokeSize = width)
        _toolbarState.update {
            it.copy(
                penSettings = newSettings,
                lastCustomWidth = if (isCustom) width else it.lastCustomWidth
            )
        }
        saveToolbarState()
    }

    /** Remember a width as the last custom (e.g. a custom width applied to a selection). */
    fun noteCustomWidth(width: Float) {
        _toolbarState.update { it.copy(lastCustomWidth = width) }
        saveToolbarState()
    }

    private fun handleCloseAllMenus() {
        log.d("Closing all menus in EditorViewModel")
        _toolbarState.update {
            it.copy(
                isMenuOpen = false,
                isStrokeSelectionOpen = false,
                isBackgroundSelectorModalOpen = false
            )
        }
        updateDrawingState()
    }

    private fun updateScribbleToErase(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(
                GlobalAppSettings.current.copy(scribbleToEraseEnabled = enabled)
            )
        }
    }

    private fun handleImagePicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val copiedFile = copyImageToDatabase(context, uri)
                sendCanvasCommand(CanvasCommand.CopyImageToCanvas(copiedFile.toUri()))
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Image import failed: ${e.message}",
                        duration = 3000
                    )
                )
            }
        }
    }

    private fun handleExport(target: ExportTarget, format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportEngine.export(target, format)
                snackDispatcher.showOrUpdateSnack(SnackConf(text = result, duration = 4000))
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Export failed: ${e.message}",
                        duration = 3000
                    )
                )
            }
        }
    }

    private fun handleBackgroundChange(type: String, path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(currentPageId) ?: return@launch
            val updatedPage = if (path == null) {
                page.copy(
                    backgroundType = type,
                    updatedAt = Date()
                )
            } else {
                page.copy(
                    background = path,
                    backgroundType = type,
                    updatedAt = Date()
                )
            }
            appRepository.pageRepository.update(updatedPage)

            // Calculate background page number
            val bgPageNum = when (val bgTypeObj = BackgroundType.fromKey(type)) {
                is BackgroundType.Pdf -> bgTypeObj.page
                is BackgroundType.AutoPdf -> {
                    bookId?.let { appRepository.getPageNumber(it, currentPageId) } ?: 0
                }

                else -> 0
            }

            _toolbarState.update {
                it.copy(
                    backgroundType = updatedPage.backgroundType,
                    backgroundPath = updatedPage.background,
                    backgroundPageNumber = bgPageNum
                )
            }
            sendCanvasCommand(CanvasCommand.RefreshCanvas)
        }
    }

    private fun handleNavigateToLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(currentPageId)
            val parentFolder = page?.getParentFolder(appRepository.bookRepository)
            sendUiEvent(EditorUiEvent.NavigateToLibrary(parentFolder))
        }
    }

    private fun handleNavigateToPages() {
        bookId?.let { id ->
            sendUiEvent(EditorUiEvent.NavigateToPages(id))
        }
    }

    // --------------------------------------------------------
    // Drawing State
    // --------------------------------------------------------

    /**
     * Re-evaluates whether drawing should be enabled based on menu and selection states.
     */
    fun updateDrawingState() {
        // It get called three times on canvas creation.
        val shouldBeDrawing = _toolbarState.value.isDrawingAllowed
        _toolbarState.update { it.copy(isDrawing = shouldBeDrawing) }
        log.d("updateDrawingState: Drawing state: $shouldBeDrawing")
        viewModelScope.launch {
            if (shouldBeDrawing)
                DeviceCompat.delayBeforeResumingDrawing()
            CanvasEventBus.isDrawing.emit(shouldBeDrawing)
        }
    }

    // --------------------------------------------------------
    // Book / Page Data
    // --------------------------------------------------------

    /**
     * Loads context data for the toolbar (page number, background info, etc.)
     */
    suspend fun loadToolbarState(bookId: String?, pageId: String) {
        log.v("loadBookData: bookId=$bookId, pageId=$pageId")
        this.bookId = bookId

        val page = appRepository.pageRepository.getById(pageId)

        if (page == null) {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Could not find page",
                    duration = 3000
                )
            )
            fixNotebook(bookId, pageId)
            return
        }
        val book = bookId?.let { appRepository.bookRepository.getById(it) }

        val pageIndex = book?.getPageIndex(pageId) ?: 0
        val totalPages = book?.pageIds?.size ?: 1

        val backgroundTypeObj = BackgroundType.fromKey(page.backgroundType)
        val bgPageNumber = when (backgroundTypeObj) {
            is BackgroundType.Pdf -> backgroundTypeObj.page
            is BackgroundType.AutoPdf -> {
                bookId?.let { appRepository.getPageNumber(it, pageId) } ?: 0
            }

            else -> 0
        }

        val hasDropbox = bookId != null && dropboxSyncManager.isDropboxLinked(bookId)
        val fixedHeight = bookId?.let {
            appRepository.bookRepository.getById(it)?.fixedPageHeightPt
        }

        _toolbarState.update {
            it.copy(
                notebookId = bookId,
                pageId = pageId,
                isBookActive = bookId != null,
                pageNumberInfo = if (bookId != null) "${pageIndex + 1}/$totalPages" else "1/1",
                currentPageNumber = pageIndex,
                backgroundType = page.backgroundType,
                backgroundPath = page.background,
                backgroundPageNumber = bgPageNumber,
                hasDropboxLink = hasDropbox,
                fixedPageHeightPt = fixedHeight,
                // Continuous view only applies to paginated notebooks; global
                // setting for now (per-notebook toggle later).
                continuousScroll = fixedHeight != null &&
                    com.ethran.notable.data.datastore.GlobalAppSettings.current.continuousScrollByDefault,
                continuousPageIds = book?.pageIds ?: emptyList()
            )
        }

        // Laptop-initiated (reverse) streaming: if this notebook was opened by a
        // "Stream from tablet" request, auto-start streaming now that it's loaded.
        if (bookId != null &&
            com.ethran.notable.ink.StreamRequestBus.pendingAutoStreamBookId == bookId
        ) {
            com.ethran.notable.ink.StreamRequestBus.pendingAutoStreamBookId = null
            handleStartStreaming()
        }
    }

    private fun saveToolbarState() {
        val currentState = _toolbarState.value
        editorSettingCacheManager.setEditorSettings(
            EditorSettingCacheManager.EditorSettings(
                isToolbarOpen = currentState.isToolbarOpen,
                mode = currentState.mode,
                pen = currentState.pen,
                eraser = currentState.eraser,
                penSettings = currentState.penSettings,
                lastCustomWidth = currentState.lastCustomWidth
            )
        )
    }

    /**
     * Attempts to repair potential inconsistencies in the notebook's data structure.
     */
    fun fixNotebook(bookId: String?, pageId: String) {
        log.i("Could not find page, prompting for repair")
        snackDispatcher.showOrUpdateSnack(
            SnackConf(
                text = "Could not find page",
                duration = 60000,
                actions = listOf(
                    "Remove bad page" to {
                        viewModelScope.launch(Dispatchers.IO) {
                            if (bookId != null) {
                                appRepository.bookRepository.removePage(bookId, pageId)
                            }
                            sendUiEvent(EditorUiEvent.NavigateToLibrary(null))
                        }
                    }
                )
            )
        )
    }

    // --------------------------------------------------------
    // Page navigation
    // --------------------------------------------------------

    private suspend fun getNextPageId(): String? {
        return if (bookId != null) {
            appRepository.getNextPageIdFromBookAndPageOrCreate(
                pageId = currentPageId, notebookId = bookId!!
            )
        } else null
    }

    private suspend fun getPreviousPageId(): String? {
        return if (bookId != null) {
            appRepository.getPreviousPageIdFromBookAndPage(
                pageId = currentPageId, notebookId = bookId!!
            )
        } else null
    }

    fun goToNextPage() {
        log.v("goToNextPage")
        viewModelScope.launch(Dispatchers.IO) {
            // Continuous view: a page-nav is purely a one-page viewport shift; the
            // majority-page computation drives the indicator (calling changePage
            // here would re-run loadToolbarState and fight that indicator).
            if (_toolbarState.value.continuousScroll) {
                sendCanvasCommand(CanvasCommand.ShiftContinuousViewport(1))
                return@launch
            }
            getNextPageId()?.let { changePage(it) }
        }
    }

    fun goToPreviousPage() {
        log.v("goToPreviousPage")
        viewModelScope.launch(Dispatchers.IO) {
            if (_toolbarState.value.continuousScroll) {
                sendCanvasCommand(CanvasCommand.ShiftContinuousViewport(-1))
                return@launch
            }
            getPreviousPageId()?.let { changePage(it) }
        }
    }

    /** Continuous view: the majority page in the viewport is the current page. It
     *  drives the toolbar indicator AND anchors the actual current page (openPageId
     *  persistence, neighbor prefetch, per-page background, and the streaming/
     *  drawing target). Called post-scroll by PageView when the majority changes. */
    fun onContinuousPageIndex(index: Int) {
        val ids = _toolbarState.value.continuousPageIds
        val total = ids.size.coerceAtLeast(1)
        _toolbarState.update {
            it.copy(currentPageNumber = index, pageNumberInfo = "${index + 1}/$total")
        }
        val pageId = ids.getOrNull(index) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (pageId == pageDataManager.getCurrentPageId()) return@launch
            pageDataManager.setPage(pageId)           // cheap: DB fetch + pointer
            bookId?.let { appRepository.bookRepository.setOpenPageId(it, pageId) }
            pageDataManager.cacheNeighbors()          // prefetch around the new anchor
        }
    }

    /**
     * Paginated-mode navigation: crossing a page boundary by scrolling is the
     * same page change as a swipe, but we pre-position the destination page's
     * scroll so the motion feels continuous -- land at the TOP of the next page
     * when scrolling down, and at the BOTTOM of the previous page when scrolling
     * up. Mirrors swipe exactly at the ends (no-op past the first/last page).
     */
    fun snapToNextPage() {
        viewModelScope.launch(Dispatchers.IO) {
            val nextId = getNextPageId() ?: return@launch
            val cur = pageDataManager.getPageScroll(currentPageId)
            pageDataManager.setPageScroll(nextId, Offset(cur.x, 0f))
            changePage(nextId)
        }
    }

    fun snapToPreviousPage(prevPageMaxScrollY: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val prevId = getPreviousPageId() ?: return@launch
            val cur = pageDataManager.getPageScroll(currentPageId)
            pageDataManager.setPageScroll(prevId, Offset(cur.x, prevPageMaxScrollY))
            changePage(prevId)
        }
    }

    /**
     * Updates the persistence layer and UI state to reflect a change in the currently opened page.
     *
     * This method saves the [newPageId] as the last opened page for the current notebook in the
     * repository. If the page ID has changed, it updates the toolbar state; otherwise, it
     * triggers a UI event to notify the user that the target page is already active.
     *
     * @param newPageId The unique identifier of the page to be set as open.
     */
    private suspend fun updateOpenedPage(newPageId: String) {
        log.v("updateOpenedPage: $newPageId")
        Log.d("EditorView", "Update open page to $newPageId")
        if (bookId != null) {
            appRepository.bookRepository.setOpenPageId(bookId!!, newPageId)
        }
        if (newPageId != currentPageId) {
            // The View's LaunchedEffect will handle the full load once navigation syncs.
            Log.d("EditorView", "Page changed")
            val oldPage = currentPageId
            // Keep the "Page X of Y" indicator in sync on navigation. (The live
            // mirror derives its xournal page from stroke y-coordinates, not this
            // discrete index, since Notable pages are continuous.)
            val newIndex = bookId?.let {
                appRepository.bookRepository.getById(it)?.getPageIndex(newPageId)
            }?.takeIf { it >= 0 } ?: 0
            _toolbarState.update { it.copy(pageId = newPageId, currentPageNumber = newIndex) }
            syncFromPageId(oldPage)
        } else {
            Log.d("EditorView", "Tried to change to same page!")
            val snack = SnackConf(text = "Tried to change to same page!", duration = 4000)
            snackDispatcher.showOrUpdateSnack(snack)
            CanvasEventBus.restoreCanvas.emit(Unit)
        }
    }

    /**
     * Changes the current page to the one with the specified [id].
     *
     * @param id The unique identifier of the page to switch to.
     */
    fun changePage(id: String) {
        log.d("Changing page to $id, from $currentPageId")
        viewModelScope.launch(Dispatchers.IO) {
            // Update the UI state
            updateOpenedPage(id)

            // Clean the selection state on Main to avoid snapshot violations during composition.
            withContext(Dispatchers.Main.immediate) {
                selectionState.reset()
            }
        }
    }

    // --------------------------------------------------------
    // Toolbar State Sync Helpers
    // --------------------------------------------------------

    fun setHasClipboard(hasClipboard: Boolean) {
        _toolbarState.update { it.copy(hasClipboard = hasClipboard) }
    }

    fun setShowResetView(showResetView: Boolean) {
        _toolbarState.update { it.copy(showResetView = showResetView) }
    }

    fun setZoomLevel(zoom: Float) {
        _toolbarState.update { it.copy(zoomLevel = zoom) }
    }

    fun setSelectionActive(active: Boolean) {
        log.v("setSelectionActive: $active")
        if (_toolbarState.value.isSelectionActive != active) {
            if (active) //selection is active, we can directly update it, and skip other checks
                viewModelScope.launch {
                    CanvasEventBus.isDrawing.emit(false)
                }
            _toolbarState.update { it.copy(isSelectionActive = active) }
            if (!active)
                updateDrawingState()
        }
    }

    fun setDrawingStateFromCanvas(isDrawing: Boolean) {
        _toolbarState.update { it.copy(isDrawing = isDrawing) }
    }

    // --------------------------------------------------------
    // Event / Command Helpers
    // --------------------------------------------------------

    private fun sendUiEvent(event: EditorUiEvent) {
        log.v("sendUiEvent: $event")
        viewModelScope.launch { uiEventChannel.send(event) }
    }

    private fun sendCanvasCommand(command: CanvasCommand) {
        log.v("sendCanvasCommand: $command")
        viewModelScope.launch { canvasCommandChannel.send(command) }
    }

    fun handleSaveToDropbox() {
        val notebookId = bookId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            snackDispatcher.showOrUpdateSnack(SnackConf(text = "Saving to Dropbox...", duration = 2000))
            val result = dropboxSyncManager.uploadNotebook(notebookId)
            when (result) {
                is com.ethran.notable.utils.AppResult.Success -> {
                    snackDispatcher.showOrUpdateSnack(SnackConf(text = "Saved to Dropbox", duration = 3000))
                }
                is com.ethran.notable.utils.AppResult.Error -> {
                    snackDispatcher.showOrUpdateSnack(
                        SnackConf(text = "Dropbox save failed: ${result.error.userMessage}", duration = 5000)
                    )
                }
            }
        }
    }

    /**
     * "Start streaming" (hub flow): make sure the notebook is Dropbox-linked
     * (auto-link + initial upload for brand-new notebooks), ask the inkhub
     * broker on the laptop to open the document in xournal, then stream to
     * the UDP port it returns.
     */
    fun handleStartStreaming() {
        val notebookId = bookId ?: return
        val settings = inkStreamClient.settings.value
        if (settings.host.isBlank()) {
            viewModelScope.launch {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = "Set the laptop host in Settings > Ink Stream first", duration = 4000)
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Ensure legacy manifest links are bridged onto the notebook before
            // we read its identity (idempotent; avoids racing the async
            // migration kicked off at manager construction).
            dropboxSyncManager.migrateLegacyLinks()
            // Identity is the path stored on the notebook. If it's already
            // Dropbox-linked, stream straight to that path -- no manifest
            // lookup, no forking a new file.
            var dropboxPath = dropboxSyncManager.linkedPath(notebookId)
            if (dropboxPath == null) {
                // Not linked yet: assign a path, publish the initial snapshot,
                // and (only on a successful upload) stamp the link. A failed
                // upload leaves the notebook unlinked so a retry is clean.
                val title = appRepository.bookRepository.getById(notebookId)?.title ?: "untitled"
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = "Linking to Dropbox...", duration = 2000)
                )
                when (val linked = dropboxSyncManager.linkAndUpload(notebookId, title)) {
                    is com.ethran.notable.utils.AppResult.Success -> {
                        dropboxPath = linked.data
                        _toolbarState.update { it.copy(hasDropboxLink = true) }
                    }
                    is com.ethran.notable.utils.AppResult.Error -> {
                        snackDispatcher.showOrUpdateSnack(
                            SnackConf(
                                text = "Initial Dropbox upload failed: ${linked.error.userMessage}",
                                duration = 5000
                            )
                        )
                        return@launch
                    }
                }
            }

            snackDispatcher.showOrUpdateSnack(
                SnackConf(text = "Opening on ${settings.host}...", duration = 3000)
            )
            when (val r = com.ethran.notable.ink.InkHubClient.openDoc(
                settings.host, settings.hubPort, dropboxPath, settings.secret
            )) {
                is com.ethran.notable.ink.InkHubClient.OpenResult.Ok -> {
                    inkStreamClient.saveSettings(
                        settings.copy(enabled = true, port = r.udpPort, sessionToken = r.token)
                    )
                    snackDispatcher.showOrUpdateSnack(
                        SnackConf(
                            text = "Streaming to ${settings.host}:${r.udpPort}",
                            duration = 3000
                        )
                    )
                }
                is com.ethran.notable.ink.InkHubClient.OpenResult.Error -> {
                    snackDispatcher.showOrUpdateSnack(
                        SnackConf(text = "Hub error: ${r.message}", duration = 5000)
                    )
                }
            }
        }
    }

    fun handleToggleInkStream() {
        val current = inkStreamClient.settings.value
        val next = !current.enabled
        if (next && current.host.isBlank()) {
            viewModelScope.launch {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Set a receiver host in Settings > Ink Stream first",
                        duration = 4000
                    )
                )
            }
            return
        }
        inkStreamClient.saveSettings(current.copy(enabled = next))
        viewModelScope.launch {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = if (next) "Live streaming on -> ${current.host}:${current.port}"
                    else "Live streaming off",
                    duration = 2500
                )
            )
        }
    }

    companion object {
        val DEFAULT_PEN_SETTINGS = mapOf(
            Pen.BALLPEN.penName to PenSetting(5f, Color.BLACK),
            Pen.REDBALLPEN.penName to PenSetting(5f, Color.RED),
            Pen.BLUEBALLPEN.penName to PenSetting(5f, Color.BLUE),
            Pen.GREENBALLPEN.penName to PenSetting(5f, Color.GREEN),
            Pen.PENCIL.penName to PenSetting(5f, Color.BLACK),
            Pen.BRUSH.penName to PenSetting(5f, Color.BLACK),
            Pen.MARKER.penName to PenSetting(40f, Color.LTGRAY),
            Pen.FOUNTAIN.penName to PenSetting(5f, Color.BLACK)
        )
    }


    fun showHint(message: String, durationMs: Int = 1500) {
        snackDispatcher.showOrUpdateSnack(
            SnackConf(text = message, duration = durationMs)
        )
    }

    suspend fun syncFromPageId(pageId: String) {
        syncOrchestrator.syncFromPageId(pageId)
    }
}
