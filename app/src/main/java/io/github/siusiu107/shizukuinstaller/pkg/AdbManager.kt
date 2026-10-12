package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * libadb-android 의 연결 매니저 구현.
 * RSA 키 + 자체 서명 인증서를 생성/보관하고, 그 신원으로 기기의 무선 디버깅 데몬과 페어링/연결한다.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val dir = context.applicationContext.filesDir
        val keyFile = File(dir, "adb_key.pk8")
        val certFile = File(dir, "adb_cert.der")
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream())
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=SsukInstall, O=SsukInstall")
            val now = System.currentTimeMillis()
            val cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now),
                    Date(now - 86_400_000L),
                    Date(now + 10L * 365 * 24 * 3600 * 1000),
                    name,
                    kp.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)),
            )
            privateKey = kp.private
            certificate = cert
            runCatching {
                keyFile.writeBytes(privateKey.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "SsukInstall"

    companion object {
        @Volatile
        private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
    }
}
