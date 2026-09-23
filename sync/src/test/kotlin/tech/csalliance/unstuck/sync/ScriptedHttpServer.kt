package tech.csalliance.unstuck.sync

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A scripted HTTP server on 127.0.0.1 for the request-level tests: the real
 * supabase-kt 3.0.3 client talks to it, so what is pinned is what this client
 * actually puts on the wire and how it reads the answer. One request per
 * connection, HTTP/1.1, Content-Length bodies — enough for PostgREST writes and
 * edge-function calls (the JDK HttpServer is not on this classpath).
 */
internal class ScriptedHttpServer(private val answer: (Sent) -> Reply) {
    class Sent(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)
    class Reply(val status: Int, val body: String)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        Thread {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { c.use { serve(it) } }
            }
        }.apply { isDaemon = true; start() }
    }

    fun close() = socket.close()

    private fun serve(c: Socket) {
        val input = c.getInputStream().buffered()
        val (method, target) = (readLine(input) ?: return).split(' ').let { it[0] to it[1] }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var off = 0
        while (off < length) { val n = input.read(body, off, length - off); if (n < 0) break; off += n }
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val r = answer(Sent(method, target.substringBefore('?'), query, headers, body.decodeToString()))
        val bytes = r.body.toByteArray()
        val out = c.getOutputStream()
        out.write("HTTP/1.1 ${r.status} Scripted\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
