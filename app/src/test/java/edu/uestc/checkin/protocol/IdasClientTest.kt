package edu.uestc.checkin.protocol

import edu.uestc.checkin.model.CheckinConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.net.URL
import java.time.LocalTime
import java.time.ZoneId

class IdasClientTest {
    private fun config() = CheckinConfig("test", "password", "trusted", BigDecimal("30"), BigDecimal("103"), "address", LocalTime.NOON, ZoneId.of("Asia/Shanghai"), "agent", 30, 612, "wifi", false, "", "", "")

    private class FakeTransport(private val replies: MutableList<HttpReply>) : HttpTransport {
        override val cookies = DomainCookieJar()
        val requests = mutableListOf<Pair<String, URL>>()
        val sentCookies = mutableListOf<String>()
        override fun request(method: String, url: URL, headers: Map<String, String>, body: ByteArray?): HttpReply {
            requests += method to url
            sentCookies += cookies.headerFor(url)
            val reply = replies.removeAt(0)
            cookies.absorb(url, reply.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value })
            return reply
        }
    }

    @Test fun missingExecutionStopsLogin() {
        val transport = FakeTransport(mutableListOf(HttpReply(200, emptyMap(), "<input id=\"pwdEncryptSalt\" value=\"1234567890123456\">")))
        assertTrue(IdasClient().login(config(), transport) is LoginResult.Failure)
        assertEquals(1, transport.requests.size)
    }

    @Test fun fingerprintIsPresentOnFirstGet() {
        val transport = FakeTransport(mutableListOf(HttpReply(200, emptyMap(), "<html></html>")))
        IdasClient().login(config(), transport)
        assertTrue(transport.sentCookies.first().contains("MULTIFACTOR_BROWSER_FINGERPRINT=trusted"))
    }

    @Test fun initialChallengeNeverPostsCredentials() {
        val transport = FakeTransport(mutableListOf(HttpReply(200, emptyMap(), "<html>请输入验证码</html>")))
        assertTrue(IdasClient().login(config(), transport) is LoginResult.NeedsHuman)
        assertEquals(1, transport.requests.size)
    }

    @Test fun challengeTextOnFormPageStillPostsCredentials() {
        val html = "<html>多因素认证 <input name=\"execution\" value=\"e1\"><input id=\"pwdEncryptSalt\" value=\"1234567890123456\"></html>"
        val transport = FakeTransport(mutableListOf(
            HttpReply(200, emptyMap(), html),
            HttpReply(302, mapOf("Location" to listOf("https://jzsz.uestc.edu.cn/?ticket=ST-test")), ""),
            HttpReply(200, emptyMap(), "home")
        ))
        IdasClient().login(config(), transport)
        assertEquals("POST", transport.requests[1].first)
    }

    @Test fun encryptMatchesIndependentAesVector() {
        val encrypted = IdasClient().encryptPassword("test-password", "1234567890123456", "A".repeat(64), "ABCDEFGHIJKLMNOP")
        assertEquals("DmMM9kupewi8n3CuleLDN0w/s9q5ugRIWOD7TI9HQNu7XtcUIU1ze5n6HbDZgP4qkv+Apq6tY3GNf0/9IW4LPnhyHQdhbWLO88vr/DbsAQc=", encrypted)
    }

    @Test fun mfaResponseNeverFollowsTicket() {
        val html = "<input name=\"execution\" value=\"e1\"><input id=\"pwdEncryptSalt\" value=\"1234567890123456\">"
        val transport = FakeTransport(mutableListOf(
            HttpReply(200, emptyMap(), html),
            HttpReply(200, emptyMap(), "需要二次验证")
        ))
        assertTrue(IdasClient().login(config(), transport) is LoginResult.NeedsHuman)
        assertEquals(2, transport.requests.size)
    }

    @Test fun missingSessionFailsAfterTicket() {
        val html = "<input name=\"execution\" value=\"e1\"><input id=\"pwdEncryptSalt\" value=\"1234567890123456\">"
        val transport = FakeTransport(mutableListOf(
            HttpReply(200, emptyMap(), html),
            HttpReply(302, mapOf("Location" to listOf("https://jzsz.uestc.edu.cn/?ticket=ST-test")), ""),
            HttpReply(200, emptyMap(), "home")
        ))
        assertTrue(IdasClient().login(config(), transport) is LoginResult.Failure)
        assertEquals(3, transport.requests.size)
    }
}
