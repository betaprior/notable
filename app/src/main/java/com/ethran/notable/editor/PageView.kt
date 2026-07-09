package com.ethran.notable.editor


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.pageWidthPt
import java.util.UUID
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.canvas.CanvasEventBus.drawingInProgress
import com.ethran.notable.editor.canvas.CanvasEventBus.waitForDrawing
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.drawOnCanvasFromPage
import com.ethran.notable.editor.drawing.drawStroke
import com.ethran.notable.editor.utils.offsetImage
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.divideStrokesFromCut
import com.ethran.notable.editor.utils.loadHQPagePreview
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.editor.utils.times
import com.ethran.notable.editor.utils.toIntOffset
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.utils.onError
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

const val OVERLAP = 2

data class PageCutMoveResult(
    val previousStrokes: List<Stroke>,
    val movedStrokes: List<Stroke>,
)

/**
 * Manages the state and rendering of a single page within the editor.
 * It delegates task to PageDataManager, which is responsible for loading data from db,
 * and caching it.
 */
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val pageDataManager: PageDataManager,
    val initialPageId: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState,
) {
    // TODO: unify width height variable

    val log = ShipBook.getLogger("PageView")
    private val logCache = ShipBook.getLogger("PageViewCache")

    // Live ink streaming: mirror stroke deletions to a connected receiver.
    private val inkStream by lazy {
        com.ethran.notable.ink.InkStreamClient.from(context)
    }

    private var loadingJob: Job? = null

    @Volatile
    var windowedBitmap = createBitmap(viewWidth, viewHeight)
        private set

    @Volatile
    var windowedCanvas = Canvas(windowedBitmap)
        private set

    // Spare screen-sized buffer reused by updateScroll to avoid allocating a new
    // bitmap on every scroll event. Ping-ponged with windowedBitmap; recreated only
    // when the canvas size/config changes (zoom, dimension change, page switch).
    private var scrollBackBuffer: Bitmap? = null

    //    var strokes = listOf<Stroke>()
    var strokes: List<Stroke>
        get() = pageDataManager.getStrokes(currentPageId)
        set(value) = pageDataManager.setStrokes(currentPageId, value)

    val strokesById: HashMap<String, Stroke>
        get() = pageDataManager.getStrokesById(currentPageId)

    var images: List<Image>
        get() = pageDataManager.getImages(currentPageId)
        set(value) = pageDataManager.setImages(currentPageId, value)

    // warning: The setter is delayed!
    private var currentBackground: CachedBackground
        get() = pageDataManager.getCurrentBackground()
        set(value) = pageDataManager.setCurrentBackground(value)

    val currentPageId: String
        get() = pageDataManager.getCurrentPageId()


    // scroll is observed by ui, represents top left corner
    var scroll: Offset
        get() = pageDataManager.getPageScroll(currentPageId)
        set(value) = pageDataManager.setPageScroll(currentPageId, value)


    val isTransformationAllowed: Boolean
        get() = pageDataManager.isTransformationAllowedForCurrentPage()


    // we need to observe zoom level, to adjust strokes size.
    val zoomLevel: MutableStateFlow<Float> =
        MutableStateFlow(pageDataManager.getPageZoom(currentPageId))

    // Paginated ("snap") mode -- set from the notebook's fixedPageHeightPt once
    // it is known (see EditorView). null = legacy growable page. Single source of
    // truth for pagination in the editor; the scroll boundary and height both
    // derive from it, so continuous rendering can later reuse the same value.
    @Volatile
    var fixedPageHeightPt: Int? = null

    val isPaginated: Boolean get() = fixedPageHeightPt != null

    /** One fixed page's height in page (unzoomed) coordinates, or null in legacy mode. */
    val paginatedPageHeightPx: Float?
        get() = fixedPageHeightPt?.let { it.toFloat() * SCREEN_WIDTH / pageWidthPt }

    /**
     * Max within-page scroll before crossing to the next page (page coords).
     * null in legacy mode (unbounded growth). At zoom 1 a fixed page ≈ one
     * screen, so this is ~0 and any downward scroll crosses -- i.e. scroll==swipe.
     */
    fun paginatedMaxScrollY(): Float? =
        paginatedPageHeightPx?.let { maxOf(0f, it - viewHeight / zoomLevel.value) }

    var height: Int
        get() = paginatedPageHeightPx?.toInt() ?: pageDataManager.getPageHeight(currentPageId) ?: viewHeight
        set(value) {
            if (isPaginated) return // fixed-height pages don't grow
            pageDataManager.setPageHeight(currentPageId, value)
        }

    // --- Continuous view of discrete pages (xournal-style) ---------------------
    // Pushed from EditorView like fixedPageHeightPt. Only meaningful when
    // paginated. When off, snap mode is unchanged. The helpers below are pure
    // (doc-absolute Y over the page+gap stack) and safe to reference anywhere;
    // the render/scroll swap that USES them is a later (on-device) phase, gated
    // by isContinuous so legacy + snap paths are untouched until then.
    @Volatile
    var continuousScroll: Boolean = false

    /** Doc-absolute scroll position (page coords, top of the viewport) in
     *  continuous mode. Distinct from the per-page [scroll]. */
    @Volatile
    var continuousScrollY: Float = 0f

    /** Notebook-level horizontal pan (page coords) in continuous mode. Kept off
     *  the per-page [scroll] so it survives the current-page anchor advancing. */
    @Volatile
    var continuousScrollX: Float = 0f

    /** Ordered pageIds of the notebook, pushed from EditorView -- lets the
     *  compositor map a page index to its (cached) strokes synchronously. */
    @Volatile
    var continuousPageIds: List<String> = emptyList()

    private val continuousPageLabelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(0x99, 0x99, 0x99)
        textSize = 28f
        isAntiAlias = true
    }

    /** Continuous view: ids of strokes/images "lifted" into the floating selection
     *  overlay -- the compositor SKIPS them so they don't double-draw under the
     *  floating bitmap. Cleared on commit/deselect via [clearContinuousSelection]. */
    @Volatile
    var continuousSelectedStrokeIds: Set<String> = emptySet()

    @Volatile
    var continuousSelectedImageIds: Set<String> = emptySet()

    /** Drop the lifted-selection ids and repaint (the strokes/images, now committed
     *  or restored, draw normally again). */
    fun clearContinuousSelection() {
        if (continuousSelectedStrokeIds.isEmpty() && continuousSelectedImageIds.isEmpty()) return
        continuousSelectedStrokeIds = emptySet()
        continuousSelectedImageIds = emptySet()
        coroutineScope.launch(Dispatchers.Main) { CanvasEventBus.forceUpdate.emit(null) }
    }

    /** Index of the page occupying the MAJORITY of the viewport (drives the
     *  toolbar page indicator). Observed by EditorView. */
    val continuousCurrentPageIndex = MutableStateFlow(0)

    /** Page index with the largest overlap with the current viewport -- cheap
     *  (loops only the 1-3 visible pages). */
    fun computeMajorityPageIndex(): Int {
        val stride = continuousPageStridePx ?: return 0
        val pageH = paginatedPageHeightPx ?: return 0
        val top = continuousScrollY
        val bottom = top + viewHeight / zoomLevel.value
        var bestIdx = docYToPageIndex(top)
        var bestOverlap = -1f
        for (idx in docYToPageIndex(top)..docYToPageIndex(bottom)) {
            val pTop = idx * stride
            val overlap = minOf(bottom, pTop + pageH) - maxOf(top, pTop)
            if (overlap > bestOverlap) { bestOverlap = overlap; bestIdx = idx }
        }
        return bestIdx.coerceIn(0, (continuousPageIds.size - 1).coerceAtLeast(0))
    }

    /** True when this notebook renders as a continuous stack of discrete pages. */
    val isContinuous: Boolean get() = isPaginated && continuousScroll

    /** Gap between stacked pages, in page (unzoomed) coords -- scaled like page
     *  height. ~24pt to echo xournal's continuous-view separation (tune later). */
    val continuousPageGapPx: Float get() = 24f * SCREEN_WIDTH / pageWidthPt

    /** Vertical stride from one page's top to the next (page height + gap). */
    val continuousPageStridePx: Float?
        get() = paginatedPageHeightPx?.let { it + continuousPageGapPx }

    /** Doc-absolute y of page [index]'s top, in page coords. */
    fun pageTopDocY(index: Int): Float = continuousPageStridePx?.let { index * it } ?: 0f

    /** Total document height across [pageCount] pages + gaps (page coords), or
     *  null when not continuous. Trailing gap after the last page is excluded. */
    fun totalDocHeightPx(pageCount: Int): Float? =
        continuousPageStridePx?.let { stride -> maxOf(0f, pageCount * stride - continuousPageGapPx) }

    /** Page index whose band contains doc-absolute [docY] (a point in a gap maps
     *  to the page above it -- the gap is dead space for input). */
    fun docYToPageIndex(docY: Float): Int =
        continuousPageStridePx?.let { (docY / it).toInt().coerceAtLeast(0) } ?: 0

    /** Page-local y for doc-absolute [docY] within its page (negative => in the
     *  gap below that page). */
    fun docYToLocalY(docY: Float): Float =
        continuousPageStridePx?.let { docY - docYToPageIndex(docY) * it } ?: docY


//    private var dbStrokes = appRepository.strokeRepository
//    private var dbImages = appRepository.imageRepository

    val currentPageNumber: Int
        get() = pageDataManager.getCurrentPageNumber()

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        log.i("getOrLoadBackground")
        val cached = currentBackground
        if (cached.matches(filePath, pageNumber, scale)) {
            log.i("Background bitmap (cached): ${cached.bitmap}")
            return cached.bitmap
        }
        // 0.1 to avoid constant rerender on zoom.
        val newBackground = CachedBackground(filePath, pageNumber, scale + 0.1f)
        currentBackground = newBackground
        log.i("Background bitmap: ${newBackground.bitmap}")
        return newBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        coroutineScope.launch(Dispatchers.IO) {
            // set page, and retrieve page data from db
            pageDataManager.setPage(initialPageId)
            log.i("PageView init with initial pageId: $initialPageId" )
            if(currentPageId.isEmpty())
                log.e("Current page id is empty")

            zoomLevel.value = pageDataManager.getPageZoom(currentPageId)
            pageDataManager.getCachedBitmap(currentPageId)?.let { cached ->
                log.i("PageView: using cached bitmap")
                windowedBitmap = cached
                windowedCanvas = Canvas(windowedBitmap)
            } ?: run {
                log.i("PageView.init: creating new bitmap")
                recreateCanvas()
                pageDataManager.cacheBitmap(currentPageId, windowedBitmap)
            }

            coroutineScope.launch(Dispatchers.Main) {
                // If we do it with main.immediate then it wont work.
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
            loadPage()
            log.d("Page loaded (Init with id: $currentPageId)")
            pageDataManager.collectAndPersistBitmapsBatch(context, coroutineScope)
        }
    }

    /**
     * Switches the `PageView` to display a different page.
     * **It doesn't notify the UI about the change.**
     *
     * This function handles the entire process of transitioning from the current page to a new one specified by `newPageId`.
     * It performs the following steps:
     * 1.  Saves the state of the old page, including persisting its bitmap representation to disk.
     * 2.  Updates the internal `currentPageId` to `newPageId`.
     * 3.  Fetches the new page's data from the repository.
     * 4.  Updates the `pageDataManager` to the new page context.
     * 5.  Restores the zoom level for the new page.
     * 6.  Attempts to load a cached bitmap for the new page. If a cached bitmap exists and its dimensions
     *     match the current view, it's used directly. If dimensions differ, the canvas is resized.
     * 7.  If no cached bitmap is available, it creates a new bitmap and canvas from scratch.
     * 8.  Launches a coroutine to load the page's content (strokes, images) asynchronously and refreshes the UI.
     *
     * @param newPageId The unique identifier of the page to switch to.
     */
    fun changePage(newPageId: String) {
        val oldId = currentPageId
        // Zoom is notebook-global: carry the current zoom to the new page instead
        // of resetting to that page's stored zoom.
        val keepZoom = zoomLevel.value
        log.d("changePage Entry: $oldId -> $newPageId (keepZoom=$keepZoom)")

        coroutineScope.launch(Dispatchers.IO) {
            if (isContinuous) {
                // Compositor owns the zoom-scaled viewport canvas. A page turn here
                // is only a data/current-page update (the ShiftContinuousViewport
                // command already moved the viewport). Recreating/swapping a
                // single-page bitmap would drop the zoom scale -> partial render.
                pageDataManager.setPage(newPageId)
                zoomLevel.value = keepZoom
                pageDataManager.setPageZoom(currentPageId, keepZoom)
                ensureContinuousPagesLoaded()
                return@launch
            }
            pageDataManager.onExit(oldId, windowedBitmap, coroutineScope)
            pageDataManager.setPage(newPageId)
            zoomLevel.value = keepZoom
            pageDataManager.setPageZoom(currentPageId, keepZoom)
            // A cached bitmap may have been rendered at a different zoom; when
            // zoomed, re-render fresh so the new page shows at the correct scale.
            val cached = if (keepZoom == 1f) pageDataManager.getCachedBitmap(newPageId) else null
            cached?.let {
                log.i("PageView: using cached bitmap")
                windowedBitmap = it
                windowedCanvas = Canvas(windowedBitmap)
                // Check if we have correct size of canvas
                if (windowedCanvas.width != viewWidth || windowedCanvas.height != viewHeight)
                    updateCanvasDimensions()
            } ?: run {
                log.i("PageView.changePage: creating new bitmap")
                recreateCanvas()
                pageDataManager.cacheBitmap(newPageId, windowedBitmap)
            }

            log.d("New bitmap hash: ${windowedBitmap.hashCode()}, ID: $currentPageId")

            // Refresh UI without waiting for drawing.
            // TODO: Problem: Sometimes refreshUi had a problem with proper refreshing screen,
            //  using function that does not wait for drawing mostly solved the problem.
            //  but there might be still bugs with it.
            CanvasEventBus.refreshUiImmediately.emit(Unit)
            loadPage()
            log.d("Page loaded (updatePageID($currentPageId))")
        }
    }

    private fun recreateCanvas() {
        windowedBitmap = createBitmap(viewWidth, viewHeight)
        windowedCanvas = Canvas(windowedBitmap)
        loadInitialBitmap()
    }

    /*
        Cancel loading strokes, and save bitmap to disk
    */
    fun disposeOldPage() {
        log.d("Dispose old page")
        pageDataManager.onExit(currentPageId, windowedBitmap, coroutineScope)
        cleanJob()
    }


    // To be removed.
    private fun redrawAll(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main.immediate) {
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            drawAreaScreenCoordinates(viewRectangle)
        }
    }

    private fun loadPage() {
//        loadingJob?.cancel()
        logCache.i("Init from persist layer, pageId: $currentPageId")
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                snackManager.showSnackDuring(text = "Loading strokes...") {
                    val timeToLoad = measureTimeMillis {
                        logCache.d("Start page loading, id $currentPageId")
                        pageDataManager.requestCurrentPageLoadJoin()
                        logCache.d("Got page data (PageView.loadPage). id $currentPageId")
                    }
                    logCache.d("All strokes loaded in $timeToLoad ms")
                }
                // TODO: If we put it in loadPage(…) sometimes it will try to refresh
                //  without seeing strokes, I have no idea why.
                coroutineScope.launch(Dispatchers.Main) {
//                    delay(100)
                    CanvasEventBus.forceUpdate.emit(null)
                }
//                sleep(5000)

                logCache.d("Loaded page from persistent layer $currentPageId")
                if (!pageDataManager.validatePageDataLoaded(currentPageId))
                    logCache.e("Page should be loaded, but it is not. $currentPageId")
                coroutineScope.launch(Dispatchers.Default) {
                    delay(10)
                    pageDataManager.reduceCache(20)
                    pageDataManager.cacheNeighbors()
                }
//                sleep(10000)

            } catch (_: CancellationException) {
                val dataStatus = pageDataManager.validatePageDataLoaded(currentPageId)
                logCache.d("Page loading cancelled, data was loaded correctly: $dataStatus")
            } catch (e: Exception) {
                val dataStatus = pageDataManager.validatePageDataLoaded(currentPageId)
                logCache.e("Page loading cancelled, data was loaded correctly: $dataStatus", e)
            }
        }
    }


    /**
     * @param alreadyStreamed true when the caller already mirrored these strokes to a live
     *   receiver via the per-point stream (the live-draw path). Kept for call-site clarity;
     *   streaming no longer branches on it (the committed stroke is always re-sent complete).
     */
    fun addStrokes(strokesToAdd: List<Stroke>, alreadyStreamed: Boolean = false) {
        strokes += strokesToAdd
        updateHeightForChange(strokesToAdd)

        saveStrokesToPersistLayer(strokesToAdd)
        pageDataManager.indexStrokes(coroutineScope, currentPageId)
        // Always stream the authoritative complete stroke on commit, live-drawn or not:
        // STROKE_BEGIN for a known id makes the receiver replace its rendering, so this
        // self-heals live datagrams lost in flight (a lost BEGIN would otherwise lose the
        // whole stroke; lost POINTS would leave permanent gaps).
        inkStream.streamCompleteStrokes(strokesToAdd, streamPageIndex())

//        persistBitmapDebounced()
    }

    /** Continuous view: commit [strokesToAdd] to a SPECIFIC page (the one under the
     *  pen), not the current page. Points must already be that page's LOCAL coords
     *  and each stroke's pageId must be [pageId]. Streams to [pageIndex] and
     *  repaints the viewport. */
    fun addStrokesToPage(pageId: String, pageIndex: Int, strokesToAdd: List<Stroke>) {
        pageDataManager.setStrokes(pageId, pageDataManager.getStrokes(pageId) + strokesToAdd)
        saveStrokesToPersistLayer(strokesToAdd)          // DB row keyed by stroke.pageId
        pageDataManager.indexStrokes(coroutineScope, pageId)
        inkStream.streamCompleteStrokes(strokesToAdd, pageIndex)
        // INCREMENTAL draw (matches the snap path -- NOT a full forceUpdate, which
        // recomposites the whole viewport + does a full e-ink refresh per stroke =>
        // lag). Draw just the new strokes onto the bitmap at their page position,
        // clipped to the page; the firmware raw-ink already shows them live and the
        // bitmap catches up silently on the next settle.
        val stride = continuousPageStridePx ?: return
        val pageH = paginatedPageHeightPx ?: return
        val zoom = zoomLevel.value
        val yOff = pageIndex * stride - continuousScrollY
        windowedCanvas.save()
        windowedCanvas.clipRect(0f, yOff, viewWidth / zoom, yOff + pageH)
        val off = Offset(-continuousScrollX, yOff)
        strokesToAdd.forEach { drawStroke(windowedCanvas, it, off) }
        windowedCanvas.restore()
    }

    // --- Continuous-view undo/redo: operate by each stroke's OWN pageId (not the
    // current page), since strokes can live on any page. Full repaint at the end
    // (undo/redo is infrequent, so a compositor redraw is fine). ---

    /** Re-add [strokes] to their own pages (undo of a delete / redo of an add). */
    fun addStrokesToOwnPages(strokes: List<Stroke>) {
        strokes.groupBy { it.pageId }.forEach { (pid, group) ->
            pageDataManager.setStrokes(pid, pageDataManager.getStrokes(pid) + group)
            saveStrokesToPersistLayer(group)
            pageDataManager.indexStrokes(coroutineScope, pid)
            val idx = continuousPageIds.indexOf(pid)
            if (idx >= 0) inkStream.streamCompleteStrokes(group, idx)
        }
        coroutineScope.launch(Dispatchers.Main) { CanvasEventBus.forceUpdate.emit(null) }
    }

    /** Remove strokes [ids] from their own pages; returns the removed strokes so
     *  the inverse (re-add) can be recorded. */
    fun removeStrokesFromOwnPages(ids: List<String>): List<Stroke> {
        val idSet = ids.toSet()
        val removed = continuousPageIds.flatMap { pid ->
            pageDataManager.getStrokes(pid).filter { it.id in idSet }
        }
        removed.groupBy { it.pageId }.forEach { (pid, group) ->
            pageDataManager.setStrokes(pid, pageDataManager.getStrokes(pid).filter { it.id !in idSet })
            pageDataManager.indexStrokes(coroutineScope, pid)
        }
        removeStrokesFromPersistLayer(ids)
        inkStream.deleteStrokes(ids)
        coroutineScope.launch(Dispatchers.Main) { CanvasEventBus.forceUpdate.emit(null) }
        return removed
    }

    /** Re-add [imagesToAdd] to their own pages (undo/redo). Images aren't streamed. */
    fun addImagesToOwnPages(imagesToAdd: List<Image>) {
        imagesToAdd.groupBy { it.pageId }.forEach { (pid, group) ->
            pageDataManager.setImages(pid, pageDataManager.getImages(pid) + group)
            saveImagesToPersistLayer(group)
        }
        coroutineScope.launch(Dispatchers.Main) { CanvasEventBus.forceUpdate.emit(null) }
    }

    /** Remove images [ids] from their own pages; returns them for the inverse op. */
    fun removeImagesFromOwnPages(ids: List<String>): List<Image> {
        val idSet = ids.toSet()
        val removed = continuousPageIds.flatMap { pid ->
            pageDataManager.getImages(pid).filter { it.id in idSet }
        }
        removed.groupBy { it.pageId }.forEach { (pid, _) ->
            pageDataManager.setImages(pid, pageDataManager.getImages(pid).filter { it.id !in idSet })
        }
        removeImagesFromPersistLayer(ids)
        coroutineScope.launch(Dispatchers.Main) { CanvasEventBus.forceUpdate.emit(null) }
        return removed
    }

    /** Continuous view: immutable-edit update (move / re-width) of strokes that may
     *  live on different pages. Each incoming stroke carries its OLD id with new
     *  properties; it gets a fresh id, applied in place on its own page (same rowid
     *  -> draw order preserved), mirrored as delete(old)+add(new) to the stream.
     *  Returns the new-id strokes (for history + the selection snapshot). */
    fun updateStrokesOnOwnPages(strokesToUpdate: List<Stroke>): List<Stroke> {
        val newStrokes = mutableListOf<Stroke>()
        strokesToUpdate.groupBy { it.pageId }.forEach { (pid, group) ->
            val pairs = group.map { it.id to it.copy(id = UUID.randomUUID().toString()) }
            val newByOldId = pairs.associate { (oldId, s) -> oldId to s }
            pageDataManager.setStrokes(
                pid, pageDataManager.getStrokes(pid).map { newByOldId[it.id] ?: it }
            )
            pageDataManager.updateStrokeIdsInDb(pairs)
            pageDataManager.indexStrokes(coroutineScope, pid)
            inkStream.deleteStrokes(pairs.map { it.first }) // old ids; repeats safe (disjoint)
            val idx = continuousPageIds.indexOf(pid)
            if (idx >= 0) inkStream.streamCompleteStrokes(pairs.map { it.second }, idx)
            newStrokes += pairs.map { it.second }
        }
        coroutineScope.launch(Dispatchers.Main) { CanvasEventBus.forceUpdate.emit(null) }
        return newStrokes
    }

    /** Continuous view: commit a selection MOVE/PASTE that may cross page
     *  boundaries. [strokes] are the source strokes (own pageId + page-local
     *  coords); [offset] is the doc-space drag (page units). Each stroke is
     *  re-assigned to the page its moved CENTRE lands on (whole-stroke -- no
     *  crossing), rebased to that page's local coords with a fresh id, and
     *  streamed. When [removeOriginals] (a move), the source strokes are first
     *  removed from their pages. Returns the placed strokes. */
    fun placeStrokesContinuous(
        strokes: List<Stroke>, offset: Offset, removeOriginals: Boolean
    ): List<Stroke> {
        if (strokes.isEmpty()) return emptyList()
        if (removeOriginals) removeStrokesFromOwnPages(strokes.map { it.id })
        val n = continuousPageIds.size
        val lastIdx = maxOf(0, n - 1)
        val placed = strokes.map { s ->
            val origIdx = continuousPageIds.indexOf(s.pageId).coerceIn(0, lastIdx)
            val origTop = pageTopDocY(origIdx)
            val centreDocY = (s.top + s.bottom) / 2f + origTop + offset.y
            val newIdx = docYToPageIndex(centreDocY).coerceIn(0, lastIdx)
            val dy = origTop + offset.y - pageTopDocY(newIdx)
            offsetStroke(s, Offset(offset.x, dy)).copy(
                id = UUID.randomUUID().toString(),
                pageId = continuousPageIds[newIdx],
            )
        }
        addStrokesToOwnPages(placed)
        return placed
    }

    /** Image counterpart of [placeStrokesContinuous]. */
    fun placeImagesContinuous(
        images: List<Image>, offset: Offset, removeOriginals: Boolean
    ): List<Image> {
        if (images.isEmpty()) return emptyList()
        if (removeOriginals) removeImagesFromOwnPages(images.map { it.id })
        val n = continuousPageIds.size
        val lastIdx = maxOf(0, n - 1)
        val placed = images.map { im ->
            val origIdx = continuousPageIds.indexOf(im.pageId).coerceIn(0, lastIdx)
            val origTop = pageTopDocY(origIdx)
            val centreDocY = im.y + im.height / 2f + origTop + offset.y
            val newIdx = docYToPageIndex(centreDocY).coerceIn(0, lastIdx)
            val dy = origTop + offset.y - pageTopDocY(newIdx)
            offsetImage(im, Offset(offset.x, dy)).copy(
                id = UUID.randomUUID().toString(),
                pageId = continuousPageIds[newIdx],
            )
        }
        addImagesToOwnPages(placed)
        return placed
    }

    /** Continuous view: remove strokes from a SPECIFIC page (under the eraser),
     *  not the current page. Streams the deletes; the caller repaints. */
    fun removeStrokesFromPage(pageId: String, strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        pageDataManager.setStrokes(
            pageId, pageDataManager.getStrokes(pageId).filter { it.id !in strokeIds }
        )
        removeStrokesFromPersistLayer(strokeIds)   // DB delete by id
        pageDataManager.indexStrokes(coroutineScope, pageId)
        inkStream.deleteStrokes(strokeIds)
    }

    fun applyPageCutOffset(cutLine: List<SimplePointF>, offset: Offset): PageCutMoveResult? {
        if (offset.x < 0 || offset.y < 0) return null

        val (_, previousStrokes) = divideStrokesFromCut(strokes, cutLine)
        if (previousStrokes.isEmpty()) return null

        val movedStrokes = previousStrokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { point ->
                    point.copy(x = point.x + offset.x, y = point.y + offset.y)
                },
                top = stroke.top + offset.y,
                bottom = stroke.bottom + offset.y,
                left = stroke.left + offset.x,
                right = stroke.right + offset.x
            )
        }

        removeStrokes(strokeIds = previousStrokes.map { it.id })
        addStrokes(movedStrokes)
        drawAreaScreenCoordinates(strokeBounds(previousStrokes + movedStrokes))

        return PageCutMoveResult(
            previousStrokes = previousStrokes,
            movedStrokes = movedStrokes,
        )
    }

    /**
     * Immutable-edit update: each incoming stroke carries the OLD id with new
     * properties (move/resize/re-width). Each gets a FRESH id; the change is
     * applied in place (same rowid -> draw order + locality preserved) and
     * mirrored as delete(oldId) + add(newId). Because the ids are DISJOINT, the
     * delete's reliability repeats can't wipe the re-added stroke. Returns the
     * new-id strokes so callers can update history and the selection snapshot.
     */
    fun updateStrokes(strokesToUpdate: List<Stroke>): List<Stroke> {
        val pairs = strokesToUpdate.map { it.id to it.copy(id = UUID.randomUUID().toString()) }
        val newByOldId = pairs.associate { (oldId, s) -> oldId to s }
        val newStrokes = pairs.map { it.second }

        strokes = strokes.map { newByOldId[it.id] ?: it }
        updateHeightForChange(newStrokes)
        pageDataManager.updateStrokeIdsInDb(pairs)
        pageDataManager.indexStrokes(coroutineScope, currentPageId)
        inkStream.deleteStrokes(pairs.map { it.first }) // old ids; repeats are safe (disjoint)
        inkStream.streamCompleteStrokes(newStrokes, streamPageIndex())
        return newStrokes
    }

    /**
     * Which xournal page complete-stroke streaming should target. In paginated
     * mode each Notable page is one xournal page (page-local y), so it's the
     * current page index; in continuous mode the y-band mapping decides, so null.
     */
    private fun streamPageIndex(): Int? =
        if (isPaginated) currentPageNumber.coerceAtLeast(0) else null

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        pageDataManager.indexStrokes(coroutineScope, currentPageId)
        pageDataManager.recomputeHeight(currentPageId)
        // Mirror erases (eraser tool, scribble-erase, clear-all) to a live receiver.
        inkStream.deleteStrokes(strokeIds)

