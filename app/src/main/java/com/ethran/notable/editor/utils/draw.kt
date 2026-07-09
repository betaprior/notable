package com.ethran.notable.editor.utils

import androidx.core.graphics.toRect
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.PageView
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.ShipBook


private val log = ShipBook.getLogger("draw")

// touchpoints are in page coordinates
fun handleDraw(
    page: PageView,
    historyBucket: MutableList<String>,
    strokeSize: Float,
    color: Int,
    pen: Pen,
    touchPoints: List<StrokePoint>,
    strokeId: String? = null,
    // Continuous view: commit to this page (the one under the pen) instead of the
    // current page; [touchPoints] are already that page's local coords.
    targetPageId: String? = null,
    targetPageIndex: Int = 0,
) {
    try {
        val boundingBox = calculateBoundingBox(touchPoints) { Pair(it.x, it.y) }

        //move rectangle
        boundingBox.inset(-strokeSize, -strokeSize)

        val stroke = Stroke(
            id = strokeId ?: java.util.UUID.randomUUID().toString(),
            size = strokeSize,
            pen = pen,
            pageId = targetPageId ?: page.currentPageId,
            top = boundingBox.top,
            bottom = boundingBox.bottom,
            left = boundingBox.left,
            right = boundingBox.right,
            points = touchPoints,
            color = color,
            maxPressure = EpdController.getMaxTouchPressure().toInt()
        )
        if (targetPageId != null) {
            // Continuous view: commit to the pen's page + repaint the viewport.
            page.addStrokesToPage(targetPageId, targetPageIndex, listOf(stroke))
        } else {
            // Live-draw path already streamed this stroke via begin/points/end.
            page.addStrokes(listOf(stroke), alreadyStreamed = true)
            // this is causing lagging and crushing, neo pens are not good
            page.drawAreaPageCoordinates(strokeBounds(stroke).toRect())
        }
        historyBucket.add(stroke.id)
    } catch (e: Exception) {
        log.e("Handle Draw: An error occurred while handling the drawing: ${e.message}")
    }
}
