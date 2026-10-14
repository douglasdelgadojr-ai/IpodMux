package com.douglas.ipodmux

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TlsAlert(val level: Int, val desc: Int) : IOException("alerta TLS nivel=$level codigo=$desc")

/**
 * TLS 1.0 minimo (TLS_RSA_WITH_AES_128_CBC_SHA) hecho a mano para hablar con lockdownd de iOS 4.
 * Se escribe aparte porque el TLS de Android parte el primer mensaje en dos registros
 * (proteccion BEAST) y el iPod corta la conexion. Aqui cada escritura va en UN solo registro.
 */
class MiniTls(
    private val inS: InputStream,
    private val outS: OutputStream,
    private val hostCert: ByteArray,
    private val rootCert: ByteArray,
    private val hostKey: PrivateKey,
    private val log: (String) -> Unit
) {
    private val rnd = SecureRandom()
    private val transcript = ByteArrayOutputStream()
    private var hsBuf = ByteArray(0)
    private val wLock = Any()

    private var readEnc = false
    private var writeEnc = false
    private var rSeq = 0L
    private var wSeq = 0L
    private var rMacKey = ByteArray(0)
    private var wMacKey = ByteArray(0)
    private var rCipher: Cipher? = null
    private var wCipher: Cipher? = null

    companion object {
        fun create(rec: PairRecord, inS: InputStream, outS: OutputStream, log: (String) -> Unit): MiniTls {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(rec.hostKeyPkcs8))
            return MiniTls(inS, outS, Certs.unpem(rec.hostCertPem), Certs.unpem(rec.rootCertPem), key, log)
        }
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xff
    private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())
    private fun u24(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    // ---------- PRF de TLS 1.0 ----------
    private fun hmac(alg: String, key: ByteArray, a: ByteArray, b: ByteArray? = null): ByteArray {
        val m = Mac.getInstance(alg)
        m.init(SecretKeySpec(key, alg))
        m.update(a)
        if (b != null) m.update(b)
        return m.doFinal()
    }

    private fun pHash(alg: String, secret: ByteArray, seed: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var a = seed
        var pos = 0
        while (pos < len) {
            a = hmac(alg, secret, a)
            val chunk = hmac(alg, secret, a, seed)
            val n = minOf(chunk.size, len - pos)
            System.arraycopy(chunk, 0, out, pos, n)
            pos += n
        }
        return out
    }

    private fun prf(secret: ByteArray, label: String, seed: ByteArray, len: Int): ByteArray {
        val half = (secret.size + 1) / 2
        val s1 = secret.copyOfRange(0, half)
        val s2 = secret.copyOfRange(secret.size - half, secret.size)
        val ls = label.toByteArray(Charsets.US_ASCII) + seed
        val a = pHash("HmacMD5", s1, ls, len)
        val b = pHash("HmacSHA1", s2, ls, len)
        for (i in 0 until len) a[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return a
    }

    private fun hsHash(): ByteArray {
        val t = transcript.toByteArray()
        return MessageDigest.getInstance("MD5").digest(t) + MessageDigest.getInstance("SHA-1").digest(t)
    }

    // ---------- registros ----------
    private fun macOf(key: ByteArray, seq: Long, type: Int, data: ByteArray): ByteArray {
        val hdr = ByteBuffer.allocate(13)
        hdr.putLong(seq)
        hdr.put(type.toByte())
        hdr.put(3.toByte())
        hdr.put(1.toByte())
        hdr.putShort(data.size.toShort())
        return hmac("HmacSHA1", key, hdr.array(), data)
    }

    private fun writeRecord(type: Int, data: ByteArray) {
        var frag = data
        if (writeEnc) {
            val mac = macOf(wMacKey, wSeq, type, data)
            val base = data.size + 20
            val padBytes = 16 - (base % 16)
            val plain = ByteArray(base + padBytes)
            System.arraycopy(data, 0, plain, 0, data.size)
            System.arraycopy(mac, 0, plain, data.size, 20)
            for (i in base until plain.size) plain[i] = (padBytes - 1).toByte()
            frag = wCipher!!.update(plain)
            wSeq++
        }
        val rec = ByteBuffer.allocate(5 + frag.size)
        rec.put(type.toByte())
        rec.put(3.toByte())
        rec.put(1.toByte())
        rec.putShort(frag.size.toShort())
        rec.put(frag)
        outS.write(rec.array())
        outS.flush()
    }

    private fun readRecord(): Pair<Int, ByteArray> {
        val h = readFully(inS, 5) ?: throw EOFException("conexion cerrada")
        val type = u8(h[0])
        val len = (u8(h[3]) shl 8) or u8(h[4])
        val body = readFully(inS, len) ?: throw EOFException("conexion cerrada")
        var data = body
        if (readEnc) {
            if (body.size < 32 || body.size % 16 != 0) throw IOException("registro TLS con largo invalido (${body.size})")
            val plain = rCipher!!.update(body)
            val padVal = u8(plain[plain.size - 1])
            val dataLen = plain.size - 1 - padVal - 20
            if (dataLen < 0) throw IOException("relleno TLS invalido")
            data = plain.copyOfRange(0, dataLen)
            val mac = plain.copyOfRange(dataLen, dataLen + 20)
            if (!MessageDigest.isEqual(mac, macOf(rMacKey, rSeq, type, data))) throw IOException("MAC TLS invalido")
            rSeq++
        }
        if (type == 21) {
            val level = if (data.isNotEmpty()) u8(data[0]) else 0
            val desc = if (data.size > 1) u8(data[1]) else 0
            throw TlsAlert(level, desc)
        }
        return Pair(type, data)
    }

    private fun sendHs(type: Int, body: ByteArray) {
        val m = byteArrayOf(type.toByte()) + u24(body.size) + body
        transcript.write(m)
        writeRecord(22, m)
    }

    /** Lee un mensaje de handshake. Los Finished (tipo 20) no se agregan al transcript aqui. */
    private fun readHs(): Pair<Int, ByteArray> {
        while (true) {
            if (hsBuf.size >= 4) {
                val len = (u8(hsBuf[1]) shl 16) or (u8(hsBuf[2]) shl 8) or u8(hsBuf[3])
                if (hsBuf.size >= 4 + len) {
                    val full = hsBuf.copyOfRange(0, 4 + len)
                    val type = u8(hsBuf[0])
                    hsBuf = hsBuf.copyOfRange(4 + len, hsBuf.size)
                    if (type != 20) transcript.write(full)
                    return Pair(type, full.copyOfRange(4, full.size))
                }
            }
            val (t, d) = readRecord()
            if (t == 22) hsBuf += d
            else throw IOException("registro TLS inesperado (tipo $t) durante el handshake")
        }
    }

    // ---------- handshake ----------
    fun handshake() {
        val cr = ByteArray(32)
        rnd.nextBytes(cr)
        val hello = byteArrayOf(3, 1) + cr + byteArrayOf(0) + u16(2) + byteArrayOf(0x00, 0x2F) + byteArrayOf(1, 0)
        sendHs(1, hello)

        var sr: ByteArray? = null
        var spub: PublicKey? = null
        var certReq = false
        var done = false
        while (!done) {
            val (t, b) = readHs()
            when (t) {
                2 -> {
                    sr = b.copyOfRange(2, 34)
                    val sid = u8(b[34])
                    val suite = (u8(b[35 + sid]) shl 8) or u8(b[36 + sid])
                    if (suite != 0x002F) throw IOException("el iPod eligio la suite 0x${suite.toString(16)}")
                }
                11 -> {
                    val n = (u8(b[3]) shl 16) or (u8(b[4]) shl 8) or u8(b[5])
                    val der = b.copyOfRange(6, 6 + n)
                    spub = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)).publicKey
                }
                12 -> throw IOException("ServerKeyExchange inesperado")
                13 -> certReq = true
                14 -> done = true
            }
        }
        val serverRandom = sr ?: throw IOException("el iPod no mando ServerHello")
        val serverPub = spub ?: throw IOException("el iPod no mando certificado")
        log("TLS propio: ServerHello OK, certificado pedido = $certReq")

        if (certReq) {
            val certs = u24(hostCert.size) + hostCert + u24(rootCert.size) + rootCert
            sendHs(11, u24(certs.size) + certs)
        }

        val pm = ByteArray(48)
        rnd.nextBytes(pm)
        pm[0] = 3
        pm[1] = 1
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, serverPub)
        val enc = rsa.doFinal(pm)
        sendHs(16, u16(enc.size) + enc)

        if (certReq) {
            val sig = Signature.getInstance("NONEwithRSA")
            sig.initSign(hostKey)
            sig.update(hsHash())
            val s = sig.sign()
            sendHs(15, u16(s.size) + s)
        }

        val master = prf(pm, "master secret", cr + serverRandom, 48)
        val kb = prf(master, "key expansion", serverRandom + cr, 104)
        val cMac = kb.copyOfRange(0, 20)
        val sMac = kb.copyOfRange(20, 40)
        val cKey = kb.copyOfRange(40, 56)
        val sKey = kb.copyOfRange(56, 72)
        val cIv = kb.copyOfRange(72, 88)
        val sIv = kb.copyOfRange(88, 104)

        // nuestro ChangeCipherSpec + Finished
        writeRecord(20, byteArrayOf(1))
        val wc = Cipher.getInstance("AES/CBC/NoPadding")
        wc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cKey, "AES"), IvParameterSpec(cIv))
        wCipher = wc
        wMacKey = cMac
        wSeq = 0
        writeEnc = true
        sendHs(20, prf(master, "client finished", hsHash(), 12))

        // ChangeCipherSpec + Finished del iPod
        while (true) {
            val (t, _) = readRecord()
            if (t == 20) break
            throw IOException("registro TLS inesperado (tipo $t) esperando ChangeCipherSpec")
        }
        val rc = Cipher.getInstance("AES/CBC/NoPadding")
        rc.init(Cipher.DECRYPT_MODE, SecretKeySpec(sKey, "AES"), IvParameterSpec(sIv))
        rCipher = rc
        rMacKey = sMac
        rSeq = 0
        readEnc = true
        val expected = prf(master, "server finished", hsHash(), 12)
        val (ft, fb) = readHs()
        if (ft != 20 || !MessageDigest.isEqual(fb, expected)) throw IOException("el Finished del iPod no coincide")
        log("TLS propio: handshake completo")
    }

    // ---------- datos ----------
    val input: InputStream = object : InputStream() {
        private var cur = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xff)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) {
                val rec = try {
                    readRecord()
                } catch (e: TlsAlert) {
                    if (e.desc == 0) return -1
                    throw e
                } catch (e: EOFException) {
                    return -1
                }
                if (rec.first == 23) {
                    cur = rec.second
                    pos = 0
                }
            }
            val k = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, k)
            pos += k
            return k
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(wLock) {
                var o = 0
                while (o < len) {
                    val n = minOf(16384, len - o)
                    writeRecord(23, b.copyOfRange(off + o, off + o + n))
                    o += n
                }
            }
        }

        override fun flush() {
            outS.flush()
        }
    }
}
