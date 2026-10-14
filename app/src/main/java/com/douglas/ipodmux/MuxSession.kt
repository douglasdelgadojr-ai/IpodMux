package com.douglas.ipodmux

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun hex(b: ByteArray, max: Int = 64): String {
    val n = minOf(b.size, max)
    val sb = StringBuilder()
    for (i in 0 until n) sb.append(String.format("%02x", b[i].toInt() and 0xff))
    if (b.size > n) sb.append("...(+${b.size - n})")
    return sb.toString()
}

/** Una conexion TCP (de usbmux) con un servicio del iPod. Expone streams normales. */
class MuxConn(private val mux: Mux, val sport: Int, val dport: Int) {
    @Volatile var seqOut = 0L
    @Volatile var ackOut = 0L
    @Volatile var closed = false
    @Volatile var refused = false
    @Volatile var readTimeoutMs = 20000
    val established = CountDownLatch(1)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val eofMarker = ByteArray(0)

    internal fun deliver(data: ByteArray) { queue.add(data) }

    internal fun markClosed() {
        if (!closed) {
            closed = true
            queue.add(eofMarker)
        }
        established.countDown()
    }

    val input: InputStream = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xff)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val tmo = readTimeoutMs
            val deadline = if (tmo > 0) System.currentTimeMillis() + tmo else Long.MAX_VALUE
            while (cur == null || pos >= cur!!.size) {
                if (eof) return -1
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw SocketTimeoutException("usbmux: tiempo de espera agotado")
                val n = queue.poll(minOf(remaining, 500L), TimeUnit.MILLISECONDS) ?: continue
                if (n === eofMarker) {
                    eof = true
                    return -1
                }
                cur = n
                pos = 0
            }
            val c = cur!!
            val k = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, k)
            pos += k
            return k
        }

        override fun available(): Int {
            val c = cur
            return if (c == null) 0 else c.size - pos
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("usbmux: conexion cerrada")
            var o = 0
            while (o < len) {
                val n = minOf(4096, len - o)
                if (!mux.sendTcp(this@MuxConn, 0x10, b.copyOfRange(off + o, off + o + n), true)) {
                    throw IOException("usbmux: fallo al enviar por USB")
                }
                o += n
            }
        }
    }

    fun close() {
        if (!closed) mux.sendTcp(this, 0x04, ByteArray(0), false)
        markClosed()
        mux.remove(this)
    }
}

