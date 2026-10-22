package edu.uestc.checkin.protocol

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

data class HttpReply(val status: Int, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

interface HttpTransport {
    val cookies: DomainCookieJar
    fun request(method: String, url: URL, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpReply
}

class UrlConnectionTransport(override val cookies: DomainCookieJar = DomainCookieJar()) : HttpTransport {
    override fun request(method: String, url: URL, headers: Map<String, String>, body: ByteArray?): HttpReply {
        require(url.protocol == "https" && url.host in setOf("idas.uestc.edu.cn", "jzsz.uestc.edu.cn"))
        require(method == "GET" || method == "POST")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            for ((key, value) in headers) connection.setRequestProperty(key, value)
            val cookieHeader = cookies.headerFor(url)
            if (cookieHeader.isNotEmpty()) connection.setRequestProperty("Cookie", cookieHeader)
            if (body != null) {
                require(method == "POST")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            val setCookies = responseHeaders.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
            cookies.absorb(url, setCookies)
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val raw = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    require(output.size() <= 262_144) { "响应超过允许大小" }
                }
                output.toByteArray()
            } ?: ByteArray(0)
            return HttpReply(status, responseHeaders, raw.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
