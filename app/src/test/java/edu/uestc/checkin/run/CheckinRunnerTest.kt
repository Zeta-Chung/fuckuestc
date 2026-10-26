package edu.uestc.checkin.run

import edu.uestc.checkin.model.CheckinConfig
import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.protocol.DomainCookieJar
import edu.uestc.checkin.protocol.HttpReply
import edu.uestc.checkin.protocol.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CheckinRunnerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T14:00:00Z"), ZoneId.of("UTC"))
    private fun config() = CheckinConfig("test", "password", "trusted", BigDecimal("30"), BigDecimal("103"), "address", LocalTime.of(22, 0), ZoneId.of("Asia/Shanghai"), "agent", 30, 612, "wifi", false, "", "", "")
    private class Ledger : AttemptLedger {
        var claimed = false
        override fun claim(day: LocalDate): Boolean = if (claimed) false else { claimed = true; true }
    }
    private class Fake(private val replies: MutableList<Any>) : HttpTransport {
        override val cookies = DomainCookieJar()
        val submittedPaths = mutableListOf<String>()
        override fun request(method: String, url: URL, headers: Map<String, String>, body: ByteArray?): HttpReply {
            if (method == "POST") submittedPaths += url.path
            val item = replies.removeAt(0)
            if (item is IOException) throw item
            val reply = item as HttpReply
            cookies.absorb(url, reply.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value })
            return reply
        }
    }
    private fun ok(body: String) = HttpReply(200, emptyMap(), body)
    private fun initial(today: String): MutableList<Any> = mutableListOf(
        ok("<input name=\"execution\" value=\"e1\"><input id=\"pwdEncryptSalt\" value=\"1234567890123456\">"),
        HttpReply(302, mapOf("Location" to listOf("https://jzsz.uestc.edu.cn/?ticket=ST-test")), ""),
        HttpReply(200, mapOf("Set-Cookie" to listOf("SESSION=session-test; Path=/; Secure")), ""),
        ok("{}"),
        HttpReply(302, mapOf("Location" to listOf("/daily-checkin-h5/index.html?appId=294")), ""),
        ok("<html></html>"), ok(today)
    )
    private fun today(status: String) = """{"code":0,"ok":true,"data":{"businessDate":"2026-10-22","recordStatusCode":"$status","abnormalFlag":false}}"""
    private val schedule = """{"code":0,"ok":true,"data":{"effective":true,"studentCheckinStartOffsetMinute":1260,"studentCheckinEndOffsetMinute":1410}}"""
    private val locations = """{"code":0,"ok":true,"data":[{"latitude":30,"longitude":103,"radiusMeter":130}]}"""

    @Test fun readOnlyNeverPosts() {
        val fake = Fake(initial(today("PENDING")))
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.READ_ONLY)
        assertEquals(RunOutcome.READ_ONLY, result.outcome)
        assertEquals(0, fake.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun alreadyCheckedInNeverPosts() {
        val fake = Fake(initial(today("CHECKED_IN")))
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.SUCCESS, result.outcome)
        assertEquals(0, fake.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun timedOutPostReadsBackOnce() {
        val replies = initial(today("PENDING"))
        replies += ok(schedule)
        replies += ok(locations)
        replies += IOException("network timeout")
        replies += ok(today("CHECKED_IN"))
        val fake = Fake(replies)
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.SUCCESS, result.outcome)
        assertEquals(1, fake.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun unknownSuccessFieldNeverPasses() {
        val replies = initial(today("PENDING"))
        replies += ok(schedule)
        replies += ok(locations)
        replies += ok("""{"code":0,"ok":true,"data":"unknown"}""")
        replies += ok(today("PENDING"))
        val fake = Fake(replies)
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.UNCERTAIN, result.outcome)
        assertEquals(1, fake.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun rejectedPostFailsWithoutRetry() {
        val replies = initial(today("PENDING"))
        replies += ok(schedule)
        replies += ok(locations)
        replies += HttpReply(400, emptyMap(), "bad request")
        replies += ok(today("PENDING"))
        val fake = Fake(replies)
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.FAILURE, result.outcome)
        assertEquals("POST_REJECTED", result.reasonCode)
        assertEquals(1, fake.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun secondRunDoesNotSubmitAgain() {
        val ledger = Ledger()
        val firstReplies = initial(today("PENDING"))
        firstReplies += ok(schedule)
        firstReplies += ok(locations)
        firstReplies += IOException("response lost")
        firstReplies += ok(today("PENDING"))
        val first = Fake(firstReplies)
        val firstResult = CheckinRunner(ledger, { first }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.UNCERTAIN, firstResult.outcome)

        val secondReplies = initial(today("PENDING"))
        secondReplies += ok(schedule)
        secondReplies += ok(locations)
        val second = Fake(secondReplies)
        val secondResult = CheckinRunner(ledger, { second }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.UNCERTAIN, secondResult.outcome)
        assertEquals(0, second.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun offlineNetworkFailureNeverPosts() {
        val fake = Fake(mutableListOf<Any>(IOException("offline")))
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.FAILURE, result.outcome)
        assertEquals("NETWORK_IO", result.reasonCode)
        assertEquals(0, fake.submittedPaths.count { it.endsWith("/checkin") })
    }

    @Test fun outsideWindowNeverPosts() {
        val replies = initial(today("PENDING"))
        replies += ok("""{"code":0,"ok":true,"data":{"effective":true,"studentCheckinStartOffsetMinute":600,"studentCheckinEndOffsetMinute":700}}""")
        val fake = Fake(replies)
        val result = CheckinRunner(Ledger(), { fake }, clock).runOnce(config(), RunMode.SUBMIT_ONCE)
        assertEquals(RunOutcome.FAILURE, result.outcome)
        assertEquals("OUTSIDE_WINDOW", result.reasonCode)
        assertEquals(0, fake.submittedPaths.count { it.endsWith("/checkin") })
    }
}
