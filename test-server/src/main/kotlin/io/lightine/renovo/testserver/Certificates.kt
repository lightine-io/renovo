package io.lightine.renovo.testserver

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** The key store the server runs with, and the certificate in it. */
internal class ServerIdentity(
    val keyStore: KeyStore,
    val certificate: X509Certificate,
    val pemFile: File,
)

/**
 * Creates and loads the self-signed certificate of the test server.
 *
 * The JDK has no public API that creates a certificate, so the `keytool` of the running JDK does it.
 */
internal object Certificates {
    private const val ALIAS = "renovo-test-server"

    // The store only protects a throwaway key that exists to serve a local test server. It has to have
    // some password because PKCS12 requires one, and a fixed one lets later starts reopen the store.
    private const val STORE_PASSWORD = "renovo-test"
    private const val VALIDITY_DAYS = 365
    private const val KEYTOOL_TIMEOUT_SECONDS = 60L
    private const val STORE_NAME = "server.p12"
    private const val PEM_NAME = "server.pem"

    // A certificate that expires in the middle of an acceptance run would fail it for a reason nobody
    // can see, so one that is about to expire is replaced.
    private val renewBeforeExpiry: Duration = Duration.ofDays(1)

    /** Loads the certificate in [certDir], or creates it (and the folder) when it is missing or about to expire. */
    fun loadOrCreate(certDir: File): ServerIdentity {
        val store = File(certDir, STORE_NAME)
        val pem = File(certDir, PEM_NAME)
        if (store.isFile && pem.isFile) {
            val existing = load(store, pem)
            if (existing != null && existing.certificate.notAfter.toInstant().isAfter(Instant.now().plus(renewBeforeExpiry))) {
                return existing
            }
        }
        if (!certDir.isDirectory && !certDir.mkdirs()) throw IOException("Cannot create ${certDir.path}.")
        // keytool refuses to add an alias that already exists, so an old store must go first.
        store.delete()
        pem.delete()
        create(store, pem)
        return load(store, pem) ?: throw IOException("The new key store ${store.path} cannot be read.")
    }

    /** The server side TLS setup for [identity]. */
    fun serverContext(identity: ServerIdentity): SSLContext {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(identity.keyStore, STORE_PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    /** The SHA-256 fingerprint in the notation `keytool` prints, for comparing by eye. */
    fun fingerprint(certificate: X509Certificate): String = MessageDigest
        .getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { "%02X".format(it) }

    private fun load(
        store: File,
        pem: File,
    ): ServerIdentity? = try {
        val keyStore = KeyStore.getInstance("PKCS12")
        store.inputStream().use { keyStore.load(it, STORE_PASSWORD.toCharArray()) }
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
        certificate?.let { ServerIdentity(keyStore, it, pem) }
    } catch (_: IOException) {
        // A damaged or foreign store is replaced instead of blocking the start.
        null
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun create(
        store: File,
        pem: File,
    ) {
        keytool(
            "-genkeypair",
            "-alias", ALIAS,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-validity", VALIDITY_DAYS.toString(),
            "-dname", "CN=Renovo test server",
            // 10.0.2.2 is how the Android emulator reaches the host machine.
            "-ext", "san=ip:10.0.2.2,ip:127.0.0.1,dns:localhost",
            "-ext", "eku=serverAuth",
            "-storetype", "PKCS12",
            "-keystore", store.absolutePath,
            "-storepass", STORE_PASSWORD,
        )
        keytool(
            "-exportcert",
            "-rfc",
            "-alias", ALIAS,
            "-keystore", store.absolutePath,
            "-storepass", STORE_PASSWORD,
            "-file", pem.absolutePath,
        )
    }

    private fun keytool(vararg arguments: String) {
        val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "keytool.exe" else "keytool"
        val executable = File(File(System.getProperty("java.home"), "bin"), name)
        val process =
            ProcessBuilder(listOf(executable.path) + arguments)
                .redirectErrorStream(true)
                .start()
        // Read before waiting, so a full pipe cannot block keytool.
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("keytool did not finish in $KEYTOOL_TIMEOUT_SECONDS seconds.")
        }
        if (process.exitValue() != 0) throw IOException("keytool failed (exit ${process.exitValue()}): $output")
    }
}
