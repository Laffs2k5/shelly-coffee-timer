package com.shellycoffee.timer.api

import android.content.Context
import com.shellycoffee.timer.R
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * mTLS material for the LAN broker (route B, spec 11 §3/§7).
 *
 * - Trust anchor: the private CA's *public* certificate, bundled in res/raw/mqtt_ca.crt
 *   (a public cert — safe to ship; only the CA private key is secret and never leaves the operator).
 * - Client identity: the phone's `YOUR_PHONE_ID` cert+key, imported at runtime as a PKCS#12. The client
 *   key is NEVER bundled in the app/repo; the user imports a .p12 from the operator (Settings screen).
 *
 * Mosquitto uses `use_identity_as_username`, so the cert CN is the broker identity — no password.
 */
object MqttTls {

    /** Build an mTLS SSLSocketFactory, or null if no client identity is available (→ skip local, use cloud). */
    fun localSocketFactory(context: Context, p12: ByteArray?, password: CharArray?): SSLSocketFactory? {
        if (p12 == null || p12.isEmpty() || password == null) return null
        return try {
            // Trust: bundled private CA (public cert).
            val cf = CertificateFactory.getInstance("X.509")
            val ca = context.resources.openRawResource(R.raw.mqtt_ca).use { cf.generateCertificate(it) }
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("mqtt-ca", ca)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trustStore)

            // Client identity: imported PKCS#12 (cert + key).
            val clientStore = KeyStore.getInstance("PKCS12")
            p12.inputStream().use { clientStore.load(it, password) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(clientStore, password)

            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(kmf.keyManagers, tmf.trustManagers, null)
            ctx.socketFactory
        } catch (_: Exception) {
            null
        }
    }
}
