package com.ethran.notable.ink

import android.content.Context
import android.util.Log
import com.ethran.notable.data.db.KvProxy
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

    init {
        scope.launch {
            for (datagram in sendChannel) trySend(datagram)
        }
    }

    fun loadSettings() {
        scope.launch {
            val loaded = kvProxy.get(INK_STREAM_SETTINGS_KEY, InkStreamSettings.serializer())
            if (loaded != null) _settings.value = loaded
        }
    }

    fun saveSettings(settings: InkStreamSettings) {
        _settings.value = settings
        // force re-resolution of address/socket on next stroke
        closeSocket()
        scope.launch {
            kvProxy.setKv(INK_STREAM_SETTINGS_KEY, settings, InkStreamSettings.serializer())
        }
    }

    // ---- stroke lifecycle (called from the drawing thread) ----

    fun strokeBegin(pageIndex: Int, pen: Pen, color: Int, width: Float) {
        if (!isEnabled) return
        val uuid = newUuid()
        currentUuid = uuid
        pointBuffer.clear()
        pointsSent = 0

        val buf = header(MSG_STROKE_BEGIN, 16 + 2 + 1 + 1 + 4 + 4)
        buf.put(uuid)
        buf.putShort(pageIndex.coerceIn(0, 65535).toShort())
        buf.put(penToTool(pen))
        buf.put(0)
        buf.putInt(colorToRgba(pen, color))
        buf.putFloat(width)
        enqueue(buf)
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
        val buf = ByteBuffer.allocate(6 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('X'.code.toByte())
        buf.put('I'.code.toByte())
        buf.put('N'.code.toByte())
        buf.put('K'.code.toByte())
        buf.put(PROTO_VERSION)
        buf.put(msgType.toByte())
        return buf
    }

    private fun newUuid(): ByteArray {
        val u = UUID.randomUUID()
        return ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
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
