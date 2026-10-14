package com.douglas.ipodmux

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cliente AFC (Apple File Conduit): el servicio de archivos del iPod (raiz = /var/mobile/Media). */
class Afc(private val inS: InputStream, private val outS: OutputStream, private val log: (String) -> Unit) {
    private var pktNum = 0L

    class Resp(val op: Long, val data: ByteArray)

    private fun le64(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun cstr(s: String): ByteArray = s.toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    private fun request(op: Long, header: ByteArray, payload: ByteArray = ByteArray(0)): Resp? {
        try {
            val thisLen = 40 + header.size
            val entire = thisLen + payload.size
            val bb = ByteBuffer.allocate(entire).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("CFA6LPAA".toByteArray(Charsets.US_ASCII))
            bb.putLong(entire.toLong())
            bb.putLong(thisLen.toLong())
            bb.putLong(pktNum++)
            bb.putLong(op)
            bb.put(header)
            bb.put(payload)
            outS.write(bb.array())
            outS.flush()
            val h = readFully(inS, 40) ?: return null
            val hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val entireR = hb.getLong(8)
            val rop = hb.getLong(32)
            val n = (entireR - 40).toInt()
            if (n < 0 || n > (64 shl 20)) {
                log("AFC: respuesta con largo raro ($entireR)")
                return null
            }
            val body = if (n == 0) ByteArray(0) else (readFully(inS, n) ?: return null)
            return Resp(rop, body)
        } catch (e: Exception) {
            log("AFC: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    private fun status(r: Resp): Long =
        if (r.data.size >= 8) ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN).getLong(0) else -1

    fun listDir(path: String): List<String>? {
        val r = request(0x03, cstr(path)) ?: return null
        if (r.op != 2L) {
            log("AFC listDir $path -> op=${r.op} estado=${status(r)}")
            return null
        }
        return String(r.data, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() && it != "." && it != ".." }
    }

    fun fileInfo(path: String): Map<String, String>? {
        val r = request(0x0A, cstr(path)) ?: return null
        if (r.op != 2L) return null
        val parts = String(r.data, Charsets.UTF_8).split('\u0000')
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < parts.size) {
            m[parts[i]] = parts[i + 1]
            i += 2
        }
        return m
    }

    fun open(path: String, mode: Long = 1): Long? {
        val r = request(0x0D, le64(mode) + cstr(path)) ?: return null
        if (r.op != 0x0EL || r.data.size < 8) {
            log("AFC open $path -> op=${r.op} estado=${status(r)}")
            return null
        }
        return ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
    }

    fun read(handle: Long, n: Int): ByteArray? {
        val r = request(0x0F, le64(handle) + le64(n.toLong())) ?: return null
        if (r.op == 2L) return r.data
        if (r.op == 1L && status(r) == 0L) return ByteArray(0)
        log("AFC read -> op=${r.op} estado=${status(r)}")
        return null
    }

    fun close(handle: Long) {
        request(0x14, le64(handle))
    }

    /** Copia un archivo del iPod a [out]. [progress] puede lanzar una excepcion para cancelar. */
    fun readFile(path: String, out: OutputStream, progress: ((Long, Long) -> Unit)? = null): Boolean {
        val size = fileInfo(path)?.get("st_size")?.toLongOrNull() ?: -1L
        val h = open(path) ?: return false
        var total = 0L
        try {
            while (size < 0 || total < size) {
                val d = read(h, 65536) ?: return false
                if (d.isEmpty()) break
                out.write(d)
                total += d.size
                progress?.invoke(total, size)
            }
        } finally {
            close(h)
        }
        return true
    }

    fun readAll(path: String): ByteArray? {
        val o = ByteArrayOutputStream()
        return if (readFile(path, o)) o.toByteArray() else null
    }
}
