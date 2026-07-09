package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import kotlin.math.min
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

// How long to wait after a stylus selection-drag pen-up (commit + refresh) before re-selecting
// the moved strokes, so the pen-up refresh settles and the isDrawing transition propagates before
// the panel re-enters selection/animation mode. Tunable if the re-select overlay lags or flickers.
private const val STYLUS_RESELECT_SETTLE_MS = 200L

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    private val viewModel: EditorViewModel,
    private val clipboardStore: ClipboardStore,
) {
    private var scrollInProgress = Mutex()
    private val logEditorControlTower = ShipBook.getLogger("EditorControlTower")
    private var changePageObserverJob: Job? = null

    // Paginated ("snap") scroll: one continuous drag turns AT MOST one page.
    // A drag emits many scroll samples; without this cooldown each one that sees
    // the boundary crossed would fire another page turn (and, at the last page,
    // auto-create a page). Lift and drag again to turn the next page.
    private var lastSnapAtMs = 0L
    private val snapCooldownMs = 350L

    // Accumulated, not-yet-rendered scroll delta in screen coordinates. Input events add
    // into this; a single consumer coroutine drains and renders it. StateFlow conflation
    // means a burst of input collapses to one render pass per frame the renderer can keep
    // up with.
    private val pendingScroll = MutableStateFlow(Offset.Zero)
    private var scrollConsumerJob: Job? = null

    fun registerObservers() {
        startScrollConsumer()
        if (changePageObserverJob?.isActive == true) return

        changePageObserverJob = scope.launch {
            CanvasEventBus.changePage.collect { pageId ->
                logEditorControlTower.d("Change to page $pageId")

                // Switch to Main thread for Compose state mutations
                withContext(Dispatchers.Main) {
                    viewModel.changePage(pageId)
                    history.cleanHistory()
                }
                // no need for this, we are listening for change of current page,
                // in EditorView
//                page.changePage(pageId)
                refreshScreen()
            }
        }
    }

    // TODO: remove it, change to proper solution
    fun unregisterObservers() {
        changePageObserverJob?.cancel()
        changePageObserverJob = null
        scrollConsumerJob?.cancel()
        scrollConsumerJob = null
    }

    /**
     * Submit a scroll/drag delta (screen coordinates) for rendering. Non-blocking: the
     * delta is accumulated and consumed by [startScrollConsumer]; bursts coalesce automatically.
     */
    fun requestScroll(delta: Offset) {
        if (delta == Offset.Zero) return
        if (!page.isTransformationAllowed) return
        pendingScroll.update { it + delta }
    }

    /**
     * Single consumer that drains [pendingScroll] and performs the actual bitmap shift.
     * Draining atomically resets the accumulator to zero, so any input that arrives while
     * a render is in flight piles onto a fresh zero and is picked up on the next pass —
     * coalescing a flood of touch samples into one shift per render.
     */
    private fun startScrollConsumer() {
        if (scrollConsumerJob?.isActive == true) return
        scrollConsumerJob = scope.launch(Dispatchers.Main.immediate) {
            pendingScroll.collect {
                val delta = pendingScroll.getAndUpdate { Offset.Zero }
                if (delta == Offset.Zero) return@collect
                scrollInProgress.withLock {
                    if (viewModel.toolbarState.value.mode == Mode.Select &&
                        viewModel.selectionState.firstPageCut != null
                    ) {
                        onOpenPageCut(delta / page.zoomLevel.value)
                    } else {
                        onPageScroll(-delta)
                    }
                }
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
        }
    }


    fun setIsDrawing(value: Boolean) {
        if (viewModel.toolbarState.value.isDrawing == value) {
            logEditorControlTower.w("IsDrawing already set to $value")
            return
        }
        scope.launch { CanvasEventBus.isDrawing.emit(value) }
    }

    fun toggleTool() {
        val mode = viewModel.toolbarState.value.mode
        viewModel.onToolbarAction(ToolbarAction.ChangeMode(if (mode == Mode.Draw) Mode.Erase else Mode.Draw))
    }

    // Toggle the lasso: activate Select, or if already in Select return to the tool
    // that was active before.
    private var modeBeforeLasso: Mode = Mode.Draw
    fun toggleLassoMode() {
        val mode = viewModel.toolbarState.value.mode
        if (mode == Mode.Select) {
            viewModel.onToolbarAction(ToolbarAction.ChangeMode(modeBeforeLasso))
        } else {
            modeBeforeLasso = mode
            viewModel.onToolbarAction(ToolbarAction.ChangeMode(Mode.Select))
        }
    }

    fun toggleZen() {
        viewModel.onToolbarAction(ToolbarAction.ToggleToolbar)
    }

    fun getSnapshotOfSelectionState(): SelectionState {
        return viewModel.selectionState
    }

    fun getSelectedBitmap(): Bitmap {
        return requireNotNull(viewModel.selectionState.selectedBitmap)
    }

    fun goToNextPage() {
        logEditorControlTower.i("Going to next page")
        viewModel.goToNextPage()
        history.cleanHistory()
    }

    fun goToPreviousPage() {
        logEditorControlTower.i("Going to previous page")
        viewModel.goToPreviousPage()
        history.cleanHistory()
    }

    fun undo() {
        scope.launch {
            logEditorControlTower.i("Undo called")
            history.undo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun redo() {
        scope.launch {
            logEditorControlTower.i("Redo called")
            history.redo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun onPinchToZoom(delta: Float, center: Offset?) {
        if (!page.isTransformationAllowed) return
        if (viewModel.toolbarState.value.mode == Mode.Select)
            return
        scope.launch {
            scrollInProgress.withLock {
                if (GlobalAppSettings.current.simpleRendering || !GlobalAppSettings.current.continuousZoom)
                    page.simpleUpdateZoom(delta)
                else
                    page.updateZoom(delta, center)
            }
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    fun resetZoomAndScroll() {
        scope.launch {
            page.scroll = Offset(0f, page.scroll.y)
            page.applyZoomAndRedraw(1f)
            // Request UI update
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    /** Set an absolute zoom level, keeping the current view center anchored. */
    fun setZoom(level: Float) {
        scope.launch {
            val old = page.zoomLevel.value
            if (old == level) return@launch
            // Page point currently under the view center (screen = (page - scroll) * zoom).
            val cx = page.viewWidth / 2f
            val cy = page.viewHeight / 2f
            val pageX = cx / old + page.scroll.x
            val pageY = cy / old + page.scroll.y
            // New scroll that keeps that page point under the center at the new zoom.
            val newScrollX = (pageX - cx / level).coerceAtLeast(0f)
            val newScrollY = (pageY - cy / level).coerceAtLeast(0f)
            page.scroll = Offset(newScrollX, newScrollY)
            page.applyZoomAndRedraw(level)
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    /** Fit the page width to the visible view; keeps vertical position, resets horizontal. */
    fun zoomFitWidth() {
        scope.launch {
            val pageW = min(SCREEN_WIDTH, SCREEN_HEIGHT).toFloat()
            val fit = page.viewWidth / pageW
            page.scroll = Offset(0f, page.scroll.y)
            page.applyZoomAndRedraw(fit)
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    private fun onOpenPageCut(offset: Offset) {
        val cutLine = viewModel.selectionState.firstPageCut ?: return
        val result = page.applyPageCutOffset(cutLine, offset) ?: return

        // commit to history
        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(result.movedStrokes.map { it.id }),
                Operation.AddStroke(result.previousStrokes)
            )
        )

        viewModel.selectionState.reset()
    }

    private suspend fun onPageScroll(dragDelta: Offset) {
        // Continuous view: smooth scroll through the discrete-page stack, no snap.
        if (page.isContinuous) {
            page.continuousScrollBy(dragDelta)
            return
        }
        // Paginated ("snap") mode: a vertical scroll that would cross the fixed
        // page boundary becomes a page change instead -- scroll == swipe. The
        // page-position decision is a pure function of scroll vs. the page's
        // fixed height, so continuous rendering can later reuse it.
        val maxScrollY = page.paginatedMaxScrollY()
        if (maxScrollY != null) {
            val zoom = page.zoomLevel.value
            val newScrollY = page.scroll.y + dragDelta.y / zoom
            val crossingDown = newScrollY > maxScrollY + 1f
            val crossingUp = newScrollY < -1f
            if (crossingDown || crossingUp) {
                // one page turn per drag gesture (see lastSnapAtMs)
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastSnapAtMs < snapCooldownMs) return
                lastSnapAtMs = now
                if (crossingDown) viewModel.snapToNextPage()
                else viewModel.snapToPreviousPage(maxScrollY)
                return
            }
        }
        // scroll is in Page coordinates
        if (GlobalAppSettings.current.simpleRendering)
            page.simpleUpdateScroll(dragDelta)
        else
            page.updateScroll(dragDelta)
    }


    // A stylus selection drag ended. Unlike a finger, the moving selection can't repaint live
    // under the pen (raw drawing is off during a selection), and the floating overlay won't show
    // until something kicks the EPD. So on lift we commit the displacement (writes the strokes
    // into the page at the new spot, minting fresh ids + streaming) and then immediately
    // RE-SELECT them there via the same page-region redraw the initial lasso uses -- which shows
    // reliably after the pen lifts. Net effect: the strokes land at the new position and stay
    // selected, so the pen (or a finger) can drag them again; a tap outside then dismisses.
    fun finishStylusSelectionDrag() {
        // The proven finish (same as a plain deselect): commit the move, clear the selection,
        // return to drawing mode. reset()'s setAnimationMode(false) full refresh shows the
        // strokes at their new position, and setIsDrawing(true) drives the isDrawing false->true
        // transition -- the floating overlay can't repaint live under the pen, so we rely on this
        // firmware-level refresh path.
        val result = viewModel.selectionState.applySelectionDisplaceAndCommit(page, history)
        viewModel.selectionState.reset()
        setIsDrawing(true)
        scope.launch { CanvasEventBus.refreshUi.emit(Unit) }

        if (result == null || (result.strokes.isEmpty() && result.images.isEmpty())) return

        // Then re-select the moved strokes so the pen can drag them again. This is POSTED after a
        // short settle on purpose: the pen-up refresh above must land, and the isDrawing
        // true->false transition (which fires the updateIsDrawing() panel kick via the
        // distinctUntilChanged observer) must complete, BEFORE selectImagesAndStrokes puts the
        // panel back into selection/animation mode. Done synchronously, the transition conflates
        // and the re-selected overlay renders at the old position until the next tap.
        scope.launch(Dispatchers.Main.immediate) {
            delay(STYLUS_RESELECT_SETTLE_MS)
            selectImagesAndStrokes(
                scope = scope,
                page = page,
                viewModel = viewModel,
                imagesToSelect = result.images,
                strokesToSelect = result.strokes
            )
        }
    }

    // when selection is moved, we need to redraw canvas
    fun applySelectionDisplace() {
        viewModel.selectionState.applySelectionDisplaceAndCommit(page, history)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun deleteSelection() {
        viewModel.selectionState.deleteSelectionAndCommit(page, history)
        setIsDrawing(true)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun changeSizeOfSelection(scale: Int) {
        if (!viewModel.selectionState.selectedImages.isNullOrEmpty())
            viewModel.selectionState.resizeImages(scale, page)
        if (!viewModel.selectionState.selectedStrokes.isNullOrEmpty())
            viewModel.selectionState.resizeStrokes(scale, scope, page)
        // Emit a refresh signal to update UI
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    /** Last custom stroke width (for the selection set-width popup's C modal). */
    fun lastCustomWidth(): Float = viewModel.toolbarState.value.lastCustomWidth

    /** Dominant stroke width in the current selection, or null. */
    fun selectionDominantWidth(): Float? =
        viewModel.selectionState.selectedStrokes
            ?.groupingBy { it.size }?.eachCount()?.maxByOrNull { it.value }?.key

    /** Set the stroke width of every selected stroke (undoable). */
    fun setSelectionStrokeWidth(width: Float, isCustom: Boolean) {
        val sel = viewModel.selectionState.selectedStrokes
        if (sel.isNullOrEmpty()) { showHint("No strokes selected"); return }
        val updated = sel.map { com.ethran.notable.editor.utils.setStrokeWidth(it, width) }
        val newStrokes = page.updateStrokes(updated) // fresh ids (immutable edit)
        viewModel.selectionState.selectedStrokes = newStrokes // keep snapshot current
        if (isCustom) viewModel.noteCustomWidth(width)
        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(newStrokes.map { it.id }),
                Operation.AddStroke(sel) // undo restores the original strokes (old ids + widths)
            )
        )
        // Apply, then dismiss the selection and return to the drawing tool so the width
        // takes effect immediately -- no extra tap-outside needed.
        val label = com.ethran.notable.editor.utils.widthLabel(width)
        deselectAndRestoreTool()
        scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
        showHint("Stroke width set to $label")
    }

    /** Commit any pending move, clear the selection, and restore the pre-lasso tool. */
    fun deselectAndRestoreTool() {
        applySelectionDisplace()                 // no-op rewrite is skipped when unmoved
        viewModel.selectionState.reset()
        viewModel.onToolbarAction(ToolbarAction.ChangeMode(modeBeforeLasso))
        setIsDrawing(true)
    }

    /**
     * Adopt the dominant stroke width of the selection as the current pen width.
     * If it isn't a preset it shows as "C" (custom) and becomes the last custom width.
     */
    fun getSelectionStrokeWidth() {
        val sel = viewModel.selectionState.selectedStrokes
        if (sel.isNullOrEmpty()) {
            showHint("No strokes selected")
            return
        }
        val dominant = sel.groupingBy { it.size }.eachCount()
            .maxByOrNull { it.value }?.key ?: return
        val label = com.ethran.notable.editor.utils.widthLabel(dominant)
        viewModel.applyPenWidth(
            dominant,
            isCustom = !com.ethran.notable.editor.utils.isPresetWidth(dominant)
        )
        // Deselect and return to the drawing tool so the user can immediately draw
        // with the picked-up width -- no extra tap-outside needed.
        deselectAndRestoreTool()
        scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
        showHint("Pen width set to $label")
    }

    fun duplicateSelection(overlapOriginal: Boolean = false) {
        // finish ongoing movement
        applySelectionDisplace()
        viewModel.selectionState.duplicateSelection()
        // Under the stylus the floating copy can't be shown (only committed page content paints),
        // so the user grabs it blindly and drags it into place (it commits + appears on lift).
        // Placing the copy directly on top of the original means they grab exactly the strokes
        // they can see. (Finger keeps the default +50 offset so the copy is visibly separate.)
        if (overlapOriginal) {
            viewModel.selectionState.selectionDisplaceOffset = IntOffset.Zero
        }
    }

    fun cutSelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
        deleteSelection()
        showHint("Content cut to clipboard")
    }

    fun copySelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
    }


    fun pasteFromClipboard() {
        // finish ongoing movement
        applySelectionDisplace()

        val (strokes, images) = clipboardStore.get() ?: return

        val now = Date()
        val scrollPos = page.scroll

        val pastedStrokes = strokes.map {
            offsetStroke(it, offset = scrollPos).copy(
                // change the pasted strokes' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        val pastedImages = images.map {
            it.copy(
                // change the pasted images' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                x = it.x + scrollPos.x.toInt(),
                y = it.y + scrollPos.y.toInt(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        selectImagesAndStrokes(
            scope = scope,
            page = page,
            viewModel = viewModel,
            imagesToSelect = pastedImages,
            strokesToSelect = pastedStrokes
        )
        viewModel.selectionState.placementMode = PlacementMode.Paste

        showHint("Pasted content from clipboard")
    }

    fun showHint(text: String) = viewModel.showHint(text)
}
