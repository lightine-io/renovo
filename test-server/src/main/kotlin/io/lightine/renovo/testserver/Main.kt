package io.lightine.renovo.testserver

import java.io.File
import kotlin.system.exitProcess

/**
 * Starts the test server: a version file and an APK over HTTPS on this machine, for the acceptance
 * tests of the sample app.
 *
 * ```
 * ./gradlew :test-server:run --args="--dir sample/server"
 * ./gradlew :test-server:run --args="--dir sample/server --cut-after 4000000"
 * ```
 *
 * Options:
 * - `--dir <path>` the folder to serve (required). `GET /<name>.json` and `GET /<name>.apk` serve
 *   the files of that name in it.
 * - `--port <n>` the port (default 8443).
 * - `--cert-dir <path>` where the self-signed certificate and key store are created on the first
 *   start (default `<dir>/.cert`).
 * - `--cut-after <bytes>` cuts the first APK download after that many bytes by closing the
 *   connection, then serves normally. Used to test that a download resumes (AC-3).
 * - `--bind <address>` the address to listen on (default 0.0.0.0, so an emulator reaches the server
 *   as `10.0.2.2`).
 */
fun main(args: Array<String>) {
    val options =
        try {
            parseArgs(args)
        } catch (e: UsageException) {
            System.err.println("Error: ${e.message}")
            System.err.println()
            System.err.println(USAGE)
            exitProcess(EXIT_USAGE)
        }
    if (options == null) {
        println(USAGE)
        return
    }

    val identity = Certificates.loadOrCreate(options.certDir)
    val server = startServer(options, Certificates.serverContext(identity))
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })

    println("Serving ${options.dir.absolutePath}")
    println("Listening on https://${options.bind}:${server.port}/ (an emulator uses https://10.0.2.2:${server.port}/)")
    println("Certificate (PEM): ${identity.pemFile.absolutePath}")
    println("Certificate SHA-256: ${Certificates.fingerprint(identity.certificate)}")
    options.cutAfter?.let { println("The first APK download is cut after $it bytes.") }
}

private const val EXIT_USAGE = 2
private const val MAX_PORT = 65535

internal const val DEFAULT_PORT = 8443
internal const val DEFAULT_BIND = "0.0.0.0"

internal val USAGE =
    """
    Usage: test-server --dir <path> [--port <n>] [--cert-dir <path>] [--cut-after <bytes>] [--bind <address>]

      --dir <path>          folder to serve (required)
      --port <n>            port, 0 picks a free one (default $DEFAULT_PORT)
      --cert-dir <path>     where the certificate is kept (default <dir>/.cert)
      --cut-after <bytes>   cut the first APK download after this many bytes
      --bind <address>      address to listen on (default $DEFAULT_BIND)
    """.trimIndent()

internal class Options(
    val dir: File,
    val port: Int,
    val certDir: File,
    val cutAfter: Long?,
    val bind: String,
)

internal class UsageException(
    message: String,
) : Exception(message)

/**
 * Reads the command line. Returns null when help was asked for. Written by hand because the module
 * may not depend on an argument parser library.
 */
internal fun parseArgs(args: Array<String>): Options? {
    var dir: String? = null
    var port = DEFAULT_PORT
    var certDir: String? = null
    var cutAfter: Long? = null
    var bind = DEFAULT_BIND

    var index = 0

    fun value(name: String): String {
        if (index + 1 >= args.size) throw UsageException("$name needs a value.")
        index += 1
        return args[index]
    }
    while (index < args.size) {
        when (val name = args[index]) {
            "--help", "-h" -> return null

            "--dir" -> dir = value(name)

            "--port" ->
                port = value(name).toIntOrNull()?.takeIf { it in 0..MAX_PORT }
                    ?: throw UsageException("--port must be a number from 0 to $MAX_PORT.")

            "--cert-dir" -> certDir = value(name)

            "--cut-after" ->
                cutAfter = value(name).toLongOrNull()?.takeIf { it > 0 }
                    ?: throw UsageException("--cut-after must be a number of bytes above 0.")

            "--bind" -> bind = value(name)

            else -> throw UsageException("Unknown argument $name.")
        }
        index += 1
    }

    val servedDir = File(dir ?: throw UsageException("--dir is required."))
    if (!servedDir.isDirectory) throw UsageException("--dir ${servedDir.path} is not a folder.")
    return Options(
        dir = servedDir,
        port = port,
        certDir = certDir?.let(::File) ?: File(servedDir, ".cert"),
        cutAfter = cutAfter,
        bind = bind,
    )
}
