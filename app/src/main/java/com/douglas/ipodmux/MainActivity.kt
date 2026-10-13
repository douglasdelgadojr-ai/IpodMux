package com.douglas.ipodmux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val ui = Handler(Looper.getMainLooper())
    private val sb = StringBuilder()
    private val actionPerm = "com.douglas.ipodmux.USB_PERMISSION"

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != actionPerm) return
            val dev = deviceFrom(i)
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && dev != null) {
                log("Permiso USB concedido")
                Thread { runTest(dev) }.start()
            } else {
                log("Permiso USB DENEGADO (granted=$granted)")
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val b1 = Button(this)
        b1.text = "Conectar y probar iPod"
        b1.setOnClickListener { start() }
        val b2 = Button(this)
        b2.text = "Copiar log"
        b2.setOnClickListener { copyLog() }
        row.addView(b1)
        row.addView(b2)

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 14f
        scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val f = IntentFilter(actionPerm)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(permReceiver, f)

        log("iPod Mux Test v0.2 - Android ${Build.VERSION.RELEASE}")
        log("Conecta el iPod y toca 'Conectar y probar iPod'.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(permReceiver)
    }

    private fun log(m: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        ui.post {
            sb.append(t).append("  ").append(m).append('\n')
            logView.text = sb.toString()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", sb.toString()))
        Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
    }

    private fun start() {
        log("Buscando iPod (VID 05ac)...")
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == 0x05ac }
        if (dev == null) {
            val all = usb.deviceList.values.joinToString { String.format("%04x:%04x", it.vendorId, it.productId) }
            log("No hay dispositivo Apple conectado. Dispositivos USB: [$all]")
            return
        }
        if (usb.hasPermission(dev)) {
            Thread { runTest(dev) }.start()
        } else {
            log("Pidiendo permiso USB... acepta el cuadro en pantalla")
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionPerm).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun runTest(dev: UsbDevice) {
        try {
            log(String.format("Dispositivo %s VID=%04x PID=%04x", dev.deviceName, dev.vendorId, dev.productId))
            log("Configuraciones: ${dev.configurationCount}, interfaces (lista plana): ${dev.interfaceCount}")

            var iface: UsbInterface? = null
            var cfg: UsbConfiguration? = null
            for (c in 0 until dev.configurationCount) {
                val cf = dev.getConfiguration(c)
                log("config[$c] id=${cf.id} interfaces=${cf.interfaceCount}")
                for (i in 0 until cf.interfaceCount) {
                    val f = cf.getInterface(i)
                    log("   iface id=${f.id} alt=${f.alternateSetting} clase=${f.interfaceClass}/${f.interfaceSubclass}/${f.interfaceProtocol} endpoints=${f.endpointCount}")
                    if (f.interfaceClass == 255 && f.interfaceSubclass == 254 && f.interfaceProtocol == 2) {
                        iface = f
                        cfg = cf
                    }
                }
            }
            val usbIface = iface
            val usbCfg = cfg
            if (usbIface == null || usbCfg == null) {
                log("FALLO PASO 1: no encontre la interfaz usbmux")
                return
            }
            log("PASO 1 OK: interfaz usbmux (id=${usbIface.id}) esta en la config id=${usbCfg.id}")

            val conn = usb.openDevice(dev)
            if (conn == null) {
                log("FALLO PASO 2: openDevice devolvio null (permiso o firmware de la radio)")
                return
            }

            val setOk = conn.setConfiguration(usbCfg)
            log("setConfiguration(id=${usbCfg.id}) = $setOk")

            var claimed = conn.claimInterface(usbIface, true)
            log("claimInterface(force) = $claimed")
            if (!claimed) {
                claimed = conn.claimInterface(usbIface, false)
                log("claimInterface(sin force) = $claimed")
            }
            if (!claimed) {
                log("FALLO PASO 2: no pude reclamar la interfaz")
                conn.close()
                return
            }
            log("PASO 2 OK: interfaz reclamada")

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until usbIface.endpointCount) {
                val ep = usbIface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            val inEp = epIn
            val outEp = epOut
            if (inEp == null || outEp == null) {
                log("FALLO: no encontre endpoints bulk IN/OUT")
                conn.close()
                return
            }
            log(String.format("Endpoints: IN=0x%02x OUT=0x%02x", inEp.address, outEp.address))

            val mux = MuxSession(conn, inEp, outEp) { log(it) }
            if (!mux.handshake()) {
                log("FALLO PASO 3: el iPod no respondio al handshake usbmux")
                conn.releaseInterface(usbIface)
                conn.close()
                return
            }
            log("PASO 3 OK: handshake usbmux")

            if (!mux.tcpConnect(62078)) {
                log("FALLO PASO 4: no pude abrir TCP a lockdownd")
                conn.releaseInterface(usbIface)
                conn.close()
                return
            }
            log("PASO 4 OK: conectado a lockdownd")

            val q = mux.lockdownRequest(lockdownXml("QueryType"))
            log("QueryType -> ${q?.take(600) ?: "sin respuesta"}")
            if (q != null) log("PASO 5 OK: lockdownd responde")

            for (k in listOf("ProductVersion", "DeviceName", "ProductType")) {
                val r = mux.lockdownRequest(lockdownXml("GetValue", k))
                log("GetValue $k -> ${r?.take(400) ?: "sin respuesta"}")
            }

            mux.tcpClose()
            conn.releaseInterface(usbIface)
            conn.close()
            log("=== FIN DE LA PRUEBA ===  Toca 'Copiar log' y mandame captura.")
        } catch (e: Exception) {
            log("EXCEPCION: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
