package com.ethran.notable.editor.utils


import com.onyx.android.sdk.pen.style.StrokeStyle
import kotlinx.serialization.Serializable


enum class Pen(val penName: String) {
    BALLPEN("BALLPEN"),
    REDBALLPEN("REDBALLPEN"),
    GREENBALLPEN("GREENBALLPEN"),
    BLUEBALLPEN("BLUEBALLPEN"),
    PENCIL("PENCIL"),
    BRUSH("BRUSH"),
    MARKER("MARKER"),
    FOUNTAIN("FOUNTAIN"),
    DASHED("DASHED");

    companion object {
        fun fromString(name: String?): Pen {
            return entries.find { it.penName.equals(name, ignoreCase = true) } ?: BALLPEN
        }
    }
}

fun penToStroke(pen: Pen): Int {
    return when (pen) {
        Pen.BALLPEN -> StrokeStyle.PENCIL
        Pen.REDBALLPEN -> StrokeStyle.PENCIL
        Pen.GREENBALLPEN -> StrokeStyle.PENCIL
        Pen.BLUEBALLPEN -> StrokeStyle.PENCIL
        Pen.PENCIL -> StrokeStyle.CHARCOAL
        Pen.BRUSH -> StrokeStyle.NEO_BRUSH
        Pen.MARKER -> StrokeStyle.MARKER
        Pen.FOUNTAIN -> StrokeStyle.FOUNTAIN
        Pen.DASHED -> StrokeStyle.DASH
    }
}


@Serializable
data class PenSetting(
    var strokeSize: Float,
    //TODO: Rename to strokeColor
    var color: Int
)

typealias NamedSettings = Map<String, PenSetting>

// Stroke-width presets shown as chips (S/M/L/XL). A width not equal to any of
// these renders as "C" (custom). Single source of truth for the pen size widget,
// the selection set/get-width actions, and preset detection.
val STROKE_WIDTH_PRESETS: List<Pair<String, Float>> =
    listOf("S" to 3f, "M" to 5f, "L" to 10f, "XL" to 20f)

fun isPresetWidth(width: Float): Boolean = STROKE_WIDTH_PRESETS.any { it.second == width }

/** Short label for a width: the matching preset name, else "C" for custom. */
fun widthLabel(width: Float): String =
    STROKE_WIDTH_PRESETS.firstOrNull { it.second == width }?.first ?: "C"