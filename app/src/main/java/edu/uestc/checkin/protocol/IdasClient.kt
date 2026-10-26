package edu.uestc.checkin.protocol

import edu.uestc.checkin.model.CheckinConfig
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed class LoginResult {
    data object Success : LoginResult()
    data object NeedsHuman : LoginResult()
    data class Failure(val reasonCode: String, val httpStatus: Int? = null) : LoginResult()
}

class IdasClient {
    private val loginUrl = URL("https://idas.uestc.edu.cn/authserver/login?service=https%3A%2F%2Fjzsz.uestc.edu.cn%2F")
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    fun login(config: CheckinConfig, transport: HttpTransport): LoginResult {
        transport.cookies.setHostCookie("idas.uestc.edu.cn", "MULTIFACTOR_BROWSER_FINGERPRINT", config.fingerprint)
        var pageUrl = loginUrl
        var page = transport.request("GET", pageUrl, mapOf("User-Agent" to config.userAgent, "Accept" to "text/html,application/xhtml+xml"))
        var hops = 0
        while (page.status in 300..399 && hops < 3) {
            val location = page.header("Location") ?: return LoginResult.Failure("IDAS_REDIRECT_MISSING", page.status)
            val next = URL(pageUrl, location)
            if (next.protocol != "https" || next.host != "idas.uestc.edu.cn")
                return LoginResult.Failure("IDAS_REDIRECT_HOST", page.status)
            pageUrl = next
            page = transport.request("GET", pageUrl, mapOf("User-Agent" to config.userAgent, "Accept" to "text/html,application/xhtml+xml"))
            hops++
        }
        if (page.status != 200) return LoginResult.Failure("IDAS_PAGE_HTTP", page.status)
        val execution = inputValue(page.body, "execution")
        val salt = inputValue(page.body, "pwdEncryptSalt")
            ?: Regex("pwdEncryptSalt[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']+)").find(page.body)?.groupValues?.get(1)
        if (execution == null || salt == null) {
            // 没有登录表单才说明页面被验证码/二次验证拦截；页面正文含相关字样不算。
            return if (looksLikeChallenge(page.body)) LoginResult.NeedsHuman
            else LoginResult.Failure(if (execution == null) "IDAS_EXECUTION_MISSING" else "IDAS_SALT_MISSING", page.status)
        }
        if (salt.toByteArray(Charsets.UTF_8).size != 16) return LoginResult.Failure("IDAS_SALT_INVALID")

        val fields = linkedMapOf(
            "username" to config.account,
            "password" to encryptPassword(config.password, salt, randomText(64), randomText(16)),
            "captcha" to "",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to "",
            "execution" to execution
        )
        val form = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }.toByteArray(Charsets.UTF_8)
        val submitted = transport.request("POST", pageUrl, mapOf(
            "User-Agent" to config.userAgent,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin" to "https://idas.uestc.edu.cn",
            "Referer" to pageUrl.toString()
        ), form)
        val location = submitted.header("Location")
        if (location == null || !location.contains("ticket=")) {
            // 先看是否被验证码/二次验证拦截，再区分登录失败与票据缺失。
            val challenge = looksLikeChallenge(submitted.body) ||
                location?.contains(Regex("multifactor|authLogin", RegexOption.IGNORE_CASE)) == true
            return when {
                challenge -> LoginResult.NeedsHuman
                submitted.status in 400..499 -> LoginResult.Failure("IDAS_LOGIN_REJECTED", submitted.status)
                else -> LoginResult.Failure("IDAS_TICKET_MISSING", submitted.status)
            }
        }

        var next = URL(loginUrl, location)
        repeat(5) {
            if (next.protocol != "https" || next.host != "jzsz.uestc.edu.cn") return LoginResult.Failure("CAS_REDIRECT_HOST")
            val reply = transport.request("GET", next, mapOf("User-Agent" to config.userAgent))
            val session = transport.cookies.get("jzsz.uestc.edu.cn", "SESSION")
            if (!session.isNullOrBlank()) return LoginResult.Success
            val redirect = reply.header("Location") ?: return LoginResult.Failure("JZSZ_SESSION_MISSING", reply.status)
            next = URL(next, redirect)
        }
        return LoginResult.Failure("CAS_REDIRECT_LIMIT")
    }

    fun encryptPassword(password: String, salt: String, prefix: String, iv: String): String {
        require(prefix.length == 64 && iv.toByteArray(Charsets.UTF_8).size == 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        return Base64.getEncoder().encodeToString(cipher.doFinal((prefix + password).toByteArray(Charsets.UTF_8)))
    }

    private fun looksLikeChallenge(html: String): Boolean =
        html.contains(Regex("请输入验证码|需要二次验证|多因素认证|滑块验证|captcha-required|multifactor", RegexOption.IGNORE_CASE))

    private fun inputValue(html: String, key: String): String? {
        val tag = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).firstOrNull {
            Regex("(?:name|id)\\s*=\\s*['\\\"]?$key['\\\"]?(?:\\s|/|>)", RegexOption.IGNORE_CASE).containsMatchIn(it.value)
        }?.value ?: return null
        return Regex("value\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
    }

    private fun randomText(size: Int) = buildString(size) { repeat(size) { append(alphabet[random.nextInt(alphabet.length)]) } }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