//        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return pageDataManager.getStrokes(strokeIds, currentPageId)
    }

    fun updateHeightForChange(strokesChanged: List<Stroke>) {
        if (isPaginated) return // fixed-height pages don't grow to fit strokes
        strokesChanged.forEach {
            val bottomPlusPadding = it.bottom + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding.toInt()
        }
    }

    private fun saveStrokesToPersistLayer(strokes: List<Stroke>) =
        pageDataManager.saveStrokesToDb(strokes)


    private fun saveImagesToPersistLayer(image: List<Image>) = pageDataManager.saveImagesToDb(image)


    fun addImage(imageToAdd: Image) {
        images += listOf(imageToAdd)
        val bottomPlusPadding = imageToAdd.x + imageToAdd.height + 50
        if (bottomPlusPadding > height) height = bottomPlusPadding

        saveImagesToPersistLayer(listOf(imageToAdd))
        pageDataManager.indexImages(coroutineScope, currentPageId)

//        persistBitmapDebounced()
    }

    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        imageToAdd.forEach {
            val bottomPlusPadding = it.x + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        saveImagesToPersistLayer(imageToAdd)
        pageDataManager.indexImages(coroutineScope, currentPageId)

//        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        pageDataManager.indexImages(coroutineScope, currentPageId)
        pageDataManager.recomputeHeight(currentPageId)
//        persistBitmapDebounced()
    }

    fun getImage(imageId: String): Image? = pageDataManager.getImage(imageId, currentPageId)
    fun getImages(imageIds: List<String>): List<Image?> =
        pageDataManager.getImages(imageIds, currentPageId)


    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) =
        pageDataManager.removeStrokesFromDb(strokeIds)

    private fun removeImagesFromPersistLayer(imageIds: List<String>) =
        pageDataManager.removeImagesFromDb(imageIds)

    // load background, fast, if it is accurate enough.
    private fun loadInitialBitmap(): Boolean {
        val bitmapFromDisc = loadHQPagePreview(
            context = context,
            pageID = currentPageId,
            scroll = scroll,
            zoom = zoomLevel.value,
            pageUpdatedAtMs = pageDataManager.pageFromDb?.updatedAt?.time,
            requireExactMatch = true,
        )
        if (bitmapFromDisc != null) {
            // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
            if (bitmapFromDisc.height == windowedCanvas.height && bitmapFromDisc.width == windowedCanvas.width) {
                windowedCanvas.drawBitmap(bitmapFromDisc, 0f, 0f, Paint())
                log.d("loaded initial bitmap, drawing to canvas: ${windowedCanvas.hashCode()}, bitmap: ${windowedBitmap.hashCode()}, page: $currentPageId")
                return true
            } else
                log.i("Image preview does not fit canvas area - redrawing")
        }

        log.d("Drawing initial background.")
        // draw just background.
        val backgroundType = pageDataManager.getBackgroundType()
        if (backgroundType == BackgroundType.Native) {
            drawBgToCanvas(null)
        } else
            windowedCanvas.drawColor(Color.WHITE)
        log.d("loaded initial bitmap, drawing to canvas: ${windowedCanvas.hashCode()}, bitmap: ${windowedBitmap.hashCode()}, page: $currentPageId")
        return false
    }


    private fun cleanJob() {
        //ensure that snack is canceled, even on dispose of the page.
        coroutineScope.launch(Dispatchers.IO) {
            pageDataManager.cancelLoadingPage(pageId = currentPageId)
        }
        loadingJob?.cancel()
        if (loadingJob?.isActive == true) {
            log.e("Strokes are still loading, trying to cancel and resume")
        }
    }


    fun drawAreaPageCoordinates(
        pageArea: Rect, // in page coordinates
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val areaInScreen = toScreenCoordinates(pageArea)
        drawAreaScreenCoordinates(areaInScreen, ignoredStrokeIds, ignoredImageIds, canvas)
    }

    /*
        provided a rectangle, in screen coordinates, its check
        for all images intersecting it, excluding ones set to be ignored,
        and redraws them. Does not refresh screen/SurfaceView.
     */
    fun drawAreaScreenCoordinates(
        screenArea: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val activeCanvas = canvas ?: windowedCanvas
        if (isContinuous) {
            // Continuous view redraws the whole viewport from the page stack
            // (v1: full redraw, not incremental). Selection/ignored ids unused.
            drawContinuousViewport(activeCanvas)
            return
        }
        val pageArea = toPageCoordinates(screenArea)
        val pageAreaWithoutScroll = removeScroll(pageArea)
        drawOnCanvasFromPage(
            page = this,
            canvas = activeCanvas,
            canvasClipBounds = pageAreaWithoutScroll,
            pageArea = pageArea,
            ignoredStrokeIds = ignoredStrokeIds,
            ignoredImageIds = ignoredImageIds,
        ).onError {
            snackManager.showOrUpdateSnack(
                SnackConf(
                    text = "Error during drawing page area: ${it.userMessage}",
                    duration = 3000
                )
            )
        }
    }

    /**
     * Continuous view of discrete pages: render every page intersecting the
     * viewport, each clipped to its own rect (so a stroke never visually crosses
     * a boundary), separated by a gap. Everything is in page coords -- the canvas
     * is already zoom-scaled. Pages whose strokes aren't cached yet draw blank
     * until scrolled to (the load kicks in via cacheNeighbors).
     */
    fun drawContinuousViewport(canvas: Canvas) {
        val stride = continuousPageStridePx ?: return
        val pageH = paginatedPageHeightPx ?: return
        val ids = continuousPageIds
        val zoom = zoomLevel.value
        val viewWpage = viewWidth / zoom
        val viewHpage = viewHeight / zoom
        val top = continuousScrollY

        // Inter-page gap / background (light grey), then each page on top.
        canvas.drawColor(android.graphics.Color.rgb(0xDD, 0xDD, 0xDD))

        // Background is notebook-wide for Native ruling (the common case); use the
        // current page's type/name for all pages. (Per-page PDF/image backgrounds
        // are a TODO -- they fall through to white here.)
        val bgType = pageDataManager.getBackgroundType() ?: BackgroundType.Native
        val bgName = pageDataManager.getBackgroundName()

        val firstIdx = docYToPageIndex(top)
        val lastIdx = docYToPageIndex(top + viewHpage)
        for (idx in firstIdx..lastIdx) {
            if (idx < 0 || idx >= ids.size) continue
            val pageId = ids[idx]
            val yOff = idx * stride - top // page-coord y of this page's top on screen
            canvas.save()
            canvas.clipRect(0f, yOff, viewWpage, yOff + pageH)
            // Draw this page's background/ruling starting at its top (scroll=-yOff
            // shifts the pattern down so line 0 + top margin land at the page top).
            drawBg(
                canvas = canvas,
                backgroundType = bgType,
                background = bgName,
                // drawBg draws content at (pos - scroll): horizontal uses the pan
                // offset (scroll.x, like the normal render + the strokes below);
                // vertical uses -yOff to place this page's ruling at its top.
                scroll = Offset(continuousScrollX, -yOff),
                resourceBitmap = null,
                scale = zoom,
                repeat = false,
                clipRect = null,
                showPaginationGuide = false, // discrete pages: draw our own label
            )
            val off = Offset(-continuousScrollX, yOff)
            // Images first, then strokes on top (matches the normal render path).
            // Skip any items lifted into the floating selection overlay.
            pageDataManager.getImages(pageId).forEach { image ->
                if (image.id !in continuousSelectedImageIds) drawImage(context, canvas, image, off)
            }
            pageDataManager.getStrokes(pageId).forEach { stroke ->
                if (stroke.id !in continuousSelectedStrokeIds) drawStroke(canvas, stroke, off)
            }
            // "Page N" label, top-left of the page.
            canvas.drawText("Page ${idx + 1}", 24f - continuousScrollX, yOff + 40f, continuousPageLabelPaint)
            canvas.restore()
        }
    }

    /** Load any pages now in the viewport that aren't cached yet, then repaint --
     *  so a page revealed by a scroll or a one-page shift fills in instead of
     *  staying blank until the next interaction. */
    fun ensureContinuousPagesLoaded() {
        if (!isContinuous) return
        val viewHpage = viewHeight / zoomLevel.value
        val firstIdx = docYToPageIndex(continuousScrollY)
        val lastIdx = docYToPageIndex(continuousScrollY + viewHpage)
        val ids = continuousPageIds
        coroutineScope.launch {
            for (idx in firstIdx..lastIdx) {
                ids.getOrNull(idx)?.let { pageDataManager.ensurePageLoaded(it) }
            }
            CanvasEventBus.forceUpdate.emit(null)
        }
    }

    /** Scroll the continuous stack by [dragDelta] (screen px), clamped to the
     *  document extent, and repaint the viewport. */
    suspend fun continuousScrollBy(dragDelta: Offset) {
        val zoom = zoomLevel.value
        val viewHpage = viewHeight / zoom
        val total = totalDocHeightPx(continuousPageIds.size) ?: return
        val maxScrollY = maxOf(0f, total - viewHpage)
        val newY = (continuousScrollY + dragDelta.y / zoom).coerceIn(0f, maxScrollY)
        // Horizontal pan (relevant when zoomed in): shift the shared x offset that
        // the compositor applies to backgrounds + strokes. Page fills the width at
        // zoom 1, so the pan range is viewWidth*(1 - 1/zoom).
        val maxScrollX = maxOf(0f, viewWidth - viewWidth / zoom)
        val newX = (continuousScrollX + dragDelta.x / zoom).coerceIn(0f, maxScrollX)
        if (newY == continuousScrollY && newX == continuousScrollX) return
        continuousScrollY = newY
        continuousScrollX = newX
        continuousCurrentPageIndex.value = computeMajorityPageIndex()
        waitForDrawingWithSnack()
        CanvasEventBus.forceUpdate.emit(null)
        ensureContinuousPagesLoaded()
    }

    /** Page-change in continuous mode: displace the viewport by exactly one page
     *  stride (H+gap), preserving the relative position within the view AND the
     *  zoom -- e.g. midway between pages 1&2 -> midway between 2&3; a zoomed region
     *  of page 1 -> the same region of page 2. [dir] = +1 next, -1 previous. */
    suspend fun shiftContinuousViewportByPage(dir: Int) {
        val stride = continuousPageStridePx ?: return
        val viewHpage = viewHeight / zoomLevel.value
        val total = totalDocHeightPx(continuousPageIds.size) ?: return
        val maxScroll = maxOf(0f, total - viewHpage)
        val newY = (continuousScrollY + dir * stride).coerceIn(0f, maxScroll)
        if (newY == continuousScrollY) return
        continuousScrollY = newY
        continuousCurrentPageIndex.value = computeMajorityPageIndex()
        CanvasEventBus.forceUpdate.emit(null)
        ensureContinuousPagesLoaded()
    }

    suspend fun simpleUpdateScroll(dragDelta: Offset) {
        // Just update scroll, for debugging.
        // It will redraw whole screen, instead of trying to redraw only needed area.
        log.d("Simple update scroll")
        val delta = (dragDelta / zoomLevel.value)

        waitForDrawingWithSnack()

        scroll =
            Offset((scroll.x + delta.x).coerceAtLeast(0f), (scroll.y + delta.y).coerceAtLeast(0f))

        CanvasEventBus.forceUpdate.emit(null)
    }


    fun alreadyDrawnRectAfterShift(
        movement: IntOffset,
        screenW: Int,
        screenH: Int
    ): Rect {
        val dx = -movement.x
        val dy = -movement.y
        val left = max(0, dx)
        val top = max(0, dy)
        val right = min(screenW, dx + screenW)
        val bottom = min(screenH, dy + screenH)
        return Rect(left, top, right, bottom)
    }

    suspend fun updateScroll(dragDelta: Offset) {
//        log.d("Update scroll, dragDelta: $dragDelta, scroll: $scroll, zoomLevel.value: $zoomLevel.value")
        // drag delta is in screen coordinates,
        // so we have to scale it back to page coordinates.
        var deltaInPage = Offset(dragDelta.x / zoomLevel.value, dragDelta.y / zoomLevel.value)

        // Cut, so we won't shift outside the screen.
        if (scroll.x + deltaInPage.x < 0) {
            deltaInPage = deltaInPage.copy(x = -scroll.x)
        }
        if (scroll.y + deltaInPage.y < 0) {
            deltaInPage = deltaInPage.copy(y = -scroll.y)
        }

        // There is nothing to do, return.
        if (deltaInPage == Offset.Zero) return

        // before scrolling, make sure that strokes are drawn.
        waitForDrawingWithSnack()

        scroll += deltaInPage
        // To avoid rounding errors, we just calculate it again.
        val movement = (deltaInPage * zoomLevel.value)
        if (movement.toIntOffset() == IntOffset.Zero) return

        val width = windowedBitmap.width
        val height = windowedBitmap.height
        // Shift the existing bitmap content into the spare buffer, reusing it across
        // scroll events. Recreate the spare only if it doesn't match current geometry.
        val shiftedBitmap = scrollBackBuffer?.takeIf {
            it.width == width && it.height == height && it.config == windowedBitmap.config
        } ?: createBitmap(width, height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawColor(Color.RED) //for debugging.
        shiftedCanvas.drawBitmap(windowedBitmap, -movement.x, -movement.y, null)

        // Swap in the shifted bitmap; the old live buffer becomes the next spare.
        scrollBackBuffer = windowedBitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)

        redrawOutsideRect(
            alreadyDrawnRectAfterShift(movement.toIntOffset(), width, height),
            width,
            height
        )

//        persistBitmapDebounced()
        saveToPersistLayer()
    }


    private fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        // TODO: Better snapping logic
        val portraitRatio = SCREEN_WIDTH.toFloat() / SCREEN_HEIGHT

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or screen ratio
            if (scaleDelta <= 1.0f) {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) portraitRatio else 1.0f
            } else {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) 1.0f else portraitRatio
            }
        } else {
            // Continuous zoom mode with snap behavior
            val newZoom = (scaleDelta / 3 + currentZoom).coerceIn(0.1f, 10.0f)

            // Snap to either 1.0 or screen ratio depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - portraitRatio)) {
                1.0f
            } else {
                portraitRatio
            }

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) {
                log.d("Zoom snap to $snapTarget")
                snapTarget
            } else {
                log.d("Left zoom as is. $newZoom")
                newZoom
            }
        }
    }

    suspend fun simpleUpdateZoom(scaleDelta: Float) {
        log.d("Simple Zoom updated, $scaleDelta")
        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, zoomLevel.value)

        // If there's no actual zoom change, skip
        if (newZoomLevel == zoomLevel.value) {
            log.d("Zoom unchanged. Current level: ${zoomLevel.value}")
            return
        }
        log.d("New zoom level: $newZoomLevel")
        applyZoomAndRedraw(newZoomLevel)
    }

    suspend fun applyZoomAndRedraw(newZoom: Float) {
        zoomLevel.value = newZoom
        waitForDrawingWithSnack()
        // Create a scaled bitmap to represent zoomed view
        val scaledWidth = windowedCanvas.width
        val scaledHeight = windowedCanvas.height
        log.d("Canvas dimensions: width=$scaledWidth, height=$scaledHeight")
        log.d("Bitmap dimensions: width=${windowedBitmap.width}, height=${windowedBitmap.height}")
        log.d("Screen dimensions: width=$SCREEN_WIDTH, height=$SCREEN_HEIGHT")
        log.d("Page View dimension: width=${viewWidth}, height=${viewHeight}")


        val zoomedBitmap = createBitmap(scaledWidth, scaledHeight, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
//        windowedBitmap.recycle() -- It causes race condition with init from persistent layer
        windowedBitmap = zoomedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)


        // Redraw everything at new zoom level
        val redrawRect = Rect(0, 0, windowedBitmap.width, windowedBitmap.height)

        log.d("Redrawing full logical rect: $redrawRect")
        windowedCanvas.drawColor(Color.GREEN)
        drawBgToCanvas(redrawRect)
        pageDataManager.cacheBitmap(currentPageId, windowedBitmap)

        drawAreaScreenCoordinates(redrawRect)

        saveToPersistLayer()
        log.i("Zoom and redraw completed")
    }


    /**
     * Update zoom by reusing the existing screen bitmap.
     * - Scales the snapshot around the given center (screen coords).
     * - Redraws only the uncovered bands when zooming out.
     * - When zooming in, keeps the upscaled snapshot (even if low-res) for now.
     * - Updates scroll (IntOffset) so that the top-left of the view is correct after zoom,
     *   keeping the content under the pinch center stationary on screen.
     */
    suspend fun updateZoom(scaleDelta: Float, center: Offset?) {
        log.d("Zoom(delta): $scaleDelta. Center: $center")

        val oldZoom = zoomLevel.value
        val newZoom = calculateZoomLevel(scaleDelta, oldZoom)
        if (newZoom == oldZoom) {
            log.d("Zoom unchanged. Current level: $oldZoom")
            return
        }

        // Flush pending strokes/background before snapshot-based operations
        waitForDrawingWithSnack()

        val scaleFactor = newZoom / oldZoom
        val screenW = windowedCanvas.width
        val screenH = windowedCanvas.height

        // Default pivot to screen center if none passed
        val pivotX = center?.x ?: (screenW / 2f)
        val pivotY = center?.y ?: (screenH / 2f)

        // Draw scaled snapshot into a fresh screen-sized bitmap
        val scaledBitmap = createBitmap(screenW, screenH, windowedBitmap.config!!)
        val scaledCanvas = Canvas(scaledBitmap)
        scaledCanvas.drawColor(Color.RED) // clear

        val matrix = Matrix().apply {
            postScale(scaleFactor, scaleFactor, pivotX, pivotY)
        }

        // Calculate where the scaled snapshot ended up on screen.
        // Map the original screen rect through the same matrix to get content bounds.
        val srcRect = RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())
        val dstRect = RectF()
        matrix.mapRect(dstRect, srcRect)


        //make sure that we won't go outside canvas.
        val dx = (scroll.x - dstRect.left).coerceAtMost(0f)
        val dy = (scroll.y - dstRect.top).coerceAtMost(0f)
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            matrix.mapRect(dstRect, srcRect)
        }
        scaledCanvas.drawBitmap(windowedBitmap, matrix, null)


        val deltaScrollPage = Offset(-dstRect.left / newZoom, -dstRect.top / newZoom)


        val newScrollX = (scroll.x + deltaScrollPage.x).coerceAtLeast(0f)
        val newScrollY = (scroll.y + deltaScrollPage.y).coerceAtLeast(0f)
        scroll = Offset(newScrollX, newScrollY)

        // Swap in the new bitmap and update zoom on the windowed canvas
        windowedBitmap = scaledBitmap
        windowedCanvas.setBitmap(windowedBitmap)

        zoomLevel.value = newZoom
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)

        if (scaleFactor < 1f) redrawOutsideRect(dstRect.toRect(), screenW, screenH)

