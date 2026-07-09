package com.ethran.notable.ink

import android.content.Context
import android.util.Log
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.pageHeightPt
import com.ethran.notable.data.datastore.pageWidthPt
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.utils.Pen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.floor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 live ink streaming: sends strokes to a xournal receiver over UDP as
 * they are drawn. One-way, fire-and-forget (no acks/retransmit yet).
 *
 * Wire protocol matches xo-remote.c on the xournal side:
 *   header: 'X' 'I' 'N' 'K' | version(1) | msg type
 *   STROKE_BEGIN(2): uuid[16] | page u16 | tool u8 | 0 u8 | rgba u32 | width f32
 *   POINTS(3):       uuid[16] | first_index u16 | count u16 | count*{x,y,pressure f32}
 *   STROKE_END(4):   uuid[16] | total_count u16
 * All integers/floats little-endian. Coordinates are xoj page points.
 */
@Singleton
class InkStreamClient @Inject constructor(
    private val kvProxy: KvProxy,
) {
    private val TAG = "InkStreamClient"

    private val _settings = MutableStateFlow(InkStreamSettings())
    val settings: StateFlow<InkStreamSettings> = _settings.asStateFlow()

    val isEnabled: Boolean get() = _settings.value.enabled && _settings.value.host.isNotBlank()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0

    // Per-stroke state, mutated only from the (serialized) drawing thread.
    private var currentUuid: ByteArray? = null
    private val pointBuffer = ArrayList<Float>(64) // x,y,pressure triples
    private var pointsSent = 0
    private val flushEvery = 3 // points per POINTS datagram

    // Page index of the most recent live stroke; reused when streaming complete
    // strokes (redo/paste/undo-of-erase) which don't carry their own index.
    // MVP is current-page-only, so this matches the page being edited.
    private var lastPageIndex = 0

    // page coords -> xoj page points (matches XoppFile export)
    private val scaleFactor: Float get() = pageWidthPt.toFloat() / SCREEN_WIDTH

    init {
        scope.launch {
            for (datagram in sendChannel) trySend(datagram)
        }
        // Load persisted settings as soon as the singleton exists, so streaming
        // resumes after an app restart without having to reopen the settings tab.
        loadSettings()
    }

    fun loadSettings() {
        scope.launch {
            val loaded = kvProxy.get(INK_STREAM_SETTINGS_KEY, InkStreamSettings.serializer())
            if (loaded != null) {
                _settings.value = loaded
                tokenBytes = parseToken(loaded.sessionToken)
            }
        }
    }

    fun saveSettings(settings: InkStreamSettings) {
        _settings.value = settings
        tokenBytes = parseToken(settings.sessionToken)
        // force re-resolution of address/socket on next stroke
        closeSocket()
        scope.launch {
            kvProxy.setKv(INK_STREAM_SETTINGS_KEY, settings, InkStreamSettings.serializer())
        }
    }

    // Per-session auth token from the hub (8 bytes hex); packets carry it in a
    // v2 header. Null (manual/hubless mode) sends bare v1 headers.
    @Volatile
    private var tokenBytes: ByteArray? = null

    private fun parseToken(hex: String): ByteArray? {
        if (hex.length != 16) return null
        return try {
            ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    // ---- stroke lifecycle (called from the drawing thread) ----

    /**
     * Begin the live fragment for the stroke's first band. A stroke that straddles page
     * boundaries is mirrored as one fragment per band, all sharing group id [strokeId];
     * the fragment for [band] is keyed by fragUuid(strokeId, band). Other bands are sent
     * at pen-up via [streamStrokeExtraBands].
     */
    fun strokeBegin(strokeId: String, band: Int, pen: Pen, color: Int, width: Float) {
        if (!isEnabled) return
        val group = uuidBytes(strokeId) ?: return
        val frag = fragUuid(group, band)
        currentUuid = frag
        lastPageIndex = band
        pointBuffer.clear()
        pointsSent = 0
        sendBegin(frag, group, band, penToTool(pen), colorToRgba(pen, color), width)
    }

    private fun sendBegin(frag: ByteArray, group: ByteArray, band: Int, tool: Byte, rgba: Int, width: Float) {
        val buf = header(MSG_STROKE_BEGIN, 16 + 16 + 2 + 1 + 1 + 4 + 4)
        buf.put(frag)
        buf.put(group)
        buf.putShort(band.coerceIn(0, 65535).toShort())
        buf.put(tool)
        buf.put(0)
        buf.putInt(rgba)
        buf.putFloat(width)
        enqueue(buf)
    }

    /** Distinct 16-byte id for a stroke's fragment on [band] (band 0 == the group id). */
    private fun fragUuid(group: ByteArray, band: Int): ByteArray {
        val f = group.copyOf()
        f[14] = (f[14].toInt() xor ((band ushr 8) and 0xff)).toByte()
        f[15] = (f[15].toInt() xor (band and 0xff)).toByte()
        return f
    }

    /**
     * Stream already-completed strokes (redo, undo-of-erase, paste, page cut/move) as
     * begin+points+end, so the receiver renders them. The live-draw path must NOT call
     * this (it streams incrementally); it passes alreadyStreamed=true to addStrokes.
     */
    /**
     * @param paginatedPageIndex when non-null, the notebook is paginated: every
     *   stroke belongs to this one xournal page and its y is already page-local,
     *   so send it to that page with no y-band splitting. When null (continuous
     *   growable page), map by y-coordinate into pageHeight-tall bands.
     */
    fun streamCompleteStrokes(strokes: List<Stroke>, paginatedPageIndex: Int? = null) {
        if (!isEnabled || strokes.isEmpty()) return
        val sf = scaleFactor
        val ph = pageHeightPt.toFloat()
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            if (paginatedPageIndex != null) {
                sendStrokeFragment(stroke, paginatedPageIndex, sf, ph, pageLocalY = true)
            } else {
                for (band in bandRange(stroke, sf, ph)) sendStrokeFragment(stroke, band, sf, ph)
            }
        }
    }

    /**
     * Send the bands a live-drawn stroke touches EXCEPT its first band (which was already
     * streamed incrementally). Fills in the page(s) below/above the one drawn on when a
     * stroke straddles a page boundary.
     */
    fun streamStrokeExtraBands(stroke: Stroke) {
        if (!isEnabled || stroke.points.isEmpty()) return
        val sf = scaleFactor
        val ph = pageHeightPt.toFloat()
        if (ph <= 0f) return
        val firstBand = floor(stroke.points.first().y * sf / ph).toInt().coerceAtLeast(0)
        for (band in bandRange(stroke, sf, ph)) {
            if (band != firstBand) sendStrokeFragment(stroke, band, sf, ph)
        }
    }

    /** Contiguous range of page bands a stroke's y-extent spans. */
    private fun bandRange(stroke: Stroke, sf: Float, pageHeight: Float): IntRange {
        if (pageHeight <= 0f) return 0..0
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in stroke.points) {
            val y = p.y * sf
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val lo = floor(minY / pageHeight).toInt().coerceAtLeast(0)
        val hi = floor(maxY / pageHeight).toInt().coerceAtLeast(0)
        return lo..hi
    }

    /**
     * Send the whole stroke to one page [band] with y shifted into that page's local frame.
     * xournal's page clipbox clips it to the page, so the part on this page shows and the
     * rest is off-page -- the same render-and-clip Notable's PDF pagination uses.
     */
    private fun sendStrokeFragment(
        stroke: Stroke, band: Int, sf: Float, pageHeight: Float,
        // paginated: stroke y is already page-local, so don't subtract a band offset
        pageLocalY: Boolean = false,
    ) {
        val group = uuidBytes(stroke.id) ?: return
        val frag = fragUuid(group, band)
        val yOffset = if (pageLocalY) 0f else band * pageHeight
        val pts = stroke.points

        sendBegin(frag, group, band, penToTool(stroke.pen),
            colorToRgba(stroke.pen, stroke.color), stroke.size * sf)

        val maxP = stroke.maxPressure.toFloat().takeIf { it > 0f } ?: 4096f
        var i = 0
        while (i < pts.size) {
            val n = minOf(POINTS_PER_DATAGRAM, pts.size - i)
            val buf = header(MSG_POINTS, 16 + 2 + 2 + n * 12)
            buf.put(frag)
            buf.putShort(i.toShort())
            buf.putShort(n.toShort())
            for (j in i until i + n) {
                val p = pts[j]
                buf.putFloat(p.x * sf)
                buf.putFloat(p.y * sf - yOffset)
                buf.putFloat(((p.pressure ?: maxP) / maxP).coerceIn(0f, 1f))
            }
            enqueue(buf)
            i += n
        }

        val end = header(MSG_STROKE_END, 16 + 2)
        end.put(frag)
        end.putShort(pts.size.coerceIn(0, 65535).toShort())
        enqueue(end)
    }

    /**
     * Tell the receiver which page is active: ensure that page exists (creating pages as
     * needed) and scroll xournal to it so the mirror follows the tablet. Sent on page
     * navigation and when streaming is enabled. Discrete 1:1 page mapping.
     */
    fun setActivePage(pageIndex: Int) {
        lastPageIndex = pageIndex
        if (!isEnabled) return
        // index + this tablet's configured page size, so the receiver can warn if the
        // document it opened has a different page size (misaligned strokes).
        val buf = header(MSG_SET_PAGE, 2 + 2 + 2)
        buf.putShort(pageIndex.coerceIn(0, 65535).toShort())
        buf.putShort(pageWidthPt.coerceIn(0, 65535).toShort())
        buf.putShort(pageHeightPt.coerceIn(0, 65535).toShort())
        enqueue(buf)
    }

    /**
     * Tell the receiver which strokes are now committed to the synced file.
     * Sent after a successful Dropbox upload: [sha256] is the hash of the
     * uploaded bytes, [strokeIds] the Notable stroke ids included in it. When
     * the file with that hash reaches the receiver's disk (Dropbox sync), it
     * reloads the document and drops exactly those overlay strokes.
     */
    fun syncMarker(sha256: ByteArray, strokeIds: List<String>) {
        if (!isEnabled || sha256.size != 32) return
        // every chunk repeats the hash; 32+2+16*80 = 1314 < MTU
        val chunks = if (strokeIds.isEmpty()) listOf(emptyList()) else strokeIds.chunked(80)
        chunks.forEach { chunk ->
            val uuids = chunk.mapNotNull { uuidBytes(it) }
            val buf = header(MSG_SYNC_MARKER, 32 + 2 + 16 * uuids.size)
            buf.put(sha256)
            buf.putShort(uuids.size.toShort())
            uuids.forEach { buf.put(it) }
            enqueueRepeated(buf) // idempotent; also self-heals via the next upload
        }
    }

    /**
     * Tell the receiver to remove strokes with these Notable ids (erase /
     * scribble-erase). Sent [LOSSY_REPEATS] times, spaced out: DELETE is
     * idempotent on the receiver, and a single lost datagram would otherwise
     * leave phantom ink on the mirror that even reloads preserve (erased
     * strokes are absent from uploads, so sync markers never drop them).
     */
    fun deleteStrokes(strokeIds: List<String>, repeated: Boolean = true) {
        if (!isEnabled || strokeIds.isEmpty()) return
        // chunk so each datagram stays well under the MTU (2 + 16*n bytes)
        strokeIds.chunked(64).forEach { chunk ->
            val uuids = chunk.mapNotNull { uuidBytes(it) }
            if (uuids.isEmpty()) return@forEach
            val buf = header(MSG_DELETE, 2 + 16 * uuids.size)
            buf.putShort(uuids.size.toShort())
            uuids.forEach { buf.put(it) }
            // Erase: repeat for reliability. Update (delete-then-re-add): single
            // shot -- the spaced repeats would arrive AFTER the re-added stroke
            // and delete it, making updated strokes vanish from the mirror.
            if (repeated) enqueueRepeated(buf) else enqueue(buf)
        }
    }

    /** x,y in xoj page points; pressure normalized 0..1. */
    fun strokePoint(x: Float, y: Float, pressure: Float) {
        if (currentUuid == null) return
        pointBuffer.add(x)
        pointBuffer.add(y)
        pointBuffer.add(pressure)
        if (pointBuffer.size / 3 >= flushEvery) flushPoints()
    }

    fun strokeEnd() {
        val uuid = currentUuid ?: return
        flushPoints()
        val buf = header(MSG_STROKE_END, 16 + 2)
        buf.put(uuid)
        buf.putShort(pointsSent.coerceIn(0, 65535).toShort())
        enqueue(buf)
        currentUuid = null
    }

    private fun flushPoints() {
        val uuid = currentUuid ?: return
        val count = pointBuffer.size / 3
        if (count == 0) return
        val buf = header(MSG_POINTS, 16 + 2 + 2 + count * 12)
        buf.put(uuid)
        buf.putShort(pointsSent.toShort())
        buf.putShort(count.toShort())
        for (f in pointBuffer) buf.putFloat(f)
        enqueue(buf)
        pointsSent += count
        pointBuffer.clear()
    }

    // ---- transport ----

    private fun enqueue(buf: ByteBuffer) {
        sendChannel.trySend(buf.array())
    }

    /** Send now plus spaced repeats, for idempotent must-arrive messages (UDP loss). */
    private fun enqueueRepeated(buf: ByteBuffer) {
        val data = buf.array()
        sendChannel.trySend(data)
        scope.launch {
            for (delayMs in REPEAT_DELAYS_MS) {
                kotlinx.coroutines.delay(delayMs)
                sendChannel.trySend(data)
            }
        }
    }

    private fun trySend(data: ByteArray) {
        try {
            val s = socket ?: DatagramSocket().also { socket = it }
            val settings = _settings.value
            var addr = targetAddress
            if (addr == null || targetPort != settings.port) {
                addr = InetAddress.getByName(settings.host)
                targetAddress = addr
                targetPort = settings.port
            }
            s.send(DatagramPacket(data, data.size, addr, settings.port))
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            closeSocket()
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        targetAddress = null
    }

    // ---- encoding helpers ----

    private fun header(msgType: Int, payloadLen: Int): ByteBuffer {
        // v1: XINK | ver | type. v2 (hub sessions): XINK | 2 | type | token[8].
        // The receiver only accepts v2-with-matching-token once a token is set.
        val token = tokenBytes
        val headerLen = if (token != null) 14 else 6
        val buf = ByteBuffer.allocate(headerLen + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('X'.code.toByte())
        buf.put('I'.code.toByte())
        buf.put('N'.code.toByte())
        buf.put('K'.code.toByte())
        buf.put(if (token != null) 2 else PROTO_VERSION)
        buf.put(msgType.toByte())
        if (token != null) buf.put(token)
        return buf
    }

    /** Encode a UUID string as 16 big-endian bytes, or null if it isn't a UUID. */
    private fun uuidBytes(id: String): ByteArray? {
        return try {
            val u = UUID.fromString(id)
            ByteBuffer.allocate(16)
                .putLong(u.mostSignificantBits)
                .putLong(u.leastSignificantBits)
                .array()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InkStreamEntryPoint {
        fun inkStreamClient(): InkStreamClient
    }

    companion object {
        fun from(context: Context): InkStreamClient =
            EntryPointAccessors.fromApplication(
                context.applicationContext, InkStreamEntryPoint::class.java
            ).inkStreamClient()

        private const val PROTO_VERSION: Byte = 1
        private const val MSG_STROKE_BEGIN = 2
        private const val MSG_POINTS = 3
        private const val MSG_STROKE_END = 4
        private const val MSG_DELETE = 5
        private const val MSG_SET_PAGE = 6
        private const val MSG_SYNC_MARKER = 7

        // points per POINTS datagram for complete-stroke streaming: 14+16+4+100*12 = 1234 < MTU
        private const val POINTS_PER_DATAGRAM = 100

        // spaced repeats for idempotent must-arrive messages (DELETE, SYNC_MARKER)
        private val REPEAT_DELAYS_MS = longArrayOf(150, 450)

        private const val TOOL_PEN = 0
        private const val TOOL_HIGHLIGHTER = 2

        fun penToTool(pen: Pen): Byte =
            if (pen == Pen.MARKER) TOOL_HIGHLIGHTER.toByte() else TOOL_PEN.toByte()

        /** Android ARGB int -> xournal 0xRRGGBBAA. Highlighter forced translucent. */
        fun colorToRgba(pen: Pen, argb: Int): Int {
            val a = if (pen == Pen.MARKER) 0x7f else (argb ushr 24) and 0xff
            val rgb = argb and 0x00ffffff
            return (rgb shl 8) or a
        }
    }
}
