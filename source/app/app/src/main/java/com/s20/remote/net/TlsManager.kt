package com.s20.remote.net

import android.content.Context
import android.util.Base64
import com.s20.remote.core.Config
import com.s20.remote.core.Logger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * HTTPS 证书管理。
 *  - selfsigned：首次启动用 BouncyCastle 生成 EC P-256 自签证书（SAN 含 DDNS 域名/设备名/本机 IP/localhost）
 *  - import：导入正式证书（PKCS12 或 PEM cert+key）
 *  - 私钥库存设备保护(DE)区，锁屏未解锁也可用
 */
object TlsManager {
    private const val TAG = "Tls"
    private const val DIR = "cert"
    private const val P12 = "server.p12"
    private const val ALIAS = "s20"
    private val bc = BouncyCastleProvider()

    @Volatile private var cachedFactory: SSLServerSocketFactory? = null
    @Volatile private var cachedCert: X509Certificate? = null
    @Volatile private var lastError: String? = null

    fun certDir(ctx: Context): File {
        val de = com.s20.remote.core.Compat.deContext(ctx)
        return File(de.filesDir, DIR).apply { if (!exists()) mkdirs() }
    }

    fun p12File(ctx: Context): File = File(certDir(ctx), P12)

    fun lastErrorMsg(): String? = lastError