//        persistBitmapDebounced()
        saveToPersistLayer()
        log.i(
            "Zoom updated using snapshot scaling. " +
                    "oldZoom=$oldZoom newZoom=$newZoom " +
                    "scaleFactor=$scaleFactor pivot=($pivotX,$pivotY) " +
                    "bounds=$dstRect" +
                    "scrollDelta=$deltaScrollPage newScroll=$scroll"
        )
    }

    fun redrawOutsideRect(dstRect: Rect, screenW: Int, screenH: Int) {
        val scaledOverlap = ceil(OVERLAP * zoomLevel.value.coerceAtLeast(1f)).toInt()

        // Uncovered top band
        if (dstRect.top > 0) {
            val r = Rect(
                0,
                0,
                screenW,
                (dstRect.top + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered bottom band
        if (dstRect.bottom < screenH) {
            val r = Rect(
                0, (dstRect.bottom - scaledOverlap).coerceAtLeast(0), screenW, screenH
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered left band
        if (dstRect.left > 0) {
            val r = Rect(
                0,
                (dstRect.top - scaledOverlap).coerceAtLeast(0),
                (dstRect.left + scaledOverlap).coerceAtMost(screenW),
                (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered right band
        if (dstRect.right < screenW) {
            val r = Rect(
                (dstRect.right - scaledOverlap).coerceAtLeast(0),
                (dstRect.top - scaledOverlap).coerceAtLeast(0),
                screenW,
                (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
    }


    // updates page setting in db, (for instance type of background)
    // and redraws page to view.
    suspend fun refreshCurrentPage() {
        val pageId = currentPageId
        log.d("Refresh page: $pageId")
        pageDataManager.refreshPageFromDb(pageId)
        withContext(Dispatchers.Main) {
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
//            persistBitmapDebounced()
        }

    }

    fun drawBgToCanvas(clipRect: Rect?) {
        val backgroundType = pageDataManager.getBackgroundType() ?: BackgroundType.Native
        val bg = pageDataManager.getBackgroundName()
        val pageNumber = currentPageNumber
        val scale = zoomLevel.value
        val bgImage: Bitmap? =
            when (backgroundType) {
                BackgroundType.Image, BackgroundType.CoverImage, BackgroundType.AutoPdf,
                is BackgroundType.Pdf, BackgroundType.ImageRepeating -> {
                    if (backgroundType is BackgroundType.Image && bg == "iris") {
                        val resId = R.drawable.iris
                        ImageBitmap.imageResource(context.resources, resId).asAndroidBitmap()
                    } else {
                        getOrLoadBackground(bg, pageNumber, scale)
                    }
                }

                BackgroundType.Native -> {
                    null
                }
            }
        drawBg(
            canvas = windowedCanvas,
            backgroundType = backgroundType,
            background = bg,
            scroll = scroll,
            resourceBitmap = bgImage,
            scale = scale,
            repeat = false,
            clipRect = clipRect
        )
    }


    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            log.d("Updating dimensions: $newWidth x $newHeight")
            viewWidth = newWidth
            viewHeight = newHeight
            updateCanvasDimensions()
        }
    }

    private fun updateCanvasDimensions() {
        // Recreate bitmap and canvas with new dimensions
        recreateCanvas()
        //Reset zoom level.
        zoomLevel.value = 1.0f
        // TODO: it might be worth to do it
        //  by redrawing only part of the screen, like in scroll and zoom.
        coroutineScope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
//        persistBitmapDebounced()
    }


    private fun saveToPersistLayer() = pageDataManager.setScrollInDb()

    fun applyZoom(point: IntOffset): IntOffset {
        return point * zoomLevel.value
    }

    fun removeZoom(point: IntOffset): IntOffset {
        return point / zoomLevel.value
    }

    private fun removeScroll(rect: Rect): Rect {
        return rect - scroll
    }

    fun toScreenCoordinates(rect: Rect): Rect {
        return (rect - scroll) * zoomLevel.value
    }

    private fun toPageCoordinates(rect: Rect): Rect {
        return rect / zoomLevel.value + scroll
    }

    private suspend fun waitForDrawingWithSnack() {
        if (drawingInProgress.isLocked) {
            snackManager.runWithSnack("Waiting for drawing to finish…", resultDurationMs = 0) {
                waitForDrawing()
                "Drawing finished"
            }
        }
    }
}