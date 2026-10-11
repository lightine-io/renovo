package io.lightine.renovo.testserver

import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Random
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class TestServerTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private val servers = mutableListOf<RunningServer>()
    private val apkBytes = Random(7).let { random -> ByteArray(APK_SIZE).also(random::nextBytes) }
    private val json = """{"versionCode":2,"versionName":"2.0"}"""

    @Before
    fun setUp() {
        dir = folder.newFolder("served")
        File(dir, "version.json").writeText(json)
        File(dir, "app.apk").writeBytes(apkBytes)
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    @Test
    fun `json is served with a content type and an etag`() {
        val response = request("/version.json")
        assertEquals(200, response.status)
        assertEquals("application/json", response.header("Content-Type"))
        assertEquals(json, String(response.body, Charsets.UTF_8))
        val etag = response.header("ETag")
        assertNotNull(etag)
        assertTrue("The ETag is a quoted string: $etag", etag!!.startsWith("\"") && etag.endsWith("\""))
        assertEquals(etag, request("/version.json").header("ETag"))
    }

    @Test
    fun `json answers 304 to a matching if-none-match and 200 to another`() {
        val etag = request("/version.json").header("ETag")!!

        val unchanged = request("/version.json", "If-None-Match" to etag)
        assertEquals(304, unchanged.status)
        assertEquals(0, unchanged.body.size)

        assertEquals(200, request("/version.json", "If-None-Match" to "\"other\"").status)
    }

    @Test
    fun `the etag changes when the json changes`() {
        val before = request("/version.json").header("ETag")
        File(dir, "version.json").writeText("""{"versionCode":3,"versionName":"3.0"}""")
        val after = request("/version.json").header("ETag")
        assertTrue(before != after)
    }

    @Test
    fun `apk is served in full with its headers`() {
        val response = request("/app.apk")
        assertEquals(200, response.status)
        assertEquals("application/vnd.android.package-archive", response.header("Content-Type"))
        assertEquals("bytes", response.header("Accept-Ranges"))
        assertEquals(APK_SIZE.toString(), response.header("Content-Length"))
        assertNotNull(response.header("ETag"))
        assertNotNull(response.header("Last-Modified"))
        assertArrayEquals(apkBytes, response.body)
    }

    @Test
    fun `a range from the middle gives 206 with the right bytes`() {
        val start = APK_SIZE / 2
        val response = request("/app.apk", "Range" to "bytes=$start-")
        assertEquals(206, response.status)
        assertEquals("bytes $start-${APK_SIZE - 1}/$APK_SIZE", response.header("Content-Range"))
        assertEquals((APK_SIZE - start).toString(), response.header("Content-Length"))
        assertArrayEquals(apkBytes.copyOfRange(start, APK_SIZE), response.body)
    }

    @Test
    fun `a range with an end gives exactly those bytes`() {
        val response = request("/app.apk", "Range" to "bytes=100-199")
        assertEquals(206, response.status)
        assertEquals("bytes 100-199/$APK_SIZE", response.header("Content-Range"))
        assertArrayEquals(apkBytes.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `a range past the end gives 416 with the file size`() {
        val response = request("/app.apk", "Range" to "bytes=$APK_SIZE-")
        assertEquals(416, response.status)
        assertEquals("bytes */$APK_SIZE", response.header("Content-Range"))
        assertEquals(0, response.body.size)
    }

    @Test
    fun `if-range with the current etag or date keeps the range`() {
        val first = request("/app.apk")
        val byEtag = request("/app.apk", "Range" to "bytes=10-", "If-Range" to first.header("ETag")!!)
        assertEquals(206, byEtag.status)
        val byDate = request("/app.apk", "Range" to "bytes=10-", "If-Range" to first.header("Last-Modified")!!)
        assertEquals(206, byDate.status)
    }

    @Test
    fun `if-range with a stale validator gives 200 and the full file`() {
        val response = request("/app.apk", "Range" to "bytes=10-", "If-Range" to "\"stale\"")
        assertEquals(200, response.status)
        assertNull(response.header("Content-Range"))
        assertArrayEquals(apkBytes, response.body)
    }

    @Test
    fun `head has the headers and no body`() {
        val get = request("/app.apk")
        val head = request("/app.apk", method = "HEAD")
        assertEquals(200, head.status)
        assertEquals(APK_SIZE.toString(), head.header("Content-Length"))
        assertEquals(get.header("ETag"), head.header("ETag"))
        assertEquals("bytes", head.header("Accept-Ranges"))
        assertEquals("application/vnd.android.package-archive", head.header("Content-Type"))
        assertEquals(0, head.body.size)

        val jsonHead = request("/version.json", method = "HEAD")
        assertEquals(200, jsonHead.status)
        assertEquals(json.length.toString(), jsonHead.header("Content-Length"))
        assertEquals(0, jsonHead.body.size)
    }

    @Test
    fun `cut-after cuts the first download and a ranged request completes the file`() {
        val cutAfter = 100_000L
        val base = start(cutAfter = cutAfter)

        val first = request("/app.apk", base = base, tolerateCut = true)
        assertEquals(200, first.status)
        assertEquals(APK_SIZE.toString(), first.header("Content-Length"))
        assertEquals(cutAfter, first.body.size.toLong())
        assertArrayEquals(apkBytes.copyOfRange(0, cutAfter.toInt()), first.body)

        val second =
            request(
                "/app.apk",
                "Range" to "bytes=$cutAfter-",
                "If-Range" to first.header("ETag")!!,
                base = base,
            )
        assertEquals(206, second.status)
        assertArrayEquals(apkBytes, first.body + second.body)

        // Only the first download is cut.
        assertArrayEquals(apkBytes, request("/app.apk", base = base).body)
    }

    @Test
    fun `a head request does not use up the cut`() {
        val base = start(cutAfter = 1_000)
        assertEquals(200, request("/app.apk", method = "HEAD", base = base).status)
        assertEquals(1_000, request("/app.apk", base = base, tolerateCut = true).body.size)
    }

    @Test
    fun `unknown paths and missing files give 404`() {
        assertEquals(404, request("/nothing.json").status)
        assertEquals(404, request("/nothing.apk").status)
        assertEquals(404, request("/").status)
        assertEquals(404, request("/version.txt").status)
        assertEquals(404, request("/sub/version.json").status)
        assertEquals(404, request("/..%2Fversion.json").status)
    }

    @Test
    fun `the certificate has the three subject alternative names`() {
        val names = identity.certificate.subjectAlternativeNames.map { it[0] to it[1] }.toSet()
        assertEquals(setOf(SAN_IP to "10.0.2.2", SAN_IP to "127.0.0.1", SAN_DNS to "localhost"), names)
    }

    @Test
    fun `the exported pem is the certificate of the store`() {
        val pem = CertificateFactory.getInstance("X.509").generateCertificate(identity.pemFile.inputStream())
        assertEquals(identity.certificate, pem)
        assertTrue(Certificates.fingerprint(identity.certificate).matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }

    @Test
    fun `an existing certificate is reused`() {
        val again = Certificates.loadOrCreate(identity.pemFile.parentFile)
        assertEquals(identity.certificate, again.certificate)
    }

    @Test
    fun `arguments are read with their defaults`() {
        val options = parseArgs(arrayOf("--dir", dir.path))!!
        assertEquals(8443, options.port)
        assertEquals("0.0.0.0", options.bind)
        assertEquals(File(dir, ".cert"), options.certDir)
        assertNull(options.cutAfter)

        val full = parseArgs(arrayOf("--dir", dir.path, "--port", "9000", "--cert-dir", "c", "--cut-after", "5", "--bind", "127.0.0.1"))!!
        assertEquals(9000, full.port)
        assertEquals(File("c"), full.certDir)
        assertEquals(5L, full.cutAfter)
        assertEquals("127.0.0.1", full.bind)
    }

    @Test
    fun `bad arguments are reported`() {
        listOf(
            arrayOf(),
            arrayOf("--dir", "/nonexistent-folder-for-test"),
            arrayOf("--dir", dir.path, "--port", "x"),
            arrayOf("--dir", dir.path, "--cut-after", "0"),
            arrayOf("--dir", dir.path, "--port"),
            arrayOf("--dir", dir.path, "--unknown"),
        ).forEach { args ->
            try {
                parseArgs(args)
                throw AssertionError("Accepted ${args.toList()}")
            } catch (_: UsageException) {
                // Expected.
            }
        }
    }

    private class Response(
        val status: Int,
        private val headers: Map<String?, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun start(cutAfter: Long? = null): String {
        val options = Options(dir, port = 0, certDir = identity.pemFile.parentFile, cutAfter = cutAfter, bind = "127.0.0.1")
        val server = startServer(options, Certificates.serverContext(identity)) { }
        servers += server
        return "https://127.0.0.1:${server.port}"
    }

    private val defaultBase: String by lazy { start() }

    private fun request(
        path: String,
        vararg headers: Pair<String, String>,
        method: String = "GET",
        base: String = defaultBase,
        tolerateCut: Boolean = false,
    ): Response {
        val connection = URI("$base$path").toURL().openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = clientContext.socketFactory
            connection.requestMethod = method
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val body = ByteArrayOutputStream()
            if (stream != null) readAll(stream, body, tolerateCut)
            return Response(status, connection.headerFields, body.toByteArray())
        } finally {
            connection.disconnect()
        }
    }

    // A cut connection ends the stream early, with or without an exception depending on the JDK's
    // handling of the TLS close. The caller compares the byte count either way.
    private fun readAll(
        stream: InputStream,
        into: ByteArrayOutputStream,
        tolerateCut: Boolean,
    ) {
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    into.write(buffer, 0, read)
                }
            } catch (e: IOException) {
                if (!tolerateCut) throw e
            }
        }
    }

    companion object {
        private const val APK_SIZE = 300_000
        private const val TIMEOUT_MILLIS = 20_000
        private const val SAN_DNS = 2
        private const val SAN_IP = 7

        private lateinit var certFolder: TemporaryFolder
        private lateinit var identity: ServerIdentity
        private lateinit var clientContext: SSLContext

        @BeforeClass
        @JvmStatic
        fun createCertificate() {
            certFolder = TemporaryFolder().also { it.create() }
            identity = Certificates.loadOrCreate(File(certFolder.root, "cert"))
            // The client trusts exactly the certificate the server printed, as the sample app's
            // network security config will.
            val certificate = identity.pemFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
            val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            trust.setCertificateEntry("server", certificate)
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            clientContext = SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        }

        @AfterClass
        @JvmStatic
        fun deleteCertificate() {
            certFolder.delete()
        }
    }
}