    /** 取得（必要时生成）服务端 SSL 工厂；失败返回 null */
    fun serverSocketFactory(ctx: Context, forceNew: Boolean = false): SSLServerSocketFactory? {
        if (!forceNew) cachedFactory?.let { return it }
        return try {
            val f = p12File(ctx)
            if (forceNew || !f.exists()) generate(ctx)
            val pass = Config.tlsStorePass
            if (pass.isEmpty()) generate(ctx)
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(f).use { ks.load(it, Config.tlsStorePass.toCharArray()) }
            val cert = ks.getCertificate(ALIAS) as? X509Certificate
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, Config.tlsStorePass.toCharArray())
            val sc = SSLContext.getInstance("TLS")
            sc.init(kmf.keyManagers, null, SecureRandom())
            cachedCert = cert
            cachedFactory = sc.serverSocketFactory
            lastError = null
            Logger.i(TAG, "TLS ready, fingerprint=${fingerprint()}")
            cachedFactory
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Logger.e(TAG, "TLS init failed: $lastError")
            null
        }
    }

    /** 生成自签证书（覆盖已有） */
    fun generate(ctx: Context, extraDns: List<String> = emptyList()): JSONObject {
        val dns = LinkedHashSet<String>()
        Config.ddnsHost.trim().takeIf { it.isNotEmpty() }?.let { dns.add(it) }
        extraDns.forEach { if (it.isNotBlank()) dns.add(it.trim()) }
        Config.deviceName.trim().takeIf { it.isNotEmpty() }?.let { dns.add(it) }
        dns.add("localhost")

        val ips = LinkedHashSet<String>()
        ips.add("127.0.0.1")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.inetAddresses.toList().forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress) ips.add(a.hostAddress ?: "")
                }
            }
        } catch (_: Exception) { }
        ips.remove("")

        val kpg = KeyPairGenerator.getInstance("EC", bc)
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24 * 3600_000L)
        val notAfter = Date(now + 3650L * 24 * 3600_000L)
        val serial = BigInteger(96, SecureRandom()).abs().add(BigInteger.ONE)
        val cn = dns.firstOrNull() ?: "s20-remote"
        val subject = X500Name("CN=$cn, O=Remote Control, C=CN")

        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, kp.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val names = mutableListOf<GeneralName>()
        dns.forEach { names.add(GeneralName(GeneralName.dNSName, it)) }
        ips.forEach { names.add(GeneralName(GeneralName.iPAddress, it)) }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))

        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(bc).build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
        cert.verify(kp.public)

        val pass = Config.tlsStorePass.ifEmpty { randomPass() }
        Config.tlsStorePass = pass
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, pass.toCharArray(), arrayOf(cert))
        FileOutputStream(p12File(ctx)).use { ks.store(it, pass.toCharArray()) }
        Config.tlsMode = "selfsigned"

        cachedCert = cert
        cachedFactory = null
        lastError = null
        Logger.i(TAG, "self-signed cert generated CN=$cn dns=$dns ips=$ips")
        return info(ctx)
    }

    private fun randomPass(): String {
        val b = ByteArray(18)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 证书信息（指纹/有效期/SAN/模式） */
    fun info(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("mode", Config.tlsMode)
        o.put("tlsEnabled", Config.tlsEnabled)
        o.put("port", Config.httpPort)
        val f = p12File(ctx)
        o.put("certFile", f.absolutePath)
        o.put("exists", f.exists())
        val cert = cachedCert ?: loadCert(ctx)
        if (cert == null) {
            o.put("ok", false)
            o.put("error", lastError ?: "cert not found")
            return o
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        o.put("ok", true)
        o.put("subject", cert.subjectDN.name)
        o.put("issuer", cert.issuerDN.name)
        o.put("serial", cert.serialNumber.toString(16))
        o.put("notBefore", sdf.format(cert.notBefore))
        o.put("notAfter", sdf.format(cert.notAfter))
        o.put("sha256", sha256(cert))
        o.put("selfSigned", cert.subjectDN.name == cert.issuerDN.name)
        val sans = try { cert.subjectAlternativeNames } catch (e: Exception) { null }
        val arr = JSONArray()
        sans?.forEach { entry ->
            val type = entry.getOrNull(0)
            val value = entry.getOrNull(1)
            if (value != null) arr.put("${if (type == 2) "DNS" else "IP"}:$value")
        }
        o.put("san", arr)
        val httpUrl = (if (Config.tlsEnabled) "https" else "http") + "://" + (Config.ddnsHost.ifEmpty { "<域名或IP>" }) + ":" + Config.httpPort
        o.put("url", httpUrl)
        return o
    }

    fun fingerprint(): String {
        val c = cachedCert ?: return ""
        return sha256(c)
    }

    private fun sha256(cert: X509Certificate): String {
        val d = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return d.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun loadCert(ctx: Context): X509Certificate? = try {
        val f = p12File(ctx)
        if (!f.exists()) null else {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(f).use { ks.load(it, Config.tlsStorePass.toCharArray()) }
            (ks.getCertificate(ALIAS) as? X509Certificate)?.also { cachedCert = it }
        }
    } catch (e: Exception) {
        lastError = "load: ${e.message}"
        null
    }

    /** 证书 PEM（供浏览器/系统导入信任） */
    fun certPem(ctx: Context): String? {
        val cert = cachedCert ?: loadCert(ctx) ?: return null
        val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }

    /**
     * 导入正式证书。支持 PKCS12(.p12/.pfx) 与 PEM（证书链 + 私钥，可同文件或分文件）。
     * certPath 必填；keyPath 为空表示 PKCS12（或 PEM 内含私钥）。
     */
    fun import(ctx: Context, certPath: String, keyPath: String?, pass: String?): JSONObject {
        val src = File(certPath)
        if (!src.exists()) return JSONObject().put("ok", false).put("error", "file not found: $certPath")
        val p = pass ?: ""
        val looksP12 = certPath.endsWith(".p12", true) || certPath.endsWith(".pfx", true) ||
                (keyPath.isNullOrEmpty() && !isPem(src))
        if (looksP12) {
            return try {
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(src).use { ks.load(it, p.toCharArray()) }
                val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
                    ?: return JSONObject().put("ok", false).put("error", "no private key in p12")
                val cert = ks.getCertificate(alias) as? X509Certificate
                    ?: return JSONObject().put("ok", false).put("error", "no certificate in p12")
                // 统一别名为 s20 后另存
                val key = ks.getKey(alias, p.toCharArray())
                val out = KeyStore.getInstance("PKCS12")
                out.load(null, null)
                out.setKeyEntry(ALIAS, key, p.toCharArray(), arrayOf(cert))
                FileOutputStream(p12File(ctx)).use { out.store(it, p.toCharArray()) }
                Config.tlsStorePass = p
                Config.tlsMode = "import"
                cachedFactory = null; cachedCert = cert
                Logger.i(TAG, "imported PKCS12 ${src.name} alias=$alias")
                info(ctx).put("imported", true)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // PEM：收集证书与私钥
        return try {
            val certs = mutableListOf<X509Certificate>()
            var key: java.security.PrivateKey? = null
            val conv = JcaPEMKeyConverter().setProvider(bc)
            val files = listOfNotNull(src, keyPath?.let { File(it) }?.takeIf { it.exists() })
            for (f in files) {
                PEMParser(FileReader(f)).use { parser ->
                    var obj = parser.readObject()
                    while (obj != null) {
                        when (obj) {
                            is X509CertificateHolder -> certs.add(JcaX509CertificateConverter().setProvider(bc).getCertificate(obj))
                            is PEMKeyPair -> key = conv.getKeyPair(obj as PEMKeyPair).private
                            is PrivateKeyInfo -> key = conv.getPrivateKey(obj as PrivateKeyInfo)
                        }
                        obj = parser.readObject()
                    }
                }
            }
            if (certs.isEmpty()) return JSONObject().put("ok", false).put("error", "no certificate in PEM")
            val k = key ?: return JSONObject().put("ok", false).put("error", "no private key in PEM")
            val out = KeyStore.getInstance("PKCS12")
            out.load(null, null)
            out.setKeyEntry(ALIAS, k, p.toCharArray(), certs.toTypedArray())
            FileOutputStream(p12File(ctx)).use { out.store(it, p.toCharArray()) }
            Config.tlsStorePass = p
            Config.tlsMode = "import"
            cachedFactory = null; cachedCert = certs.first()
            Logger.i(TAG, "imported PEM ${src.name} chain=${certs.size}")
            info(ctx).put("imported", true)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun isPem(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val buf = ByteArray(64)
            val n = ins.read(buf)
            val head = String(buf, 0, maxOf(n, 0))
            head.contains("-----BEGIN")
        }
    } catch (e: Exception) { false }

    fun invalidate() { cachedFactory = null }
}
