package com.ethran.notable.editor.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import androidx.core.graphics.createBitmap
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.offsetImage
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.setAnimationMode
import com.ethran.notable.io.copyBitmapToClipboard
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.plus
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import java.util.Date
import java.util.UUID

private val log = ShipBook.getLogger("SelectionState")

/**
 * Represents the current state of a selection within the editor, managing the lifecycle and
 * transformations of selected items such as strokes and images.
 *
 * This class tracks the geometric boundaries, visual representation (bitmap), and
 * spatial offsets of the selected content. It provides methods for manipulating
 * the selection, including translation (moving), resizing, duplication, and
 * integration with the undo/redo history system.
 *
 * All coordinate-based properties within this class are intended to be in page coordinates
 * unless otherwise specified.
 */
/**
 * Result of committing a selection displacement: the undo operations, plus the strokes and
 * images as they now exist at the committed (new) position -- callers can hand these straight
 * back to [selectImagesAndStrokes] to re-select the moved content.
 */
data class DisplaceResult(
    val operations: List<Operation>,
    val strokes: List<Stroke>,
    val images: List<Image>,
)

class SelectionState {
    // all coordinates should be in page coordinates
    var firstPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var secondPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var selectedStrokes by mutableStateOf<List<Stroke>?>(null)
    var selectedImages by mutableStateOf<List<Image>?>(null)

    // TODO: Bitmap should be change, if scale changes.
    var selectedBitmap by mutableStateOf<Bitmap?>(null)

    var selectionStartOffset by mutableStateOf<IntOffset?>(null)
    var selectionDisplaceOffset by mutableStateOf<IntOffset?>(null)
    var selectionRect by mutableStateOf<Rect?>(null)
    var placementMode by mutableStateOf<PlacementMode?>(null)

    fun reset() {
        log.v("reset")
        selectedStrokes = null
        selectedImages = null
        secondPageCut = null
        firstPageCut = null
        selectedBitmap = null
        selectionRect = null
        selectionStartOffset = null
        selectionDisplaceOffset = null
        placementMode = null
        setAnimationMode(false)
    }

    fun isNonEmpty(): Boolean {
        return !selectedStrokes.isNullOrEmpty() || !selectedImages.isNullOrEmpty()
    }

    fun isResizable(): Boolean {
        return selectedImages?.count() == 1 && selectedStrokes.isNullOrEmpty()
    }

    /**
     * On-screen bounding box (px) of the whole selection UI: the selection bitmap plus the
     * floating button strip that sits ~100px above it (see [com.ethran.notable.editor.ui.SelectedBitmap]).
     * Used to carve this region out of the Onyx raw-drawing surface, so a stylus over the
     * selection is delivered to the Compose selection UI (drag/buttons) while the pen still
     * lassos elsewhere. Null when there is no active selection.
     */
    fun screenBounds(page: PageView): Rect? {
        val rectPage = selectionRect ?: return null
        val startState = selectionStartOffset ?: return null
        val dispState = selectionDisplaceOffset ?: IntOffset(0, 0)

        val topLeft = page.applyZoom(startState + dispState) // screen px
        val screenRect = page.toScreenCoordinates(rectPage)
        val w = screenRect.width()
        val h = screenRect.height()
        val bmpLeft = topLeft.x
        val bmpTop = topLeft.y
        val bmpRight = bmpLeft + w
        val bmpBottom = bmpTop + h

        // Mirror SelectorBitmap's button-row placement: offset (xPos, -100) from the bitmap.
        // +1 for the get stroke-width button shown when strokes are selected.
        val buttonCount = (if (isResizable()) 7 else 5) +
                (if (!selectedStrokes.isNullOrEmpty()) 1 else 0)
        val toolbarPadding = 4
        val xPos = w / 2 - buttonCount * (BUTTON_SIZE + 5 * toolbarPadding)
        val rowWidth = buttonCount * (BUTTON_SIZE + 4 * toolbarPadding)
        val rowHeight = BUTTON_SIZE + 4 * toolbarPadding
        val btnLeft = bmpLeft + xPos
        val btnRight = btnLeft + rowWidth
        val btnTop = bmpTop - 100 - rowHeight

        val margin = 24
        return Rect(
            minOf(bmpLeft, btnLeft) - margin,
            minOf(bmpTop, btnTop) - margin,
            maxOf(bmpRight, btnRight) + margin,
            bmpBottom + margin
        )
    }

