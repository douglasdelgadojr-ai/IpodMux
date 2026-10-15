package com.douglas.ipodmux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MenuItem(val label: String, val sub: Boolean = true, val action: () -> Unit)
class Screen(val title: String, val items: List<MenuItem>, var sel: Int = 0)

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private val ui = Handler(Looper.getMainLooper())
    private val sb = StringBuilder()
    private val actionPerm = "com.douglas.ipodmux.USB_PERMISSION"

    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: MenuAdapter
    private lateinit var nowPane: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var nowAlbum: TextView
    private lateinit var nowCount: TextView
    private lateinit var nowStatus: TextView
    private lateinit var nowElapsed: TextView
    private lateinit var nowRemain: TextView
    private lateinit var nowBar: IpodBarView
    private lateinit var nowArt: ImageView
    private lateinit var nowNote: TextView
    private var shownArt: Bitmap? = null
    private lateinit var logPane: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val stack = ArrayList<Screen>()
    private var mode = 0 // 0 lista, 1 reproduciendo, 2 registro
    private var statusLine = "Conecta el iPod por USB"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- USB
    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                actionPerm -> {
                    val dev = deviceFrom(i)
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                        log("Permiso USB concedido")
                        connectInBackground(dev)
                    } else log("Permiso USB DENEGADO")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = deviceFrom(i)
                    if (dev != null && dev.vendorId == 0x05ac) {
                        log("iPod desconectado")
                        Player.stopAll()
                        IpodSession.disconnect()
                        statusLine = "iPod desconectado"
                        rebuildMain()
                    }
                }
            }
        }
    }

    private fun findIpod(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == 0x05ac }

    private fun tryConnect() {
        if (IpodSession.connecting) return
        if (IpodSession.isConnected()) {
            log("Ya hay un iPod conectado")
            return
        }
        val dev = findIpod()
        if (dev == null) {
            statusLine = "No hay iPod conectado"
            log("No hay dispositivo Apple conectado")
            rebuildMain()
            return
        }
        if (usb.hasPermission(dev)) {
            connectInBackground(dev)
        } else {
            log("Pidiendo permiso USB... acepta el cuadro en pantalla")
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionPerm).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usb.requestPermission(dev, pi)
        }
    }

    private fun connectInBackground(dev: UsbDevice) {
        if (IpodSession.connecting) return
        statusLine = "Conectando con el iPod..."
        rebuildMain()
        Thread {
            val ok = IpodSession.connect(applicationContext, usb, dev) { log(it) }
            ui.post {
                statusLine = if (ok) IpodSession.deviceName else "Fallo la conexion (mira el registro)"
                rebuildMain()
            }
        }.start()
    }

    // ---------------------------------------------------------------- menus
    private fun rebuildMain() {
        if (mode == 2) return
        stack.clear()
        push(mainMenu(), keepMode = true)
        if (mode == 1 && Player.current == null) mode = 0
        applyMode()
    }

    private fun mainMenu(): Screen {
        val lib = IpodSession.library
        val items = ArrayList<MenuItem>()
        if (lib != null) {
            items.add(MenuItem("Música") { push(musicMenu(lib)) })
            items.add(MenuItem("Reproduciendo ahora") { showNow() })
            items.add(MenuItem("Aleatorio") { shuffleAll(lib) })
        } else {
            items.add(MenuItem(if (IpodSession.connecting) "Conectando..." else "Conectar iPod", false) { tryConnect() })
        }
        items.add(MenuItem("Registro (log)") { showLog() })
        return Screen(if (lib != null) IpodSession.deviceName else "iPod USB", items)
    }

    private fun musicMenu(lib: Library): Screen {
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Playlists") { push(playlistsMenu(lib)) })
        items.add(MenuItem("Artistas") {
            push(Screen("Artistas", lib.artists.map { a -> MenuItem(a.first) { push(trackMenu(a.first, a.second)) } }))
        })
        items.add(MenuItem("Álbumes") {
            push(Screen("Álbumes", lib.albums.map { a -> MenuItem(a.first) { push(trackMenu(a.first, a.second)) } }))
        })
        items.add(MenuItem("Canciones") { push(trackMenu("Canciones", lib.tracks)) })
        return Screen("Música", items)
    }

    private fun playlistsMenu(lib: Library): Screen {
        if (lib.playlists.isEmpty()) {
            return Screen("Playlists", listOf(MenuItem("(no hay playlists)", false) {}))
        }
        return Screen("Playlists", lib.playlists.map { p -> MenuItem(p.first) { push(trackMenu(p.first, p.second)) } })
    }

    private fun trackMenu(title: String, tracks: List<Track>): Screen {
        val items = tracks.mapIndexed { i, t ->
            MenuItem(t.title, false) {
                Player.play(tracks, i)
                showNow()
            }
        }
        return Screen(title, items)
    }

    private fun shuffleAll(lib: Library) {
        val q = lib.tracks.shuffled()
        if (q.isEmpty()) return
        Player.play(q, 0)
        showNow()
    }

    private fun push(s: Screen, keepMode: Boolean = false) {
        stack.add(s)
        if (!keepMode) mode = 0
        showScreen(s)
        applyMode()
    }

    private fun showScreen(s: Screen) {
        titleView.text = s.title
        adapter.items = s.items
        adapter.selected = s.sel
        adapter.notifyDataSetChanged()
        listView.setSelection(maxOf(0, s.sel - 2))
    }

    private fun showNow() {
        mode = 1
        applyMode()
        updateNow()
    }

    private fun showLog() {
        mode = 2
        applyMode()
    }

    private fun back(): Boolean {
        if (mode != 0) {
            mode = 0
            applyMode()
            if (stack.isNotEmpty()) showScreen(stack.last())
            return true
        }
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            showScreen(stack.last())
            return true
        }
        return false
    }

    private fun applyMode() {
        listView.visibility = if (mode == 0) View.VISIBLE else View.GONE
        nowPane.visibility = if (mode == 1) View.VISIBLE else View.GONE
        logPane.visibility = if (mode == 2) View.VISIBLE else View.GONE
        titleView.text = when (mode) {
            1 -> "Reproduciendo"
            2 -> "Registro"
            else -> stack.lastOrNull()?.title ?: "iPod USB"
        }
    }

    // ---------------------------------------------------------------- entrada (rueda, teclas, volante)
    private fun turn(d: Int) {
        when (mode) {
            0 -> {
                val s = stack.lastOrNull() ?: return
                if (s.items.isEmpty()) return
                s.sel = (s.sel + d).coerceIn(0, s.items.size - 1)
                adapter.selected = s.sel
                adapter.notifyDataSetChanged()
                val first = listView.firstVisiblePosition
                val last = listView.lastVisiblePosition
                if (s.sel <= first || s.sel >= last) listView.setSelection(maxOf(0, s.sel - 3))
            }
            1 -> Player.adjustVolume(d)
            2 -> logScroll.smoothScrollBy(0, d * dp(80))
        }
    }

    private fun center() {
        when (mode) {
            0 -> {
                val s = stack.lastOrNull() ?: return
                s.items.getOrNull(s.sel)?.action?.invoke()
            }
            1 -> Player.toggle()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> turn(1)
            KeyEvent.KEYCODE_DPAD_UP -> turn(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> center()
            KeyEvent.KEYCODE_BACK -> if (!back()) return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_MEDIA_NEXT -> Player.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Player.prev()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> Player.toggle()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ---------------------------------------------------------------- pantalla "Reproduciendo"
    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun updateNow() {
        val t = Player.current
        stateView.text = if (Player.playing) ">" else if (Player.loading) "..." else "||"
        if (t == null) {
            nowTitle.text = "Nada en reproducción"
            nowArtist.text = ""
            nowAlbum.text = ""
            nowCount.text = ""
            nowStatus.text = ""
            nowElapsed.text = ""
            nowRemain.text = ""
            nowBar.fraction = 0f
            showArt(null)
            return
        }
        nowTitle.text = t.title
        nowArtist.text = t.artist
        nowAlbum.text = t.album
        nowCount.text = "${Player.index + 1} de ${Player.queue.size}"
        nowStatus.text = Player.statusText
        val dur = Player.duration()
        val pos = Player.position()
        nowElapsed.text = fmt(pos)
        nowRemain.text = if (dur > 0) "-" + fmt(maxOf(0, dur - pos)) else ""
        nowBar.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        showArt(Player.art)
    }

    private fun showArt(b: Bitmap?) {
        if (b === shownArt) return
        shownArt = b
        nowArt.setImageBitmap(b)
        nowNote.visibility = if (b == null) View.VISIBLE else View.GONE
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (mode == 1 || Player.playing) updateNow()
            ui.postDelayed(this, 500)
        }
    }

    // ---------------------------------------------------------------- registro
    private fun log(m: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        ui.post {
            sb.append(t).append("  ").append(m).append('\n')
            logView.text = sb.toString()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", sb.toString()))
        Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- construccion de la interfaz
    private inner class MenuAdapter : BaseAdapter() {
        var items: List<MenuItem> = emptyList()
        var selected = 0

        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val label: TextView
            val chev: TextView
            if (convert == null) {
                row = LinearLayout(this@MainActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(14), 0, dp(14), 0)
                row.layoutParams = AbsListViewParams(dp(52))
                label = TextView(this@MainActivity)
                label.textSize = 22f
                label.maxLines = 1
                label.ellipsize = android.text.TextUtils.TruncateAt.END
                chev = TextView(this@MainActivity)
                chev.textSize = 24f
                chev.typeface = Typeface.DEFAULT_BOLD
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(chev, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                row = convert as LinearLayout
                label = row.getChildAt(0) as TextView
                chev = row.getChildAt(1) as TextView
            }
            val it = items[p]
            label.text = it.label
            chev.text = if (it.sub) ">" else ""
            if (p == selected) {
                val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF5AB0FF.toInt(), 0xFF0A6FD6.toInt()))
                row.background = g
                label.setTextColor(Color.WHITE)
                chev.setTextColor(Color.WHITE)
            } else {
                row.setBackgroundColor(Color.WHITE)
                label.setTextColor(Color.BLACK)
                chev.setTextColor(0xFF888888.toInt())
            }
            return row
        }
    }

    private fun AbsListViewParams(h: Int) = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    private fun tv(size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        t.maxLines = 2
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        return t
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        Player.init(applicationContext)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.HORIZONTAL
        root.setBackgroundColor(0xFF1C1C1E.toInt())
        root.setPadding(dp(16), dp(10), dp(16), dp(10))

        // --- "pantalla" del iPod (izquierda)
        val screen = LinearLayout(this)
        screen.orientation = LinearLayout.VERTICAL
        val frame = GradientDrawable()
        frame.setColor(Color.WHITE)
        frame.setStroke(dp(4), 0xFF9A9A9A.toInt())
        frame.cornerRadius = dp(10).toFloat()
        screen.background = frame
        screen.setPadding(dp(4), dp(4), dp(4), dp(4))

        val header = FrameLayout(this)
        header.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFF4F4F4.toInt(), 0xFFB9B9B9.toInt()))
        titleView = tv(20f, Color.BLACK, true)
        titleView.gravity = Gravity.CENTER
        titleView.maxLines = 1
        stateView = tv(18f, 0xFF333333.toInt(), true)
        header.addView(titleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        val sl = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.END)
        stateView.gravity = Gravity.CENTER_VERTICAL
        stateView.setPadding(0, 0, dp(12), 0)
        header.addView(stateView, sl)
        screen.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val body = FrameLayout(this)
        listView = ListView(this)
        listView.divider = null
        adapter = MenuAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val sc = stack.lastOrNull()
            if (sc != null) {
                sc.sel = pos
                adapter.selected = pos
                adapter.notifyDataSetChanged()
                center()
            }
        }
        body.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        nowPane = LinearLayout(this)
        nowPane.orientation = LinearLayout.VERTICAL
        nowPane.setPadding(dp(16), dp(10), dp(16), dp(10))
        nowPane.setBackgroundColor(Color.WHITE)
        nowCount = tv(15f, 0xFF666666.toInt())
        nowTitle = tv(28f, Color.BLACK, true)
        nowArtist = tv(22f, 0xFF222222.toInt())
        nowAlbum = tv(20f, 0xFF666666.toInt())
        nowStatus = tv(15f, 0xFFB00020.toInt())
        nowElapsed = tv(16f, 0xFF333333.toInt())
        nowRemain = tv(16f, 0xFF333333.toInt())
        nowRemain.gravity = Gravity.END
        nowBar = IpodBarView(this)

        // caratula del album (o una nota musical si el archivo no trae)
        val artBox = FrameLayout(this)
        val artBg = GradientDrawable()
        artBg.setColor(0xFFE2E2E2.toInt())
        artBg.setStroke(dp(1), 0xFFB0B0B0.toInt())
        artBg.cornerRadius = dp(4).toFloat()
        artBox.background = artBg
        nowNote = tv(48f, 0xFF9A9A9A.toInt(), true)
        nowNote.text = "\u266A"
        nowNote.gravity = Gravity.CENTER
        artBox.addView(nowNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        nowArt = ImageView(this)
        nowArt.scaleType = ImageView.ScaleType.CENTER_CROP
        artBox.addView(nowArt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(nowTitle)
        texts.addView(nowArtist)
        texts.addView(nowAlbum)
        texts.addView(nowStatus)

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.addView(artBox, LinearLayout.LayoutParams(dp(260), dp(260)))
        val tl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tl.leftMargin = dp(14)
        mid.addView(texts, tl)

        val times = LinearLayout(this)
        times.orientation = LinearLayout.HORIZONTAL
        times.addView(nowElapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(nowRemain, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        nowPane.addView(nowCount)
        val ml = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ml.topMargin = dp(6)
        nowPane.addView(mid, ml)
        val sp = View(this)
        nowPane.addView(sp, LinearLayout.LayoutParams(1, 0, 1f))
        nowPane.addView(nowBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))
        nowPane.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(nowPane, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        logPane = LinearLayout(this)
        logPane.orientation = LinearLayout.VERTICAL
        logPane.setBackgroundColor(Color.WHITE)
        val cp = Button(this)
        cp.text = "Copiar log"
        cp.setOnClickListener { copyLog() }
        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 13f
        logView.setTextColor(Color.BLACK)
        logScroll = ScrollView(this)
        logScroll.addView(logView)
        logPane.addView(cp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        logPane.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(logPane, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        screen.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(screen, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f))

        // --- rueda (derecha)
        val wheel = ClickWheelView(this)
        wheel.onTurn = { turn(it) }
        wheel.onCenter = { center() }
        wheel.onMenu = { back() }
        wheel.onNext = { Player.next() }
        wheel.onPrev = { Player.prev() }
        wheel.onPlayPause = { Player.toggle() }
        val wl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        wl.leftMargin = dp(16)
        root.addView(wheel, wl)
        setContentView(root)

        val f = IntentFilter(actionPerm)
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, f)

        Player.onLog = { log(it) }
        Player.onChange = { updateNow() }

        log("iPod USB v0.4 - Android ${Build.VERSION.RELEASE}")
        push(mainMenu())
        applyMode()
        ui.post(ticker)
        if (IpodSession.isConnected()) rebuildMain() else ui.postDelayed({ tryConnect() }, 600)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) ui.postDelayed({ tryConnect() }, 600)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(ticker)
        Player.onChange = null
        Player.onLog = null
        unregisterReceiver(usbReceiver)
    }
}
