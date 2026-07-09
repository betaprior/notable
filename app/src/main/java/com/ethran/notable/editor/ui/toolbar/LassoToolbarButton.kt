package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.ui.convertDpToPixel

/**
 * Lasso (select) toolbar button with a submenu for loose vs. strict selection.
 * Tapping when already active opens the submenu (mirrors the eraser button).
 * Loose = any stroke the lasso touches is selected; Strict = only strokes fully
 * enclosed by the lasso.
 */
@Composable
fun LassoToolbarButton(
    isSelected: Boolean,
    onSelect: () -> Unit,
    strict: Boolean,
    onStrictChange: (Boolean) -> Unit,
    isMenuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    Box {
        ToolbarButton(
            isSelected = isSelected,
            onSelect = {
                if (isSelected) onMenuOpenChange(!isMenuOpen)
                else onSelect()
            },
            iconId = R.drawable.lasso,
            contentDescription = "lasso"
        )

        if (isMenuOpen) {
            Popup(
                offset = IntOffset(0, convertDpToPixel(43.dp, context).toInt()),
                onDismissRequest = { onMenuOpenChange(false) },
                properties = PopupProperties(focusable = true),
                alignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .background(Color.White)
                        .border(1.dp, Color.Black)
                        .height(IntrinsicSize.Max)
                ) {
                    Row(
                        Modifier
                            .height(IntrinsicSize.Max)
                            .border(1.dp, Color.Black)
                    ) {
                        ToolbarButton(
                            text = "Loose",
                            isSelected = !strict,
                            onSelect = { onStrictChange(false) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                        ToolbarButton(
                            text = "Strict",
                            isSelected = strict,
                            onSelect = { onStrictChange(true) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                    }
                }
            }
        }
    }
}
