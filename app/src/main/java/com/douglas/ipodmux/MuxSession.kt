package com.douglas.ipodmux

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun hex(b: ByteArray, max: Int = 64): String {
    val n = minOf(b.size, max)
    val sb = StringBuilder()
    for (i in 0 until n) sb.append(String.format("%02x", b[i].toInt() and 0xff))
    if (b.size > n) sb.append("...(+${b.size - n})")
    return sb.toString()
}

fun lockdownXml(request: String, key: String? = null): String {
    val extra = if (key != null) "<key>Key</key><string>$key</string>" else ""
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
        "<plist version=\"1.0\">\n<dict><key>Request</key><string>$request</string>$extra" +
        "<key>Label</key><string>ipodmux</string></dict>\n</plist>\n"
}

/** Capa usbmux (el protocolo que usa iTunes sobre USB) + un TCP minimo encima. */
class MuxSession(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val log: (String) -> Unit
) {
    var version = -1
        private set
    private var txSeq = 0
    private var rxSeq = 0xFFFF
    private var pending = ByteArray(0)

    class Packet(val proto: Int, val payload: ByteArray)

    private fun headerLen(proto: Int) = if (proto == 0 || version < 2) 8 else 16

    private fun send(proto: Int, payload: ByteArray): Boolean {
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
            return false
        }
        return true
    }

    private fun parseOne(): Packet? {
        if (pending.size < 8) return null
        val bb = ByteBuffer.wrap(pending).order(ByteOrder.BIG_ENDIAN)
        val proto = bb.getInt(0)
        val len = bb.getInt(4)
        if (len < 8 || len > (1 shl 20)) {
            log("Paquete con longitud rara ($len). Buffer: ${hex(pending)}")
            pending = ByteArray(0)
            return null
        }
        if (pending.size < len) return null
        val hl = headerLen(proto)
        if (len < hl) {
            log("Paquete mas corto que su cabecera: ${hex(pending)}")
            pending = pending.copyOfRange(len, pending.size)
            return null
        }
        if (hl == 16) rxSeq = bb.getShort(12).toInt() and 0xFFFF
        val payload = pending.copyOfRange(hl, len)
        pending = pending.copyOfRange(len, pending.size)
        return Packet(proto, payload)
    }

    private fun recv(timeoutMs: Int): Packet? {
        val end = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(16384)
        while (true) {
            val p = parseOne()
            if (p != null) return p
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            val n = conn.bulkTransfer(epIn, buf, buf.size, minOf(left, 500))
            if (n > 0) pending += buf.copyOf(n)
        }
    }

    fun handshake(): Boolean {
        val vp = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(2).putInt(0).putInt(0).array()
        log("-> VERSION (major=2)")
        if (!send(0, vp)) return false
        val p = recv(4000)
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

    // ---- TCP minimo ----
    private var sport = 1
    private var dport = 0
    private var seqOut = 0L
    private var ackOut = 0L

    private fun tcpBytes(flags: Int, data: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(20 + data.size).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(sport.toShort())
        bb.putShort(dport.toShort())
        bb.putInt(seqOut.toInt())
        bb.putInt(ackOut.toInt())
        bb.put(0x50.toByte())
        bb.put(flags.toByte())
        bb.putShort(512.toShort())
        bb.putShort(0.toShort())
        bb.putShort(0.toShort())
        bb.put(data)
        return bb.array()
    }

    fun tcpConnect(port: Int): Boolean {
        dport = port
        seqOut = 0
        ackOut = 0
        log("-> SYN al puerto $port")
        if (!send(6, tcpBytes(0x02, ByteArray(0)))) return false
        val end = System.currentTimeMillis() + 4000
        while (true) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) {
                log("Sin SYN/ACK en 4 s")
                return false
            }
            val p = recv(left)
            if (p == null) {
                log("Sin SYN/ACK en 4 s")
                return false
            }
            if (p.proto != 6 || p.payload.size < 20) {
                log("<- ignoro proto=${p.proto} ${hex(p.payload)}")
                continue
            }
            val bb = ByteBuffer.wrap(p.payload).order(ByteOrder.BIG_ENDIAN)
            val flags = bb.get(13).toInt() and 0xff
            log("<- TCP flags=0x${flags.toString(16)}")
            if ((flags and 0x04) != 0) {
                log("El iPod rechazo la conexion (RST)")
                return false
            }
            if ((flags and 0x12) == 0x12) {
                ackOut = (bb.getInt(4).toLong() and 0xffffffffL) + 1
                seqOut = 1
                send(6, tcpBytes(0x10, ByteArray(0)))
                log("Conexion TCP establecida")
                return true
            }
        }
    }

    private fun tcpSend(data: ByteArray): Boolean {
        var off = 0
        while (off < data.size) {
            val n = minOf(4096, data.size - off)
            val chunk = data.copyOfRange(off, off + n)
            if (!send(6, tcpBytes(0x10, chunk))) return false
            seqOut += n
            off += n
        }
        return true
    }

    private fun tcpRecv(timeoutMs: Int): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            val p = recv(left) ?: return null
            if (p.proto != 6 || p.payload.size < 20) continue
            val bb = ByteBuffer.wrap(p.payload).order(ByteOrder.BIG_ENDIAN)
            val off = ((bb.get(12).toInt() and 0xf0) shr 4) * 4
            val flags = bb.get(13).toInt() and 0xff
            if ((flags and 0x04) != 0) {
                log("RST recibido")
                return null
            }
            val data = p.payload.copyOfRange(minOf(off, p.payload.size), p.payload.size)
            if (data.isNotEmpty()) {
                ackOut += data.size
                send(6, tcpBytes(0x10, ByteArray(0)))
                return data
            }
        }
    }

    fun lockdownRequest(xml: String): String? {
        val body = xml.toByteArray()
        val msg = ByteBuffer.allocate(4 + body.size).order(ByteOrder.BIG_ENDIAN).putInt(body.size).put(body).array()
        if (!tcpSend(msg)) return null
        var buf = ByteArray(0)
        var need = -1
        val end = System.currentTimeMillis() + 5000
        while (true) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) {
                log("Respuesta incompleta (${buf.size} bytes)")
                return null
            }
            val d = tcpRecv(left) ?:
