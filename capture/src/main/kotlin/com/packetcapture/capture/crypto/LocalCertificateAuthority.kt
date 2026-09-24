package com.packetcapture.capture.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.packetcapture.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.*
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*

/** CA 私钥用 Android Keystore 的不可导出 AES 密钥包裹；网络层只能拿到临时 TLS 上下文。 */
class LocalCertificateAuthority(context: Context) : CertificateManager {
    private val directory = File(context.noBackupFilesDir, "ca").apply { mkdirs() }
    private val provider = Conscrypt.newProvider()
    private var rootKey: PrivateKey? = null
    private var rootCertificate: X509Certificate? = null
    private val contexts = object : LinkedHashMap<String, SSLContext>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLContext>?) = size > 128
    }
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("capture-ca-wrap-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("capture-ca-wrap-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun loadOrCreate() {
        if (rootKey != null) return
        val file = File(directory, "authority.bin")
        if (file.exists()) {
            // 加密文件或 Keystore 损坏时明确报错，不静默更换 CA 使已配置的测试应用突然失去信任。
            DataInputStream(file.inputStream()).use { input ->
                require(input.readInt() == 1) { "无法识别 CA 格式" }
                val iv = ByteArray(input.readInt().also { require(it in 12..32) }).also(input::readFully)
                val cert = ByteArray(input.readInt().also { require(it in 1..16384) }).also(input::readFully)
                val encrypted = ByteArray(input.readInt().also { require(it in 1..16384) }).also(input::readFully)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv)) }
                rootKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(cipher.doFinal(encrypted)))
                rootCertificate = CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
            }
            return
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Packet Capture Local CA,O=Local Debug")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, serial(), Date(now - 86400000), Date(now + 10L * 365 * 86400000), name, pair.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val encrypted = cipher.doFinal(pair.private.encoded)
        val temporary = File(directory, "authority.tmp")
        FileOutputStream(temporary).use { stream ->
            DataOutputStream(stream).apply {
                writeInt(1); writeInt(cipher.iv.size); write(cipher.iv)
                writeInt(certificate.encoded.size); write(certificate.encoded)
                writeInt(encrypted.size); write(encrypted); flush()
            }
            stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "CA 保存失败" }
        rootKey = pair.private; rootCertificate = certificate
    }
    override suspend fun ensureCertificate(): CertificateInfo = withContext(Dispatchers.IO) {
        loadOrCreate()
        rootCertificate!!.let { CertificateInfo(it.subjectX500Principal.name,
            MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString(":") { b -> "%02X".format(b.toInt() and 255) }, it.notAfter.time) }
    }
    override suspend fun exportCertificate(output: OutputStream) = withContext(Dispatchers.IO) {
        loadOrCreate()
        output.write(rootCertificate!!.encoded)
        output.flush()
    }
    /** 在证书线程池调用，避免 RSA 密钥生成阻塞 Netty event loop；缓存有上限且仅存在内存。 */
    @Synchronized fun serverContext(host: String): SSLContext {
        loadOrCreate()
        contexts[host]?.let { return it }
        require(host.length in 1..253 && host.none { it == '\n' || it == '\r' || it == '\u0000' }) { "非法 TLS 主机名" }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val root = rootCertificate!!
        val name = X500Name("CN=Local Debug Leaf")
        val ipLiteral = host.contains(':') || host.matches(Regex("[0-9.]+"))
        // RFC2253 文本可能反转 RDN 顺序；必须复用根证书的 DER 名称，否则 Android 无法关联颁发者。
        val builder = JcaX509v3CertificateBuilder(X500Name.getInstance(root.subjectX500Principal.encoded), serial(), Date(now - 3600000),
            Date(minOf(now + 7L * 86400000, root.notAfter.time)), name, pair.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(if (ipLiteral) GeneralName.iPAddress else GeneralName.dNSName, host)))
        val leaf = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(rootKey)))
        val password = UUID.randomUUID().toString().toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("leaf", pair.private, password, arrayOf(leaf, root)) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        return SSLContext.getInstance("TLS", provider).apply { init(managers.keyManagers, null, SecureRandom()) }.also { contexts[host] = it }
    }
    fun clientEngine(host: String, port: Int, protocol: String): SSLEngine {
        // 显式使用 Android 默认信任管理器，保留系统链校验与 debug 专用 Network Security Config。
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val context = SSLContext.getInstance("TLS", provider).apply { init(null, trust.trustManagers, SecureRandom()) }
        return context.createSSLEngine(host, port).apply {
            useClientMode = true
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            Conscrypt.setHostname(this, host)
            Conscrypt.setApplicationProtocols(this, arrayOf(protocol))
        }
    }
    fun serverEngine(context: SSLContext): SSLEngine = context.createSSLEngine().apply {
        useClientMode = false
        Conscrypt.setApplicationProtocols(this, arrayOf("h2", "http/1.1"))
    }
    fun applicationProtocol(engine: SSLEngine): String? = Conscrypt.getApplicationProtocol(engine)
    private fun serial() = BigInteger(159, SecureRandom()).add(BigInteger.ONE)
}
