package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.editor.utils.STROKE_WIDTH_PRESETS
import com.ethran.notable.editor.utils.isPresetWidth

/**
 * Reusable stroke-width selector: S / M / L / XL preset chips plus a "C" (custom)
 * chip. Picking a preset calls [onPick] with isCustom=false; the C chip opens a
 * modal (pre-filled with [lastCustom], or the current width if it's already
 * custom) and calls [onPick] with isCustom=true on confirm.
 *
 * Used for the standalone toolbar width widget and the selection "set width" popup.
 */
@Composable
fun StrokeWidthChips(
    current: Float,
    lastCustom: Float,
    onPick: (width: Float, isCustom: Boolean) -> Unit,
    presets: List<Pair<String, Float>> = STROKE_WIDTH_PRESETS,
) {
    var showCustom by remember { mutableStateOf(false) }
    val currentIsCustom = !isPresetWidth(current)

    Row(
        modifier = Modifier
            .background(Color.White)
            .border(1.dp, Color.Black),
        horizontalArrangement = Arrangement.Center
    ) {
        presets.forEach { (label, value) ->
            ToolbarButton(
                text = label,
                isSelected = current == value,
                onSelect = { onPick(value, false) },
            )
        }
        ToolbarButton(
            text = "C",
            isSelected = currentIsCustom,
            onSelect = { showCustom = true },
        )
    }

    if (showCustom) {
        CustomWidthDialog(
            initial = if (currentIsCustom) current else lastCustom,
            onDismiss = { showCustom = false },
            onConfirm = { w ->
                showCustom = false
                onPick(w, true)
            }
        )
    }
}

/**
 * Standalone toolbar button showing the current pen width (S/M/L/XL/C) that opens
 * a [StrokeWidthChips] popup. Visual feedback + quick width change for the pen.
 */
@Composable
fun StrokeWidthToolbarButton(
    current: Float,
    lastCustom: Float,
    onPick: (width: Float, isCustom: Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        ToolbarButton(
            text = com.ethran.notable.editor.utils.widthLabel(current),
            isSelected = open,
            contentDescription = "stroke width",
            onSelect = { open = !open },
        )
        if (open) {
            androidx.compose.ui.window.Popup(
                offset = androidx.compose.ui.unit.IntOffset(
                    0, com.ethran.notable.ui.convertDpToPixel(43.dp, context).toInt()
                ),
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                alignment = androidx.compose.ui.Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .background(Color.White)
                        .border(1.dp, Color.Black)
                        .padding(6.dp)
                        .padding(bottom = (com.ethran.notable.data.datastore.BUTTON_SIZE + 5).dp)
                ) {
                    StrokeWidthChips(
                        current = current,
                        lastCustom = lastCustom,
                        onPick = { w, c -> open = false; onPick(w, c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomWidthDialog(
    initial: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    // show without a trailing ".0" for whole numbers
    var text by remember {
        mutableStateOf(if (initial == initial.toInt().toFloat()) initial.toInt().toString() else initial.toString())
    }
    fun commit() {
        val v = text.trim().toFloatOrNull()
        if (v != null && v > 0f) onConfirm(v.coerceIn(0.5f, 200f)) else onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material.Surface(
            color = Color.White,
            border = BorderStroke(2.dp, Color.Black),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Custom stroke width", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .border(1.dp, Color.Black)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .fillMaxWidth()
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { s -> text = s.filter { it.isDigit() || it == '.' } },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "Cancel",
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(end = 24.dp)
                    )
                    Text(
                        "Set",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.clickable { commit() }
                    )
                }
            }
        }
    }
}
