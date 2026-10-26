package edu.uestc.checkin.protocol

import org.json.JSONObject
import java.math.BigDecimal
import java.net.URL

enum class TodayStatus { PENDING, CHECKED_IN, UNKNOWN, UNAUTHORIZED }
data class TodayResult(val status: TodayStatus, val businessDate: String? = null, val abnormal: Boolean? = null, val httpStatus: Int? = null)
data class PortalResult(val active: Boolean, val reasonCode: String, val httpStatus: Int? = null)
data class ScheduleResult(val effective: Boolean?, val startMinute: Int?, val endMinute: Int?, val httpStatus: Int? = null)
data class LocationResult(val locations: List<CheckinLocation>?, val httpStatus: Int? = null)
data class CheckinLocation(val latitude: BigDecimal, val longitude: BigDecimal, val radiusMeters: Double)
data class AddressResult(val address: String?, val httpStatus: Int? = null)
enum class SubmitStatus { ACCEPTED, REJECTED, UNKNOWN }
data class SubmitResult(val status: SubmitStatus, val httpStatus: Int? = null, val serverMessage: String? = null)

class JzszClient(private val transport: HttpTransport, private val userAgent: String) {
    private val base = "https://jzsz.uestc.edu.cn"
    private var sessionId: String? = null
    private val fallbackSessionId by lazy { java.util.UUID.randomUUID().toString() }

    private fun h5Headers(): Map<String, String> {
        val id = sessionId ?: fallbackSessionId
        return mapOf(
            "User-Agent" to userAgent,
            "Platform" to "h5",
            "Accept" to "*/*",
            "Origin" to base,
            "Referer" to "$base/daily-checkin-h5/index.html?sessionId=$id",
            "X-Requested-With" to "com.tencent.mm",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )
    }

