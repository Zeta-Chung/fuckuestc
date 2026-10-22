package edu.uestc.checkin.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class DomainCookieJarTest {
    @Test fun fingerprintNeverLeavesIdas() {
        val jar = DomainCookieJar()
        jar.setHostCookie("idas.uestc.edu.cn", "MULTIFACTOR_BROWSER_FINGERPRINT", "sample")
        assertTrue(jar.headerFor(URL("https://idas.uestc.edu.cn/authserver/login")).contains("sample"))
        assertFalse(jar.headerFor(URL("https://jzsz.uestc.edu.cn/")).contains("sample"))
    }

    @Test fun sessionNeverLeavesJzsz() {
        val jar = DomainCookieJar()
        jar.absorb(URL("https://jzsz.uestc.edu.cn/"), listOf("SESSION=session-test; Path=/; Secure; HttpOnly"))
        assertTrue(jar.headerFor(URL("https://jzsz.uestc.edu.cn/daily-checkin/")).contains("SESSION=session-test"))
        assertFalse(jar.headerFor(URL("https://idas.uestc.edu.cn/")).contains("session-test"))
    }

    @Test fun secureCookieRejectsPlainHttp() {
        val jar = DomainCookieJar()
        jar.absorb(URL("https://jzsz.uestc.edu.cn/"), listOf("SESSION=session-test; Path=/; Secure"))
        assertTrue(jar.headerFor(URL("http://jzsz.uestc.edu.cn/")).isEmpty())
    }

    @Test fun pathMatchesAtSegmentBoundary() {
        val jar = DomainCookieJar()
        jar.setHostCookie("idas.uestc.edu.cn", "scope", "sample", "/authserver")
        assertTrue(jar.headerFor(URL("https://idas.uestc.edu.cn/authserver/login")).contains("scope=sample"))
        assertFalse(jar.headerFor(URL("https://idas.uestc.edu.cn/authserverevil/login")).contains("scope=sample"))
    }
}
