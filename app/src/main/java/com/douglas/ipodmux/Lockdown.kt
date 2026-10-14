package com.douglas.ipodmux

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

fun readFully(s: InputStream, n: Int): ByteArray? {
    val b = ByteArray(n)
    var o = 0
    while (o < n) {
        val k = s.read(b, o, n - o)
        if (k < 0) return null
        o += k
    }
    return b
}

/**
 * Convierte un par de streams (los de usbmux) en un SSLSocket normal:
 * abre un socket local, hace el puente de bytes hacia usbmux y pone TLS encima.
 */
class TlsBridge(
    private val ctx: SSLContext,
    private val inS: InputStream,
    private val outS: OutputStream,
    private val protocols: Array<String>? = arrayOf("TLSv1"),
    private val pickSuites: ((Array<String>) -> Array<String>)? = null,
    private val sni: Boolean = true,
    private val log: (String) -> Unit = {}
) {
    private fun pump(from: InputStream, to: OutputStream, s: Socket) {
        val buf = ByteArray(16384)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    fun connect(): SSLSocket {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = ss.localPort
        val acceptor = Thread {
            try {
                val s = ss.accept()
                ss.close()
                s.tcpNoDelay = true
                val t1 = Thread { pump(s.getInputStream(), outS, s) }
                val t2 = Thread { pump(inS, s.getOutputStream(), s) }
                t1.isDaemon = true
                t2.isDaemon = true
                t1.start()
                t2.start()
            } catch (e: Exception) {
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
        val raw = Socket("127.0.0.1", port)
        raw.tcpNoDelay = true
        val host: String? = if (sni) "ipod" else null
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.useClientMode = true
        if (protocols != null) ssl.enabledProtocols = protocols
        val pick = pickSuites
        if (pick != null) {
            val sel = pick(ssl.supportedCipherSuites)
            log("Suites elegidas: ${sel.joinToString()}")
            if (sel.isEmpty()) throw IOException("este Android no ofrece esas suites")
            ssl.enabledCipherSuites = sel
        }
        ssl.soTimeout = 15000
        ssl.startHandshake()
        return ssl
    }
}

/** Cliente de lockdownd: paquetes de 4 bytes de largo + plist XML. */
class Lockdown(private val log: (String) -> Unit, private var inS: InputStream, private var outS: OutputStream) {
    fun switchStreams(i: InputStream, o: OutputStream) {
        inS = i
        outS = o
    }

    fun request(dict: Map<String, Any?>): Map<String, Any?>? {
        try {
            val xml = Plist.build(dict).toByteArray(Charsets.UTF_8)
            val hdr = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(xml.size).array()
            val msg = ByteArray(4 + xml.size)
            System.arraycopy(hdr, 0, msg, 0, 4)
            System.arraycopy(xml, 0, msg, 4, xml.size)
            outS.write(msg)
            outS.flush()
            val h = readFully(inS, 4)
            if (h == null) {
                log("lockdownd: el iPod cerro la conexion")
                return null
            }
            val n = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN).getInt(0)
            if (n < 0 || n > (4 shl 20)) {
                log("lockdownd: largo raro $n")
                return null
            }
            val body = readFully(inS, n) ?: return null
            return Plist.parseMap(String(body, Charsets.UTF_8))
        } catch (e: IOException) {
            log("lockdownd: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    private fun base(req: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["Request"] = req
        m["Label"] = "ipodmux"
        m.putAll(extra)
        return m
    }

    fun queryType(): String? = request(base("QueryType"))?.get("Type") as? String

    fun getValue(key: String): Any? {
        val r = request(base("GetValue", mapOf("Key" to key))) ?: return null
        val err = r["Error"]
        if (err != null) {
            log("GetValue $key -> $err")
            return null
        }
        return r["Value"]
    }

    fun validatePair(rec: PairRecord): Boolean {
        val r = request(base("ValidatePair", mapOf("PairRecord" to rec.toPlist()))) ?: return false
        log("ValidatePair -> ${r["Result"] ?: r["Error"]}")
        return r["Error"] == null && r["Result"] == "Success"
    }

    /** Devuelve null si salio bien, o el texto del error. */
    fun pair(rec: PairRecord): String? {
        val r = request(base("Pair", mapOf("ProtocolVersion" to "2", "PairRecord" to rec.toPlist())))
            ?: return "sin respuesta"
        val err = r["Error"]
        if (err != null) return err.toString()
        return if (r["Result"] == null || r["Result"] == "Success") null else r["Result"].toString()
    }

    fun startSession(rec: PairRecord): Map<String, Any?>? =
        request(base("StartSession", mapOf("HostID" to rec.hostId, "SystemBUID" to rec.systemBuid)))

    fun startService(name: String): Map<String, Any?>? =
        request(base("StartService", mapOf("Service" to name)))
}
