package com.douglas.ipodmux

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** Escritor DER minimo, solo lo necesario para armar certificados X.509. */
object Der {
    fun concat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) o.write(p)
        return o.toByteArray()
    }

    private fun len(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = concat(byteArrayOf(tag.toByte()), len(content.size), content)
    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(*parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(*parts))
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun oid(b: IntArray) = tlv(0x06, ByteArray(b.size) { b[it].toByte() })
    fun bitString(content: ByteArray, unused: Int = 0) = tlv(0x03, concat(byteArrayOf(unused.toByte()), content))
    fun octet(content: ByteArray) = tlv(0x04, content)
    fun bool(b: Boolean) = tlv(0x01, byteArrayOf(if (b) 0xFF.toByte() else 0))
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    fun ctx(n: Int, content: ByteArray) = tlv(0xA0 or n, content)
    val NULL = byteArrayOf(5, 0)
}

class PairRecord(
    val hostId: String,
    val systemBuid: String,
    val rootCertPem: String,
    val hostCertPem: String,
    val deviceCertPem: String,
    val hostKeyPkcs8: ByteArray
) {
    fun toPlist(): Map<String, Any?> = linkedMapOf(
        "DeviceCertificate" to deviceCertPem.toByteArray(Charsets.US_ASCII),
        "HostCertificate" to hostCertPem.toByteArray(Charsets.US_ASCII),
        "HostID" to hostId,
        "RootCertificate" to rootCertPem.toByteArray(Charsets.US_ASCII),
        "SystemBUID" to systemBuid
    )

    fun toFields(): Map<String, String> = mapOf(
        "hostId" to hostId,
        "systemBuid" to systemBuid,
        "root" to rootCertPem,
        "host" to hostCertPem,
        "device" to deviceCertPem,
        "key" to Base64.getEncoder().encodeToString(hostKeyPkcs8)
    )

    companion object {
        fun fromFields(f: Map<String, String?>): PairRecord? {
            val h = f["hostId"] ?: return null
            val b = f["systemBuid"] ?: return null
            val r = f["root"] ?: return null
            val ho = f["host"] ?: return null
            val d = f["device"] ?: return null
            val k = f["key"] ?: return null
            return PairRecord(h, b, r, ho, d, Base64.getDecoder().decode(k))
        }

        fun create(devicePub: PublicKey): PairRecord {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val rootKp = gen.generateKeyPair()
            val hostKp = gen.generateKeyPair()
            val rootDer = Certs.build("iPodMux Root", rootKp.public, "iPodMux Root", rootKp.private, Certs.serial(), true)
            val hostDer = Certs.build("iPodMux Host", hostKp.public, "iPodMux Root", rootKp.private, Certs.serial(), false)
            val devDer = Certs.build("iPodMux Device", devicePub, "iPodMux Root", rootKp.private, Certs.serial(), false)
            return PairRecord(
                UUID.randomUUID().toString().uppercase(),
                UUID.randomUUID().toString().uppercase(),
                Certs.pem("CERTIFICATE", rootDer),
                Certs.pem("CERTIFICATE", hostDer),
                Certs.pem("CERTIFICATE", devDer),
                hostKp.private.encoded
            )
        }
    }
}

object Certs {
    private val SHA1_RSA = intArrayOf(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x05)
    private val rnd = SecureRandom()

    fun serial(): Long = (rnd.nextLong() ushr 2) or 1L

    private fun name(cn: String) =
        Der.seq(Der.set(Der.seq(Der.oid(intArrayOf(0x55, 0x04, 0x03)), Der.utf8(cn))))

    private fun ext(oid: IntArray, critical: Boolean, value: ByteArray) =
        Der.seq(Der.oid(oid), Der.bool(critical), Der.octet(value))

    fun build(subjectCn: String, subjectPub: PublicKey, issuerCn: String, issuerKey: PrivateKey, serial: Long, isCa: Boolean): ByteArray {
        val algId = Der.seq(Der.oid(SHA1_RSA), Der.NULL)
        // fechas muy amplias: el reloj de la radio o del iPod puede estar mal
        val validity = Der.seq(Der.utcTime("000101000000Z"), Der.utcTime("491231235959Z"))
        val bc = ext(intArrayOf(0x55, 0x1D, 0x13), true, if (isCa) Der.seq(Der.bool(true)) else Der.seq())
        val ku = ext(
            intArrayOf(0x55, 0x1D, 0x0F), true,
            if (isCa) Der.bitString(byteArrayOf(0x06), 1) else Der.bitString(byteArrayOf(0xA0.toByte()), 5)
        )
        val exts = Der.ctx(3, Der.seq(bc, ku))
        val tbs = Der.seq(
            Der.ctx(0, Der.int(BigInteger.valueOf(2))),
            Der.int(BigInteger.valueOf(serial)),
            algId,
            name(issuerCn),
            validity,
            name(subjectCn),
            subjectPub.encoded,
            exts
        )
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(issuerKey)
        sig.update(tbs)
        return Der.seq(tbs, algId, Der.bitString(sig.sign()))
    }

    fun pem(label: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$b64\n-----END $label-----\n"
    }

    fun unpem(pem: String): ByteArray {
        val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        return Base64.getMimeDecoder().decode(body.trim())
    }

    private class Reader(val b: ByteArray, var p: Int = 0) {
        fun tag(): Int = b[p++].toInt() and 0xff
        fun length(): Int {
            var l = b[p++].toInt() and 0xff
            if (l >= 0x80) {
                val n = l and 0x7f
                l = 0
                repeat(n) { l = (l shl 8) or (b[p++].toInt() and 0xff) }
            }
            return l
        }
        fun bytes(n: Int): ByteArray {
            val r = b.copyOfRange(p, p + n)
            p += n
            return r
        }
    }

    /** La llave publica del iPod llega como PEM (PKCS#1 "RSA PUBLIC KEY" o SPKI "PUBLIC KEY"). */
    fun parseDevicePublicKey(pemText: String): PublicKey {
        val der = unpem(pemText)
        val kf = KeyFactory.getInstance("RSA")
        if (pemText.contains("RSA PUBLIC KEY")) {
            val r = Reader(der)
            r.tag(); r.length()
            r.tag(); val n = BigInteger(1, r.bytes(r.length()))
            r.tag(); val e = BigInteger(1, r.bytes(r.length()))
            return kf.generatePublic(RSAPublicKeySpec(n, e))
        }
        return kf.generatePublic(X509EncodedKeySpec(der))
    }

    fun parseCert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(unpem(pem))) as X509Certificate

    /** Contexto TLS para hablar con lockdownd: certificado de cliente = el del emparejamiento; el del iPod no se valida. */
    fun tlsContext(rec: PairRecord): SSLContext {
        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(rec.hostKeyPkcs8))
        val hostCert = parseCert(rec.hostCertPem)
        val rootCert = parseCert(rec.rootCertPem)
        val km = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("host")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = "host"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(hostCert, rootCert)
            override fun getPrivateKey(alias: String?): PrivateKey = priv
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(km), arrayOf(tm), null)
        return ctx
    }
}
