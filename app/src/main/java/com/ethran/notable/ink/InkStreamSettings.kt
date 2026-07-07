package com.ethran.notable.ink

import kotlinx.serialization.Serializable

const val INK_STREAM_SETTINGS_KEY = "INK_STREAM_SETTINGS"

/**
 * Settings for live ink streaming (Phase 1: one-way mirror to xournal over UDP).
 * @param enabled whether strokes are streamed while drawing.
 * @param host receiver IP/hostname (the machine running `xournal --ink-listen`).
 * @param port receiver UDP port.
 */
@Serializable
data class InkStreamSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 5555,
)
