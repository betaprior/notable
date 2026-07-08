package com.ethran.notable.ink

import kotlinx.serialization.Serializable

const val INK_STREAM_SETTINGS_KEY = "INK_STREAM_SETTINGS"

/**
 * Settings for live ink streaming (Phase 1: one-way mirror to xournal over UDP).
 * @param enabled whether strokes are streamed while drawing.
 * @param host receiver IP/hostname (the laptop running inkhubd / xournal).
 * @param port receiver UDP port. With the hub flow this is assigned per
 *   session by "start streaming" (the hub returns the document's port);
 *   manual entry remains as a fallback for hubless `xournal --ink-listen`.
 * @param hubPort TCP port of the inkhub session broker on [host].
 * @param secret shared secret for hub requests; must match the hub config's
 *   "secret" (leave empty if the hub has none configured).
 * @param sessionToken per-session UDP auth token issued by the hub on
 *   open_doc (16 hex chars); managed automatically, empty in manual mode.
 */
@Serializable
data class InkStreamSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 5555,
    val hubPort: Int = 5550,
    val secret: String = "",
    val sessionToken: String = "",
)