    private fun captureSessionId(url: URL) {
        Regex("[?&]sessionId=([^&#]+)").find(url.toString())?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }?.let { sessionId = it }
    }

    fun activate(): PortalResult {
        if (transport.cookies.get("jzsz.uestc.edu.cn", "SESSION").isNullOrBlank()) return PortalResult(false, "SESSION_MISSING")
        val warmup = transport.request("GET", URL("$base/api/isLoginFailure?_t=${System.currentTimeMillis()}"), mapOf("User-Agent" to userAgent))
        if (warmup.status != 200) return PortalResult(false, "PORTAL_WARMUP_HTTP", warmup.status)
        var next = URL("$base/daily-checkin/checkLogin?appId=294")
        repeat(4) {
            captureSessionId(next)
            val reply = transport.request("GET", next, mapOf("User-Agent" to userAgent))
            if (reply.status == 200) {
                val expectedPage = next.path == "/daily-checkin-h5/index.html"
                val loginPage = reply.body.contains(Regex("authserver/login|请输入密码|登录失败|多因素认证", RegexOption.IGNORE_CASE))
                return if (expectedPage && !loginPage) PortalResult(true, "PORTAL_READY", reply.status)
                else PortalResult(false, "PORTAL_UNVERIFIED", reply.status)
            }
            if (reply.status !in 300..399) return PortalResult(false, "CHECK_LOGIN_HTTP", reply.status)
            val location = reply.header("Location") ?: return PortalResult(false, "CHECK_LOGIN_REDIRECT_MISSING", reply.status)
            next = URL(next, location)
            if (next.protocol != "https" || next.host != "jzsz.uestc.edu.cn") return PortalResult(false, "CHECK_LOGIN_REDIRECT_HOST", reply.status)
        }
        return PortalResult(false, "CHECK_LOGIN_REDIRECT_LIMIT")
    }

    fun today(): TodayResult {
        val reply = transport.request("GET", URL("$base/daily-checkin/api/student-checkin-record/today"), h5Headers())
        if (reply.status == 401 || reply.status == 403 || reply.status in 300..399) return TodayResult(TodayStatus.UNAUTHORIZED, httpStatus = reply.status)
        val data = validEnvelope(reply)?.optJSONObject("data") ?: return TodayResult(TodayStatus.UNKNOWN, httpStatus = reply.status)
        val date = data.optString("businessDate").takeIf { it.isNotBlank() }
        val abnormal = if (data.has("abnormalFlag") && !data.isNull("abnormalFlag")) data.optBoolean("abnormalFlag") else null
        return when (data.optString("recordStatusCode")) {
            "PENDING" -> TodayResult(TodayStatus.PENDING, date, abnormal, reply.status)
            "CHECKED_IN" -> TodayResult(TodayStatus.CHECKED_IN, date, abnormal, reply.status)
            else -> TodayResult(TodayStatus.UNKNOWN, date, abnormal, reply.status)
        }
    }

    fun schedule(): ScheduleResult {
        val reply = transport.request("GET", URL("$base/daily-checkin/api/checkin-schedule/effective"), h5Headers())
        val data = validEnvelope(reply)?.optJSONObject("data") ?: return ScheduleResult(null, null, null, reply.status)
        val effective = if (data.has("effective")) data.optBoolean("effective") else null
        val start = data.optInt("studentCheckinStartOffsetMinute", -1).takeIf { it >= 0 }
        val end = data.optInt("studentCheckinEndOffsetMinute", -1).takeIf { it >= 0 }
        return ScheduleResult(effective, start, end, reply.status)
    }

    fun locations(): LocationResult {
        val reply = transport.request("GET", URL("$base/daily-checkin/api/checkin-location/available-locations"), h5Headers())
        val data = validEnvelope(reply)?.optJSONArray("data") ?: return LocationResult(null, reply.status)
        val parsed = buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val latitude = item.optString("latitude").toBigDecimalOrNull() ?: continue
                val longitude = item.optString("longitude").toBigDecimalOrNull() ?: continue
                val radius = item.optDouble("radiusMeter", -1.0)
                if (radius >= 0) add(CheckinLocation(latitude, longitude, radius))
            }
        }
        return LocationResult(parsed, reply.status)
    }

    fun reverseGeocode(lat: BigDecimal, lon: BigDecimal): AddressResult {
        val reply = transport.request("GET", URL("$base/daily-checkin/api/map/reverse?lat=${lat.toPlainString()}&lng=${lon.toPlainString()}"), h5Headers())
        val root = validEnvelope(reply) ?: return AddressResult(null, reply.status)
        return AddressResult(root.optString("data").takeIf { it.isNotBlank() }, reply.status)
    }

    fun submit(payload: JSONObject): SubmitResult = submit(payload.toString())

    fun submit(payloadJson: String): SubmitResult {
        // 打卡接口会校验微信小程序标识；IDAS 登录仍用配置里的 UA，避免触发微信专用登录页。
        val effectiveUa = effectiveSubmitUserAgent(userAgent)
        val headers = h5Headers() +
            ("User-Agent" to effectiveUa) +
            ("Content-Type" to "application/json; charset=UTF-8")
        val reply = transport.request("POST", URL("$base/daily-checkin/api/student-checkin-record/checkin"),
            headers, payloadJson.toByteArray(Charsets.UTF_8))
        val root = try { JSONObject(reply.body) } catch (_: Exception) { null }
        val ok = root?.optBoolean("ok", false) == true
        val code = root?.optInt("code", -1) ?: -1
        val message = root?.optString("msg").orEmpty().ifBlank { root?.optString("message").orEmpty() }
            .trim().take(120).takeIf { it.isNotBlank() }
        return when {
            reply.status == 200 && ok -> SubmitResult(SubmitStatus.ACCEPTED, reply.status, message)
            reply.status in 400..499 -> SubmitResult(SubmitStatus.REJECTED, reply.status, message)
            reply.status == 200 && root != null && (code > 0 || !ok) ->
                SubmitResult(SubmitStatus.REJECTED, reply.status, message)
            else -> SubmitResult(SubmitStatus.UNKNOWN, reply.status, message)
        }
    }

    private fun validEnvelope(reply: HttpReply): JSONObject? {
        if (reply.status != 200) return null
        val root = try { JSONObject(reply.body) } catch (_: Exception) { return null }
        return root.takeIf { it.optInt("code", -1) == 0 && it.optBoolean("ok", false) }
    }

    companion object {
        private const val WECHAT_SUBMIT_UA =
            "Mozilla/5.0 (Linux; Android 14; TNA-AN22 Build/HONORTNA-AN22; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/150.0.7871.189 Mobile Safari/537.36 " +
                "XWEB/1500145 MMWEBSDK/20260902 MMWEBID/445 MicroMessenger/8.0.79.3200(0x28004F32) " +
                "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64 miniProgram/wxaa78bf2905f01cee"

        fun effectiveSubmitUserAgent(userAgent: String): String =
            if (userAgent.contains("MicroMessenger", true)) userAgent else WECHAT_SUBMIT_UA
    }
}
