package com.ethran.notable.editor.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.io.shareBitmap
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Crosshair
import compose.icons.feathericons.Scissors
import compose.icons.feathericons.Share2

val strokeStyle = Stroke(
    width = 2f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
)

@Composable
fun SelectedBitmap(
    context: Context,
    controlTower: EditorControlTower
) {
    val selectionState = controlTower.getSnapshotOfSelectionState()
    if (selectionState.selectedBitmap == null) return

    var selectionDisplaceOffset =
        controlTower.page.applyZoom(selectionState.selectionDisplaceOffset ?: return)
    val selectionRect =
        controlTower.page.toScreenCoordinates(selectionState.selectionRect ?: return)
    val selectionStartOffset =
        controlTower.page.applyZoom(selectionState.selectionStartOffset ?: IntOffset(0, 0))

    // Track whether the pointer touching the selection UI is a stylus, so selection-button
    // actions (e.g. duplicate) can adapt: the stylus can't show a floating copy, so it's placed
    // on top of the original to be grabbed and dragged blind. Recorded on the Initial pass so it
    // is set before a button's click handler runs.
    var lastPointerStylus by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()
                            ?.let { lastPointerStylus = it.type == PointerType.Stylus }
                    }
                }
            }
            .noRippleClickable {
                controlTower.applySelectionDisplace()
                selectionState.reset()
                controlTower.setIsDrawing(true)
            }) {
        Image(
            bitmap = selectionState.selectedBitmap!!.asImageBitmap(),
            contentDescription = "Selection bitmap",
            modifier = Modifier
                .offset { selectionStartOffset + selectionDisplaceOffset }
                .drawBehind {
                    drawRect(
                        color = Color.Gray,
                        topLeft = Offset(0f, 0f),
                        size = size,
                        style = strokeStyle
                    )
                }
                .pointerInput(Unit) {
                    // A finger drag repaints the floating selection live and stays floating on
                    // lift (commit on tap). A stylus drag can't repaint live under the pen, so on
                    // lift we finalize it (commit + show) instead of leaving it invisible.
                    var dragWasStylus = false
                    detectDragGestures(
                        onDragEnd = {
                            if (dragWasStylus) controlTower.finishStylusSelectionDrag()
                        },
                        onDragCancel = {
                            if (dragWasStylus) controlTower.finishStylusSelectionDrag()
                        },
                    ) { change, dragAmount ->
                        dragWasStylus = change.type == PointerType.Stylus
                        change.consume()
                        selectionState.selectionDisplaceOffset =
                            controlTower.page.removeZoom(
                                selectionDisplaceOffset + dragAmount.round()
                            )
                        selectionDisplaceOffset =
                            controlTower.page.applyZoom(
                                selectionState.selectionDisplaceOffset ?: return@detectDragGestures
                            )
                    }
                }
                .combinedClickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                    onDoubleClick = { controlTower.duplicateSelection(overlapOriginal = lastPointerStylus) }
                )
        )

        // TODO: improve this code

        // +1 for the get-width button (strokes only)
        val hasStrokes = !selectionState.selectedStrokes.isNullOrEmpty()
        val widthButtons = if (hasStrokes) 1 else 0
        val buttonCount = (if (selectionState.isResizable()) 7 else 5) + widthButtons
        val toolbarPadding = 4

        // If we can calculate offset of buttons show selection handling tools
        selectionStartOffset.let { startOffset ->
            selectionDisplaceOffset.let { displaceOffset ->
                // TODO: I think the toolbar is still not in the center.
                val xPos = selectionRect.let { rect ->
                    (rect.right - rect.left) / 2 - buttonCount * (BUTTON_SIZE + 5 * toolbarPadding)
                }
                val offset = startOffset + displaceOffset + IntOffset(x = xPos, y = -100)
                // Overlay buttons near the selection box
                Row(
                    modifier = Modifier
                        .offset { offset }
                        .background(Color.White.copy(alpha = 0.8f))
                        .padding(toolbarPadding.dp)
                        .height(BUTTON_SIZE.dp)
                ) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Share2,
                        isSelected = false,
                        onSelect = {
                            shareBitmap(context, controlTower.getSelectedBitmap())
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        iconId = R.drawable.delete,
                        isSelected = false,
                        onSelect = {
                            controlTower.deleteSelection()
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    if (selectionState.isResizable()) {
                        ToolbarButton(
                            iconId = R.drawable.plus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(10) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                        ToolbarButton(
                            iconId = R.drawable.minus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(-10) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                    }
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Scissors,
                        isSelected = false,
                        onSelect = { controlTower.cutSelectionToClipboard(context) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        isSelected = false,
                        onSelect = { controlTower.copySelectionToClipboard(context) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Copy,
                        isSelected = false,
                        onSelect = { controlTower.duplicateSelection(overlapOriginal = lastPointerStylus) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    if (hasStrokes) {
                        // Get width: load the selection's dominant width into the toolbar
                        // width widget (where it, and the picker, can then be applied back
                        // to the selection). Set is done from the main toolbar widget.
                        ToolbarButton(
                            vectorIcon = FeatherIcons.Crosshair,
                            contentDescription = "get stroke width",
                            isSelected = false,
                            onSelect = { controlTower.getSelectionStrokeWidth() },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                    }
                }
            }
        }

    }
}