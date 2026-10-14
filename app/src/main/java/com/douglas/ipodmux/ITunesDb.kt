package com.douglas.ipodmux

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class Track(
    val id: Long,
    var title: String,
    var artist: String,
    var album: String,
    var genre: String,
    var path: String,
    var size: Long,
    var durationMs: Long,
    var trackNo: Int,
    var discNo: Int,
    var year: Int
)

class Library(all: List<Track>, val hasMetadata: Boolean) {
    val tracks: List<Track> = all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    private fun albumOrder() = compareBy<Track>({ it.discNo }, { it.trackNo }, { it.title.lowercase() })

    val artists: List<Pair<String, List<Track>>> = tracks
        .groupBy { if (it.artist.isBlank()) "Desconocido" else it.artist }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (k, v) -> Pair(k, v.sortedWith(compareBy<Track>({ it.album.lowercase() }, { it.discNo }, { it.trackNo }, { it.title.lowercase() }))) }

    val albums: List<Pair<String, List<Track>>> = tracks
        .groupBy { if (it.album.isBlank()) "Desconocido" else it.album }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (k, v) -> Pair(k, v.sortedWith(albumOrder())) }
}

/** Lector del formato iTunesDB / iTunesCDB (el de iPod: enteros little-endian). */
object ITunesDb {
    private fun u32(d: ByteArray, o: Int): Long {
        if (o < 0 || o + 4 > d.size) return 0
        return (d[o].toLong() and 0xff) or ((d[o + 1].toLong() and 0xff) shl 8) or
            ((d[o + 2].toLong() and 0xff) shl 16) or ((d[o + 3].toLong() and 0xff) shl 24)
    }

    private fun tag(d: ByteArray, o: Int): String =
        if (o < 0 || o + 4 > d.size) "" else String(d, o, 4, Charsets.US_ASCII)

    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "aif", "aiff", "m4b", "m4p", "flac", "ogg")

    fun parse(data0: ByteArray, log: (String) -> Unit): List<Track>? {
        var data = data0
        if (data.size < 16 || tag(data, 0) != "mhbd") {
            log("iTunesDB: no empieza con mhbd (primeros bytes: ${hex(data, 8)})")
            return null
        }
        val base = u32(data, 4).toInt()
        if (data.size >= base + 4 && tag(data, base) != "mhsd") {
            // iTunesCDB: lo que sigue a la cabecera esta comprimido con zlib
            val inf = Inflater()
            inf.setInput(data, base, data.size - base)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                log("iTunesCDB: error al descomprimir: ${e.message}")
                return null
            }
            data = data.copyOfRange(0, base) + out.toByteArray()
            log("iTunesCDB descomprimido: ${out.size()} bytes")
        }
        val tracks = ArrayList<Track>()
        var pos = base
        try {
            while (pos + 16 <= data.size && tag(data, pos) == "mhsd") {
                val hdr = u32(data, pos + 4).toInt()
                val total = u32(data, pos + 8).toInt()
                val type = u32(data, pos + 12).toInt()
                if (type == 1) parseTracks(data, pos + hdr, tracks)
                if (total <= 0) break
                pos += total
            }
        } catch (e: Exception) {
            log("iTunesDB: error de lectura (${e.javaClass.simpleName}: ${e.message}); sigo con ${tracks.size} canciones")
        }
        return tracks
    }

    private fun parseTracks(d: ByteArray, listPos: Int, out: MutableList<Track>) {
        if (tag(d, listPos) != "mhlt") return
        val hdr = u32(d, listPos + 4).toInt()
        val count = u32(d, listPos + 8).toInt()
        var p = listPos + hdr
        for (i in 0 until count) {
            if (tag(d, p) != "mhit") break
            val itHdr = u32(d, p + 4).toInt()
            val itTotal = u32(d, p + 8).toInt()
            val nMhod = u32(d, p + 12).toInt()
            val id = u32(d, p + 16)
            val size = u32(d, p + 36)
            val dur = u32(d, p + 40)
            val trackNo = u32(d, p + 44).toInt()
            val year = u32(d, p + 52).toInt()
            val disc = u32(d, p + 92).toInt()
            var title = ""
            var artist = ""
            var album = ""
            var genre = ""
            var path = ""
            var m = p + itHdr
            for (k in 0 until nMhod) {
                if (tag(d, m) != "mhod") break
                val mTotal = u32(d, m + 8).toInt()
                val type = u32(d, m + 12).toInt()
                if (type in 1..5) {
                    val len = u32(d, m + 28).toInt()
                    val enc = u32(d, m + 32).toInt()
                    if (len > 0 && m + 40 + len <= d.size) {
                        val s = if (enc == 2) String(d, m + 40, len, Charsets.UTF_8) else String(d, m + 40, len, Charsets.UTF_16LE)
                        when (type) {
                            1 -> title = s
                            2 -> path = s
                            3 -> album = s
                            4 -> artist = s
                            5 -> genre = s
                        }
                    }
                }
                if (mTotal <= 0) break
                m += mTotal
            }
            if (path.isNotEmpty()) {
                val fixed = path.replace(':', '/')
                val ext = fixed.substringAfterLast('.', "").lowercase()
                if (ext in audioExt) {
                    out.add(Track(id, if (title.isBlank()) fixed.substringAfterLast('/') else title, artist, album, genre, fixed, size, dur, trackNo, disc, year))
                }
            }
            if (itTotal <= 0) break
            p += itTotal
        }
    }

    /** Plan B: si no hay base de datos legible, lista los archivos de /iTunes_Control/Music tal cual. */
    fun scanFiles(afc: Afc, log: (String) -> Unit): List<Track> {
        val out = ArrayList<Track>()
        val dirs = afc.listDir("/iTunes_Control/Music") ?: return out
        var id = 1L
        for (dir in dirs.sorted()) {
            val files = afc.listDir("/iTunes_Control/Music/$dir") ?: continue
            for (f in files.sorted()) {
                val ext = f.substringAfterLast('.', "").lowercase()
                if (ext !in audioExt) continue
                out.add(Track(id++, f, "", dir, "", "/iTunes_Control/Music/$dir/$f", 0, 0, 0, 0, 0))
            }
        }
        log("Escaneo de archivos: ${out.size} canciones")
        return out
    }
}