    fun resizeImages(scale: Int, page: PageView): AppResult<Unit, DomainError> {
        log.v("resizeImages: scale=$scale")

        val selectedImagesCopy = selectedImages?.map { image ->
            image.copy(
                height = image.height + (image.height * scale / 100),
                width = image.width + (image.width * scale / 100)
            )
        }

        if (selectedImagesCopy.isNullOrEmpty()) {
            return AppResult.Error(DomainError.UnexpectedState("No images selected for resizing."))
        }

        selectedImages = selectedImagesCopy
        // Adjust displacement offset by half the size change
        val sizeChange = selectedImagesCopy.firstOrNull()?.let { image ->
            IntOffset(
                x = (image.width * scale / 200), y = (image.height * scale / 200)
            )
        } ?: IntOffset.Zero

        val pageBounds = imageBoundsInt(selectedImagesCopy)
        selectionRect = page.toScreenCoordinates(pageBounds)

        selectionDisplaceOffset = selectionDisplaceOffset?.let { it - sizeChange } ?: IntOffset.Zero

        // 1. Safe Bitmap Creation
        val selectedBitmapNew = try {
            // createBitmap can throw IllegalArgumentException if width/height <= 0
            // or OutOfMemoryError if the scale is massive.
            createBitmap(pageBounds.width(), pageBounds.height())
        } catch (e: Exception) {
            log.e("Failed to create resized bitmap", e)
            return AppResult.Error(DomainError.DrawingError("Failed to allocate memory for resized image."))
        } catch (e: OutOfMemoryError) {
            log.e("OOM on resized bitmap", e)
            return AppResult.Error(DomainError.DrawingError("Image is too large to resize."))
        }

        val selectedCanvas = Canvas(selectedBitmapNew)

        // 2. The Accumulator
        var accumulatedError: DomainError? = null

        // 3. The Loop
        selectedImagesCopy.forEach { image ->
            drawImage(
                page.context, selectedCanvas, image, Offset(-image.x.toFloat(), -image.y.toFloat())
            ).onError { error ->
                accumulatedError = accumulatedError?.let { it + error } ?: error
            }
        }

        // 4. Final State Update & Return
        selectedBitmap = selectedBitmapNew

        return accumulatedError?.let { AppResult.Error(it) } ?: AppResult.Success(Unit)
    }

    @Suppress("UNUSED_PARAMETER")
    fun resizeStrokes(scale: Int, scope: CoroutineScope, page: PageView) {
        log.v("resizeStrokes: scale=$scale")
        //TODO: implement this
    }

    /**
     * Deletes the currently selected strokes and images from the page.
     *
     * This function identifies the selected images and strokes, removes them from the given [page],
     * and creates a list of undo [Operation]s. After deletion, it resets the selection state.
     *
     * @param page The [PageView] from which the selected items should be removed.
     * @return A list of [Operation]s that can be used to undo the deletion (e.g., re-adding the deleted items).
     */
    fun deleteSelection(page: PageView): List<Operation> {
        log.v("deleteSelection: images=${selectedImages?.size}, strokes=${selectedStrokes?.size}")
        val operationList = mutableListOf<Operation>()
        val selectedImagesToRemove = selectedImages
        if (!selectedImagesToRemove.isNullOrEmpty()) {
            val imageIds: List<String> = selectedImagesToRemove.map { it.id }
            log.i("removing images")
            page.removeImages(imageIds)
            operationList += Operation.AddImage(selectedImagesToRemove)
        }
        val selectedStrokesToRemove = selectedStrokes
        if (!selectedStrokesToRemove.isNullOrEmpty()) {
            val strokeIds: List<String> = selectedStrokesToRemove.map { it.id }
            log.i("removing strokes")
            page.removeStrokes(strokeIds)
            operationList += Operation.AddStroke(selectedStrokesToRemove)
        }
        reset()
        return operationList
    }

    fun duplicateSelection() {
        log.v("duplicateSelection")
        // set operation to paste only
        placementMode = PlacementMode.Paste
        if (!selectedStrokes.isNullOrEmpty())
        // change the selected stokes' ids - it's a copy
            selectedStrokes = selectedStrokes!!.map {
                it.copy(
                    id = UUID.randomUUID().toString(), createdAt = Date()
                )
            }
        if (!selectedImages.isNullOrEmpty()) selectedImages = selectedImages!!.map {
            it.copy(
                id = UUID.randomUUID().toString(), createdAt = Date()
            )
        }
        // move the selection a bit, to show the copy
        selectionDisplaceOffset = IntOffset(
            x = (selectionDisplaceOffset?.x ?: 0) + 50,
            y = (selectionDisplaceOffset?.y ?: 0) + 50,
        )
    }

