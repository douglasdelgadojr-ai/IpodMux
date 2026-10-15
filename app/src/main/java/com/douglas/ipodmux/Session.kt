package com.douglas.ipodmux

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.OutputStream
import javax.net.ssl.SSLSocket

/** Mantiene viva la conexion con el iPod (USB -> usbmux -> lockdownd -> AFC) mientras la app esta abierta. */
object IpodSession {
    @Volatile var library: Library? = null
    @Volatile var deviceName: String = ""
    @Volatile var connecting = false

    private var usbConn: UsbDeviceConnection? = null
    private var usbIface: UsbInterface? = null
    private var mux: Mux? = null
    private var afc: Afc? = null
    private val sockets = ArrayList<SSLSocket>()
    private val afcLock = Any()

    fun isConnected(): Boolean = afc != null

    private fun fail(log: (String) -> Unit, msg: String): Boolean {
        log(msg)
        disconnect()
        return false
    }

    fun disconnect() {
        synchronized(afcLock) { afc = null }
        for (s in sockets) try { s.close() } catch (e: Exception) {}
        sockets.clear()
        try { mux?.stop() } catch (e: Exception) {}
        mux = null
        try {
            val i = usbIface
            if (i != null) usbConn?.releaseInterface(i)
        } catch (e: Exception) {}
        try { usbConn?.close() } catch (e: Exception) {}
        usbConn = null
        usbIface = null
        library = null
    }

    /** Lee un archivo del iPod. Una sola operacion AFC a la vez. */
    fun download(path: String, out: OutputStream, progress: (Long, Long) -> Unit): Boolean =
        synchronized(afcLock) {
            val a = afc
            if (a == null) false else a.readFile(path, out, progress)
        }

