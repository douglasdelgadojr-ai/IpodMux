package com.douglas.ipodmux

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Reproductor: baja cada cancion del iPod (AFC) a la cache de la radio y la reproduce con MediaPlayer. */
object Player {
    var queue: List<Track> = emptyList()
    var index = -1
    @Volatile var playing = false
    @Volatile var loading = false
    @Volatile var statusText = ""
    var onChange: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private lateinit var appCtx: Context
    private var inited = false
    private var mp: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var wantedId = -1L
    private var token = 0
    private var errorsInRow = 0
    private var resumeAfterFocus = false
    private var session: MediaSession? = null

    val current: Track? get() = queue.getOrNull(index)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext
        try {
            val s = MediaSession(appCtx, "IpodMux")
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { prev() }
            })
            s.isActive = true
            session = s
            updateSession()
        } catch (e: Exception) {
            onLog?.invoke("MediaSession: ${e.message}")
        }
    }

    private fun notifyChange() {
        updateSession()
        main.post { onChange?.invoke() }
    }

    private fun updateSession() {
        val s = session ?: return
        try {
            val st = if (playing) PlaybackState.STATE_PLAYING else if (loading) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            val pb = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(st, position().toLong(), 1f)
                .build()
            s.setPlaybackState(pb)
            val t = current
            if (t != null) {
                val md = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration().toLong())
                    .build()
                s.setMetadata(md)
            }
        } catch (e: Exception) {
        }
    }

    fun position(): Int = try { if (mp != null && !loading) mp!!.currentPosition else 0 } catch (e: Exception) { 0 }

    fun duration(): Int {
        val d = try { if (mp != null && !loading) mp!!.duration else 0 } catch (e: Exception) { 0 }
        return if (d > 0) d else (current?.durationMs?.toInt() ?: 0)
    }

    fun play(q: List<Track>, i: Int) {
        queue = q
        index = i
        errorsInRow = 0
        startCurrent()
    }

    private fun stopPlayer() {
        try { mp?.stop() } catch (e: Exception) {}
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        playing = false
    }

    fun stopAll() {
        token++
        wantedId = -1
        stopPlayer()
        loading = false
        queue = emptyList()
        index = -1
        statusText = ""
        notifyChange()
    }

    private fun cacheFile(t: Track): File {
        val ext = t.path.substringAfterLast('.', "mp3")
        val dir = File(appCtx.cacheDir, "tracks")
        dir.mkdirs()
        return File(dir, "t${t.id}.$ext")
    }

    private fun startCurrent() {
        val t = current ?: return
        val my = ++token
        wantedId = t.id
        stopPlayer()
        loading = true
        statusText = "Cargando..."
        notifyChange()
        worker.execute {
            val f = fetch(t, showProgress = true) { wantedId != t.id }
            main.post {
                if (my != token) return@post
                if (f == null) {
                    loading = false
                    statusText = "No pude leer la cancion del iPod"
                    notifyChange()
                } else startMp(f, my)
            }
        }
    }

    /** Baja la cancion a la cache (si no esta ya). Devuelve null si falla o se cancela. */
    private fun fetch(t: Track, showProgress: Boolean, abortIf: () -> Boolean): File? {
        val f = cacheFile(t)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(f.path + ".part")
        var lastPct = -1
        try {
            FileOutputStream(tmp).use { out ->
                val ok = IpodSession.download(t.path, out) { done, total ->
                    if (abortIf()) throw InterruptedException("cancelado")
                    if (showProgress && total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            statusText = "Cargando $pct%"
                            notifyChange()
                        }
                    }
                }
                if (!ok) {
                    tmp.delete()
                    return null
                }
            }
        } catch (e: InterruptedException) {
            tmp.delete()
            return null
        } catch (e: Exception) {
            tmp.delete()
            onLog?.invoke("Descarga: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!tmp.renameTo(f)) return null
        trimCache()
        return f
    }

    private fun trimCache() {
        val dir = File(appCtx.cacheDir, "tracks")
        val files = dir.listFiles()?.filter { !it.name.endsWith(".part") } ?: return
        val sorted = files.sortedByDescending { it.lastModified() }
        for (i in 8 until sorted.size) sorted[i].delete()
    }

    private fun startMp(f: File, my: Int) {
        try {
            val p = MediaPlayer()
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
            p.setWakeMode(appCtx, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(f.absolutePath)
            p.setOnPreparedListener {
                if (my == token) {
                    requestFocus()
                    it.start()
                    playing = true
                    loading = false
                    statusText = ""
                    errorsInRow = 0
                    notifyChange()
                    prefetchNext()
                }
            }
            p.setOnCompletionListener { if (my == token) next() }
            p.setOnErrorListener { _, what, extra ->
                if (my == token) {
                    onLog?.invoke("MediaPlayer error $what/$extra en ${current?.path}")
                    playing = false
                    loading = false
                    statusText = "No se puede reproducir (¿protegida con DRM?)"
                    notifyChange()
                    errorsInRow++
                    if (errorsInRow < 4) main.postDelayed({ if (my == token) next() }, 1500)
                }
                true
            }
            mp = p
            p.prepareAsync()
        } catch (e: Exception) {
            loading = false
            statusText = "Error: ${e.message}"
            notifyChange()
        }
    }

    private fun prefetchNext() {
        val cur = current ?: return
        val nx = queue.getOrNull(index + 1) ?: return
        worker.execute {
            fetch(nx, showProgress = false) { wantedId != cur.id && wantedId != nx.id }
        }
    }

    fun next() {
        if (index + 1 < queue.size) {
            index++
            startCurrent()
        } else {
            token++
            stopPlayer()
            statusText = "Fin de la lista"
            notifyChange()
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position() > 3000) {
            try { mp?.seekTo(0) } catch (e: Exception) {}
            notifyChange()
        } else if (index > 0) {
            index--
            startCurrent()
        }
    }

    fun pause() {
        try { if (mp != null && playing) mp!!.pause() } catch (e: Exception) {}
        playing = false
        notifyChange()
    }

    fun resume() {
        try { if (mp != null && !loading) { mp!!.start(); playing = true } } catch (e: Exception) {}
        notifyChange()
    }

    fun toggle() {
        if (playing) pause() else resume()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocus = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeAfterFocus = playing; pause() }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocus) { resumeAfterFocus = false; resume() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

    fun adjustVolume(dir: Int) {
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }
}