/** Capa usbmux (el protocolo que usa iTunes sobre USB) con varias conexiones TCP encima. */
class Mux(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val log: (String) -> Unit
) {
    var version = -1
        private set
    private var txSeq = 0
    @Volatile private var rxSeq = 0xFFFF
    private val sendLock = Any()
    private var rbuf = ByteArray(1 shl 16)
    private var rlen = 0
    private val inBuf = ByteArray(16384)
    private val conns = ConcurrentHashMap<Int, MuxConn>()
    @Volatile private var running = false
    private var nextPort = 1

    class Packet(val proto: Int, val payload: ByteArray)

    private fun headerLen(proto: Int) = if (proto == 0 || version < 2) 8 else 16

    private fun send(proto: Int, payload: ByteArray): Boolean = synchronized(sendLock) {
        val hl = headerLen(proto)
        val bb = ByteBuffer.allocate(hl + payload.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(proto)
        bb.putInt(hl + payload.size)
        if (hl == 16) {
            bb.putInt(0xfeedface.toInt())
            bb.putShort(txSeq.toShort())
            bb.putShort(rxSeq.toShort())
            txSeq = (txSeq + 1) and 0xFFFF
        }
        bb.put(payload)
        val data = bb.array()
        val r = conn.bulkTransfer(epOut, data, data.size, 3000)
        if (r != data.size) {
            log("ERROR enviando por bulk: devolvio $r de ${data.size}")
            false
        } else true
    }

    private fun ensure(n: Int) {
        if (n > rbuf.size) rbuf = rbuf.copyOf(maxOf(n, rbuf.size * 2))
    }

    private fun consume(n: Int) {
        System.arraycopy(rbuf, n, rbuf, 0, rlen - n)
        rlen -= n
    }

    private fun parseOne(): Packet? {
        if (rlen < 8) return null
        val bb = ByteBuffer.wrap(rbuf, 0, rlen).order(ByteOrder.BIG_ENDIAN)
        val proto = bb.getInt(0)
        val len = bb.getInt(4)
        if (len < 8 || len > (1 shl 20)) {
            log("Paquete con longitud rara ($len). Buffer: ${hex(rbuf.copyOf(minOf(rlen, 32)))}")
            rlen = 0
            return null
        }
        if (rlen < len) return null
        val hl = headerLen(proto)
        if (len < hl) {
            log("Paquete mas corto que su cabecera")
            consume(len)
            return null
        }
        if (hl == 16) rxSeq = bb.getShort(12).toInt() and 0xFFFF
        val payload = rbuf.copyOfRange(hl, len)
        consume(len)
        return Packet(proto, payload)
    }

    private fun readPacket(timeoutMs: Int): Packet? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val p = parseOne()
            if (p != null) return p
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            val n = conn.bulkTransfer(epIn, inBuf, inBuf.size, minOf(left, 300))
            if (n > 0) {
                ensure(rlen + n)
                System.arraycopy(inBuf, 0, rbuf, rlen, n)
                rlen += n
            }
        }
    }

    fun handshake(): Boolean {
        val vp = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(2).putInt(0).putInt(0).array()
        log("-> VERSION (major=2)")
        if (!send(0, vp)) return false
        val p = readPacket(4000)
        if (p == null) {
            log("Sin respuesta al VERSION en 4 s")
            return false
        }
        log("<- proto=${p.proto} payload=${hex(p.payload)}")
        if (p.proto != 0 || p.payload.size < 8) {
            log("Respuesta inesperada al VERSION")
            return false
        }
        version = ByteBuffer.wrap(p.payload).order(ByteOrder.BIG_ENDIAN).getInt(0)
        log("Version usbmux del iPod: $version")
        if (version >= 2) {
            log("-> SETUP")
            send(2, byteArrayOf(7))
        }
        return true
    }

    /** Despues del handshake: un hilo lee paquetes y los reparte a cada conexion. */
    fun start() {
        running = true
        val t = Thread { readerLoop() }
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        for (c in conns.values) c.markClosed()
        conns.clear()
    }

    private fun readerLoop() {
        while (running) {
            val p = try {
                readPacket(300)
            } catch (e: Exception) {
                log("Lector USB: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            if (p == null) continue
            try {
                dispatch(p)
            } catch (e: Exception) {
                log("Error procesando paquete: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        for (c in conns.values) c.markClosed()
    }

    private fun dispatch(p: Packet) {
        if (p.proto != 6) {
            log("<- paquete usbmux proto=${p.proto} ${hex(p.payload, 32)}")
            return
        }
        val pl = p.payload
        if (pl.size < 20) return
        val bb = ByteBuffer.wrap(pl).order(ByteOrder.BIG_ENDIAN)
        val dp = bb.getShort(2).toInt() and 0xffff
        val seq = bb.getInt(4).toLong() and 0xffffffffL
        val off = ((pl[12].toInt() and 0xf0) shr 4) * 4
        val flags = pl[13].toInt() and 0xff
        val c = conns[dp] ?: return
        if ((flags and 0x04) != 0) {
            log("RST recibido (puerto ${c.dport})")
            c.refused = true
            c.markClosed()
            conns.remove(dp)
            return
        }
        if ((flags and 0x12) == 0x12 && c.seqOut == 0L) {
            c.ackOut = seq + 1
            c.seqOut = 1
            sendTcp(c, 0x10, ByteArray(0), false)
            c.established.countDown()
            return
        }
        val data = pl.copyOfRange(minOf(off, pl.size), pl.size)
        if (data.isNotEmpty()) {
            c.ackOut += data.size
            sendTcp(c, 0x10, ByteArray(0), false)
            c.deliver(data)
        }
        if ((flags and 0x01) != 0) c.markClosed()
    }

    internal fun sendTcp(c: MuxConn, flags: Int, data: ByteArray, advance: Boolean): Boolean = synchronized(sendLock) {
        val bb = ByteBuffer.allocate(20 + data.size).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(c.sport.toShort())
        bb.putShort(c.dport.toShort())
        bb.putInt(c.seqOut.toInt())
        bb.putInt(c.ackOut.toInt())
        bb.put(0x50.toByte())
        bb.put(flags.toByte())
        bb.putShort(512.toShort())
        bb.putShort(0.toShort())
        bb.putShort(0.toShort())
        bb.put(data)
        val ok = send(6, bb.array())
        if (ok && advance) c.seqOut += data.size
        ok
    }

    internal fun remove(c: MuxConn) {
        conns.remove(c.sport)
    }

    fun open(port: Int, timeoutMs: Long = 4000): MuxConn? {
        val sport = synchronized(this) { nextPort++ }
        val c = MuxConn(this, sport, port)
        conns[sport] = c
        log("-> SYN al puerto $port (origen $sport)")
        if (!sendTcp(c, 0x02, ByteArray(0), false)) {
            conns.remove(sport)
            return null
        }
        val ok = c.established.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            log("Sin SYN/ACK en ${timeoutMs / 1000} s")
            conns.remove(sport)
            return null
        }
        if (c.refused || c.closed) {
            log("El iPod rechazo la conexion al puerto $port")
            conns.remove(sport)
            return null
        }
        log("Conexion TCP establecida (puerto $port)")
        return c
    }
}