    /** Bloqueante: llamar desde un hilo. Devuelve true si la biblioteca quedo cargada. */
    fun connect(ctx: Context, usb: UsbManager, dev: UsbDevice, log: (String) -> Unit): Boolean {
        connecting = true
        try {
            return doConnect(ctx, usb, dev, log)
        } catch (e: Exception) {
            return fail(log, "EXCEPCION: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connecting = false
        }
    }

    private fun doConnect(ctx: Context, usb: UsbManager, dev: UsbDevice, log: (String) -> Unit): Boolean {
        disconnect()
        log(String.format("Dispositivo %s VID=%04x PID=%04x", dev.deviceName, dev.vendorId, dev.productId))

        // PASO 1-2: buscar la interfaz usbmux, cambiar a su configuracion y reclamarla
        var iface: UsbInterface? = null
        var cfg: UsbConfiguration? = null
        for (c in 0 until dev.configurationCount) {
            val cf = dev.getConfiguration(c)
            for (i in 0 until cf.interfaceCount) {
                val f = cf.getInterface(i)
                if (f.interfaceClass == 255 && f.interfaceSubclass == 254 && f.interfaceProtocol == 2) {
                    iface = f
                    cfg = cf
                }
            }
        }
        val ifc = iface
        val cf = cfg
        if (ifc == null || cf == null) return fail(log, "FALLO PASO 1: no encontre la interfaz usbmux")
        val conn = usb.openDevice(dev) ?: return fail(log, "FALLO PASO 2: openDevice devolvio null")
        usbConn = conn
        log("setConfiguration(id=${cf.id}) = ${conn.setConfiguration(cf)}")
        if (!conn.claimInterface(ifc, true)) return fail(log, "FALLO PASO 2: no pude reclamar la interfaz")
        usbIface = ifc
        log("PASO 2 OK: interfaz reclamada")

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (e in 0 until ifc.endpointCount) {
            val ep = ifc.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
        val inEp = epIn
        val outEp = epOut
        if (inEp == null || outEp == null) return fail(log, "FALLO: no encontre endpoints bulk")

        // PASO 3: usbmux
        val m = Mux(conn, inEp, outEp, log)
        mux = m
        if (!m.handshake()) return fail(log, "FALLO PASO 3: el iPod no respondio al handshake usbmux")
        m.start()
        log("PASO 3 OK: handshake usbmux")

        // PASO 4-5: lockdownd
        val lc = m.open(62078) ?: return fail(log, "FALLO PASO 4: no pude abrir lockdownd")
        val ld = Lockdown(log, lc.input, lc.output)
        log("PASO 5: lockdownd tipo = ${ld.queryType()}")
        deviceName = (ld.getValue("DeviceName") as? String) ?: "iPod"
        log("iPod: $deviceName, iOS ${ld.getValue("ProductVersion")}")

        // PASO 6: emparejamiento (una sola vez; queda guardado en la radio)
        val prefs = ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE)
        val saved = HashMap<String, String?>()
        for (k in listOf("hostId", "systemBuid", "root", "host", "device", "key")) saved[k] = prefs.getString(k, null)
        var rec: PairRecord? = PairRecord.fromFields(saved)
        var paired = false
        if (rec != null) {
            paired = ld.validatePair(rec)
            log("Emparejamiento guardado valido: $paired")
        }
        if (!paired) {
            log("PASO 6: emparejando (la primera vez tarda unos segundos, desbloquea el iPod)...")
            val pubBytes = ld.getValue("DevicePublicKey") as? ByteArray
                ?: return fail(log, "FALLO PASO 6: el iPod no entrego su llave publica")
            val pub = Certs.parseDevicePublicKey(String(pubBytes, Charsets.US_ASCII))
            log("Generando claves y certificados...")
            val r = PairRecord.create(pub)
            val err = ld.pair(r)
            if (err != null) {
                val hint = if (err.contains("Password", true)) " (el iPod tiene codigo: desbloquealo y reintenta)" else ""
                return fail(log, "FALLO PASO 6: Pair -> $err$hint")
            }
            val ed = prefs.edit()
            for ((k, v) in r.toFields()) ed.putString(k, v)
            ed.apply()
            rec = r
            log("PASO 6 OK: emparejado")
        }
        val pr = rec ?: return fail(log, "FALLO PASO 6: sin registro de emparejamiento")

        // PASO 7: sesion con TLS (implementacion propia: MiniTls)
        val ss = ld.startSession(pr) ?: return fail(log, "FALLO PASO 7: StartSession sin respuesta")
        if (ss["Error"] != null) {
            if (ss["Error"].toString().contains("InvalidHostID")) {
                prefs.edit().clear().apply()
            }
            return fail(log, "FALLO PASO 7: StartSession -> ${ss["Error"]} (vuelve a tocar Conectar)")
        }
        if (ss["EnableSessionSSL"] == true) {
            lc.readTimeoutMs = 15000
            try {
                val tls = MiniTls.create(pr, lc.input, lc.output, log)
                tls.handshake()
                lc.readTimeoutMs = 0
                ld.switchStreams(tls.input, tls.output)
            } catch (e: Exception) {
                return fail(log, "FALLO PASO 7: TLS propio: ${e.javaClass.simpleName}: ${e.message}")
            }
            log("PASO 7 OK: sesion TLS abierta (TLS propio)")
        } else {
            log("PASO 7 OK: sesion sin TLS")
        }
        log("ProductType: ${ld.getValue("ProductType")}")

        // PASO 8: servicio de archivos
        val sv = ld.startService("com.apple.afc") ?: return fail(log, "FALLO PASO 8: StartService sin respuesta")
        val port = (sv["Port"] as? Long)?.toInt()
        if (port == null) return fail(log, "FALLO PASO 8: StartService -> ${sv["Error"] ?: sv}")
        val ac = m.open(port) ?: return fail(log, "FALLO PASO 8: no pude abrir AFC (puerto $port)")
        var ain = ac.input
        var aout = ac.output
        if (sv["EnableServiceSSL"] == true) {
            ac.readTimeoutMs = 0
            try {
                ac.readTimeoutMs = 15000
                val tls = MiniTls.create(pr, ac.input, ac.output, log)
                tls.handshake()
                ac.readTimeoutMs = 0
                ain = tls.input
                aout = tls.output
            } catch (e: Exception) {
                return fail(log, "FALLO PASO 8: TLS de AFC: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val a = Afc(ain, aout, log)
        log("PASO 8 OK: AFC abierto. Raiz: ${a.listDir("/")}")

        // PASO 9: biblioteca
        val itunes = a.listDir("/iTunes_Control/iTunes")
        log("/iTunes_Control/iTunes: $itunes")
        var best: List<Track>? = null
        var bestRaw: ByteArray? = null
        for (name in listOf("iTunesCDB", "iTunesDB")) {
            if (itunes == null || !itunes.contains(name)) continue
            val raw = a.readAll("/iTunes_Control/iTunes/$name")
            if (raw == null) {
                log("No pude leer $name")
                continue
            }
            log("$name: ${raw.size} bytes")
            val t = ITunesDb.parse(raw, log)
            log("$name -> ${t?.size ?: 0} canciones")
            if (t != null && (best == null || t.size > best.size)) {
                best = t
                bestRaw = raw
            }
        }
        val lib: Library
        if (best != null && best.isNotEmpty()) {
            for (t in best.take(3)) log("Ej: '${t.title}' / ${t.artist} / ${t.album} -> ${t.path}")
            val raw0 = bestRaw
            val pls: List<Pair<String, List<Track>>> =
                if (raw0 != null) ITunesDb.parsePlaylists(raw0, best, log) else emptyList()
            log("Playlists: ${pls.size}")
            lib = Library(best, true, pls)
        } else {
            log("Sin base de datos legible: listo los archivos de /iTunes_Control/Music")
            val files = ITunesDb.scanFiles(a, log)
            if (files.isEmpty()) {
                afc = a
                return fail(log, "FALLO PASO 9: no encontre canciones en el iPod")
            }
            lib = Library(files, false)
        }
        synchronized(afcLock) { afc = a }
        library = lib
        log("=== LISTO: ${lib.tracks.size} canciones ===")
        return true
    }
}
