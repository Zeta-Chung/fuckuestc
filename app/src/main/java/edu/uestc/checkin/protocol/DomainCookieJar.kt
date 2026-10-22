package edu.uestc.checkin.protocol

import java.net.URL

class DomainCookieJar {
    private data class Cookie(val name: String, val value: String, val host: String, val path: String, val secure: Boolean)
    private val cookies = mutableListOf<Cookie>()
    private val allowedHosts = setOf("idas.uestc.edu.cn", "jzsz.uestc.edu.cn")

    @Synchronized
    fun setHostCookie(host: String, name: String, value: String, path: String = "/", secure: Boolean = true) {
        require(host in allowedHosts)
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        require(!value.contains(';') && !value.contains('\n') && !value.contains('\r'))
        cookies.removeAll { it.host == host && it.name == name && it.path == path }
        cookies.add(Cookie(name, value, host, path, secure))
    }

    @Synchronized
    fun absorb(source: URL, setCookieHeaders: List<String>) {
        if (source.protocol != "https" || source.host !in allowedHosts) return
        for (line in setCookieHeaders) {
            val parts = line.split(';').map { it.trim() }
            val pair = parts.firstOrNull()?.split('=', limit = 2) ?: continue
            if (pair.size != 2) continue
            val name = pair[0]
            val value = pair[1]
            if (name.isBlank() || value.isBlank()) continue
            val path = parts.firstOrNull { it.startsWith("Path=", true) }?.substringAfter('=') ?: "/"
            val secure = parts.any { it.equals("Secure", true) }
            try { setHostCookie(source.host, name, value, path, secure) } catch (_: IllegalArgumentException) { }
        }
    }

    @Synchronized
    fun headerFor(target: URL): String {
        if (target.protocol != "https" || target.host !in allowedHosts) return ""
        return cookies.filter {
            it.host == target.host && pathMatches(it.path, target.path) && (!it.secure || target.protocol == "https")
        }.joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean =
        requestPath == cookiePath || requestPath.startsWith(cookiePath) &&
            (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/')

    @Synchronized
    fun get(host: String, name: String): String? = cookies.lastOrNull { it.host == host && it.name == name }?.value
}