    // Moves strokes, and redraws canvas. Returns the operations plus the strokes/images as
    // they now exist at the new position (so callers can re-select them, e.g. after a stylus
    // drag). Returns null when there is nothing to commit.
    fun applySelectionDisplace(page: PageView): DisplaceResult? {
        log.v("applySelectionDisplace: offset=$selectionDisplaceOffset, mode=$placementMode")

        if (selectionDisplaceOffset == null) return null
        if (selectionRect == null) return null

        // get snapshot of the selection
        val selectedStrokesCopy = selectedStrokes
        val selectedImagesCopy = selectedImages
        val offset = selectionDisplaceOffset!!
        val finalZone = selectionRect!!
        finalZone.offset(offset.x, offset.y)

        // collect undo operations for strokes and images together, as a single change
        val operationList = mutableListOf<Operation>()
        var committedStrokes: List<Stroke> = emptyList()
        var committedImages: List<Image> = emptyList()

        if (!selectedStrokesCopy.isNullOrEmpty()) {
            val displacedStrokes = selectedStrokesCopy.map {
                offsetStroke(it, offset = offset.toOffset())
            }

            // Move WITH an actual displacement mints fresh ids (immutable edit). A Move
            // with zero offset (select -> deselect, or get/set-width) leaves the strokes
            // exactly as they are -- skip the rewrite so we don't churn ids or re-stream
            // unchanged strokes. Paste always adds the displaced copies.
            committedStrokes = when {
                placementMode == PlacementMode.Paste -> {
                    page.addStrokes(displacedStrokes)
                    displacedStrokes
                }
                offset.x != 0 || offset.y != 0 -> page.updateStrokes(displacedStrokes)
                else -> selectedStrokesCopy
            }

            if (offset.x != 0 || offset.y != 0 || placementMode == PlacementMode.Paste) {
                // A displacement happened or this is a paste commit - create history for this
                operationList += Operation.DeleteStroke(committedStrokes.map { it.id })
                // in case we are on a move operation, this history point re-adds the original strokes
                if (placementMode == PlacementMode.Move) operationList += Operation.AddStroke(
                    selectedStrokesCopy
                )
            }
        }
        if (!selectedImagesCopy.isNullOrEmpty()) {
            log.i("Commit images to history.")

            val displacedImages = selectedImagesCopy.map {
                offsetImage(it, offset = offset.toOffset())
            }
            if (placementMode == PlacementMode.Move) page.removeImages(selectedImagesCopy.map { it.id })

            page.addImage(displacedImages)
            committedImages = displacedImages

            if (offset.x != 0 || offset.y != 0 || placementMode == PlacementMode.Paste) {
                // TODO: find why sometimes we add two times same operation.
                // A displacement happened or this is a paste commit - create history for this
                // To undo changes we first remove image
                operationList += Operation.DeleteImage(displacedImages.map { it.id })
                // then add the original images, only if we intended to move it.
                if (placementMode == PlacementMode.Move) operationList += Operation.AddImage(
                    selectedImagesCopy
                )
            }
        }
        page.drawAreaPageCoordinates(finalZone)
        return DisplaceResult(operationList, committedStrokes, committedImages)
    }

    fun applySelectionDisplaceAndCommit(page: PageView, history: History): DisplaceResult? {
        val result = applySelectionDisplace(page) ?: return null
        if (result.operations.isNotEmpty()) history.addOperationsToHistory(result.operations)
        return result
    }

    fun deleteSelectionAndCommit(page: PageView, history: History) {
        val operationList = deleteSelection(page)
        history.addOperationsToHistory(operationList)
    }

    fun selectionToClipboard(scrollPos: Offset, context: Context): ClipboardContent {
        log.v("selectionToClipboard: scrollPos=$scrollPos, images=${selectedImages?.size}, strokes=${selectedStrokes?.size}")

        val strokes = selectedStrokes?.map {
            offsetStroke(it, offset = -scrollPos)
        }

        val images = selectedImages?.map {
            it.copy(x = it.x - scrollPos.x.toInt(), y = it.y - scrollPos.y.toInt())
        }

        selectedBitmap?.let {
            copyBitmapToClipboard(context, it)
        }
        return ClipboardContent(
            strokes = strokes ?: emptyList(), images = images ?: emptyList()
        )
    }
}
