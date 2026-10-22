package edu.uestc.checkin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URL

class JzszClientTest {
    private class Fake(private val replies: MutableList<HttpReply>) : HttpTransport {
        override val cookies = DomainCookieJar()
        val paths = mutableListOf<String>()
        override fun request(method: String, url: URL, headers: Map<String, String>, body: ByteArray?): HttpReply {
            paths += url.path
            return replies.removeAt(0)
        }
    }

    @Test fun activateSequence() {
        val fake = Fake(mutableListOf(
            HttpReply(200, emptyMap(), "{}"),
            HttpReply(302, mapOf("Location" to listOf("/daily-checkin-h5/index.html?appId=294")), ""),
            HttpReply(200, emptyMap(), "<html></html>")
        ))
        fake.cookies.setHostCookie("jzsz.uestc.edu.cn", "SESSION", "sample")
        val result = JzszClient(fake, "agent").activate()
        assertEquals(true, result.active)
        assertEquals(listOf("/api/isLoginFailure", "/daily-checkin/checkLogin", "/daily-checkin-h5/index.html"), fake.paths)
    }

    @Test fun loginPageWithHttp200IsNotPortalReady() {
        val fake = Fake(mutableListOf(
            HttpReply(200, emptyMap(), "{}"),
            HttpReply(200, emptyMap(), "<html>authserver/login</html>")
        ))
        fake.cookies.setHostCookie("jzsz.uestc.edu.cn", "SESSION", "sample")
        assertFalse(JzszClient(fake, "agent").activate().active)
    }

    @Test fun warmupRedirectIsNotPortalReady() {
        val fake = Fake(mutableListOf(HttpReply(302, mapOf("Location" to listOf("https://idas.uestc.edu.cn/authserver/login")), "")))
        fake.cookies.setHostCookie("jzsz.uestc.edu.cn", "SESSION", "sample")
        assertFalse(JzszClient(fake, "agent").activate().active)
        assertEquals(1, fake.paths.size)
    }

    @Test fun http200UnauthenticatedIsNotSuccess() {
        val fake = Fake(mutableListOf(HttpReply(200, emptyMap(), "{\"code\":401,\"ok\":false}")))
        val result = JzszClient(fake, "agent").today()
        assertEquals(TodayStatus.UNKNOWN, result.status)
    }

    @Test fun checkedInRequiresKnownEnvelope() {
        val fake = Fake(mutableListOf(HttpReply(200, emptyMap(), "{\"code\":0,\"ok\":true,\"data\":{\"businessDate\":\"2026-10-22\",\"recordStatusCode\":\"CHECKED_IN\",\"abnormalFlag\":false}}")))
        val result = JzszClient(fake, "agent").today()
        assertEquals(TodayStatus.CHECKED_IN, result.status)
        assertFalse(result.abnormal!!)
    }

    @Test fun businessRejectionWithHttp200IsRejected() {
        val fake = Fake(mutableListOf(HttpReply(200, emptyMap(), "{\"code\":40001,\"ok\":false,\"msg\":\"denied\"}")))
        val result = JzszClient(fake, "agent").submit(org.json.JSONObject().put("test", true))
        assertEquals(SubmitStatus.REJECTED, result.status)
    }
}
