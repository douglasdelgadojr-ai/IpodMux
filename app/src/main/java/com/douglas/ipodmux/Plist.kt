package com.douglas.ipodmux

import java.util.Base64

/** Lector/escritor minimo de property lists XML (lo que habla lockdownd). */
object Plist {
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun build(root: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n")
        write(sb, root)
        sb.append("\n</plist>\n")
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            is Map<*, *> -> {
                sb.append("<dict>")
                for ((k, x) in v) {
                    sb.append("<key>").append(escape(k.toString())).append("</key>")
                    write(sb, x)
                }
                sb.append("</dict>")
            }
            is List<*> -> {
                sb.append("<array>")
                for (x in v) write(sb, x)
                sb.append("</array>")
            }
            is ByteArray -> sb.append("<data>").append(Base64.getEncoder().encodeToString(v)).append("</data>")
            is Boolean -> sb.append(if (v) "<true/>" else "<false/>")
            is Int -> sb.append("<integer>").append(v).append("</integer>")
            is Long -> sb.append("<integer>").append(v).append("</integer>")
            else -> sb.append("<string>").append(escape(v.toString())).append("</string>")
        }
    }

    private class Tok(val kind: Int, val name: String, val text: String) // 0 abre, 1 cierra, 2 vacio, 3 texto

    private fun tokenize(xml: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (lt > i) {
                val t = xml.substring(i, lt)
                if (t.isNotBlank()) out.add(Tok(3, "", unescape(t)))
            }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val inner = xml.substring(lt + 1, gt)
            i = gt + 1
            if (inner.startsWith("?") || inner.startsWith("!")) continue
            if (inner.startsWith("/")) out.add(Tok(1, inner.substring(1).trim(), ""))
            else if (inner.endsWith("/")) out.add(Tok(2, inner.dropLast(1).trim().substringBefore(' '), ""))
            else out.add(Tok(0, inner.trim().substringBefore(' '), ""))
        }
        return out
    }

    fun parse(xml: String): Any? {
        val t = tokenize(xml)
        val p = intArrayOf(0)
        while (p[0] < t.size && (t[p[0]].kind == 3 || t[p[0]].name == "plist")) p[0]++
        if (p[0] >= t.size) return null
        return value(t, p)
    }

    @Suppress("UNCHECKED_CAST")
    fun parseMap(xml: String): Map<String, Any?>? = parse(xml) as? Map<String, Any?>

    private fun value(t: List<Tok>, p: IntArray): Any? {
        val tok = t[p[0]++]
        if (tok.kind == 2) {
            return when (tok.name) {
                "true" -> true
                "false" -> false
                "dict" -> LinkedHashMap<String, Any?>()
                "array" -> ArrayList<Any?>()
                else -> ""
            }
        }
        if (tok.kind != 0) return null
        when (tok.name) {
            "dict" -> {
                val m = LinkedHashMap<String, Any?>()
                while (p[0] < t.size && t[p[0]].kind != 1) {
                    val k = value(t, p) as? String ?: ""
                    m[k] = value(t, p)
                }
                p[0]++
                return m
            }
            "array" -> {
                val a = ArrayList<Any?>()
                while (p[0] < t.size && t[p[0]].kind != 1) a.add(value(t, p))
                p[0]++
                return a
            }
        }
        var text = ""
        if (p[0] < t.size && t[p[0]].kind == 3) {
            text = t[p[0]].text
            p[0]++
        }
        if (p[0] < t.size && t[p[0]].kind == 1) p[0]++
        return when (tok.name) {
            "data" -> try { Base64.getMimeDecoder().decode(text.trim()) } catch (e: Exception) { ByteArray(0) }
            "integer" -> text.trim().toLongOrNull() ?: 0L
            "real" -> text.trim().toDoubleOrNull() ?: 0.0
            else -> text
        }
    }
}
