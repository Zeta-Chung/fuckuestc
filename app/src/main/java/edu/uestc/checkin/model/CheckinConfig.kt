package edu.uestc.checkin.model

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CheckinConfig(
    val account: String,
    val password: String,
    val fingerprint: String,
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val address: String,
    val checkinTime: LocalTime,
    val zoneId: ZoneId,
    val userAgent: String,
    val accuracy: Int,
    val locationDurationMs: Long,
    val networkTypeCode: String,
    val locationEnabled: Boolean,
    val locationAuthorized: String,
    val locationAccuracy: String,
    val errorMessage: String
) {
    fun validate(): List<String> = buildList {
        if (account.isBlank()) add("账号不能为空")
        if (password.isBlank()) add("密码不能为空")
        if (fingerprint.isBlank()) add("可信指纹不能为空")
        if (fingerprint.any { it == ';' || it == '\r' || it == '\n' }) add("可信指纹格式无效")
        if (latitude < BigDecimal("-90") || latitude > BigDecimal("90")) add("纬度必须在 -90 到 90 之间")
        if (longitude < BigDecimal("-180") || longitude > BigDecimal("180")) add("经度必须在 -180 到 180 之间")
        if (checkinTime < CHECKIN_WINDOW_START || checkinTime > CHECKIN_WINDOW_END)
            add("打卡时间必须在 $WINDOW_LABEL_SPOKEN 之间")
        if (address.isBlank()) add("地址不能为空")
        if (userAgent.isBlank()) add("User-Agent 不能为空")
        if (userAgent.any { it == '\r' || it == '\n' }) add("User-Agent 格式无效")
        if (accuracy < 0) add("定位精度不能为负")
        if (locationDurationMs < 0) add("定位耗时不能为负")
        if (networkTypeCode.isBlank()) add("网络类型不能为空")
    }

    fun payload(now: LocalDateTime): JSONObject {
        require(validate().isEmpty()) { "配置无效" }
        return JSONObject()
            .put("latitude", latitude.setScale(6, RoundingMode.HALF_UP))
            .put("longitude", longitude.setScale(6, RoundingMode.HALF_UP))
            .put("reverseGeocode", address)
            .put("clientTime", now.format(CLIENT_TIME))
            .put("userAgent", userAgent)
            .put("coordinateSystemCode", "gcj02")
            .put("accuracy", accuracy)
            .put("locationDurationMs", locationDurationMs)
            .put("networkTypeCode", networkTypeCode)
            .put("locationEnabled", locationEnabled)
            .put("locationAuthorized", locationAuthorized)
            .put("locationAccuracy", locationAccuracy)
            .put("errorMessage", errorMessage)
    }

    fun payloadJson(now: LocalDateTime, userAgentOverride: String? = null): String {
        require(validate().isEmpty()) { "配置无效" }
        return buildString {
            append("{\"latitude\":")
            append(latitude.setScale(6, RoundingMode.HALF_UP).toPlainString())
            append(",\"longitude\":")
            append(longitude.setScale(6, RoundingMode.HALF_UP).toPlainString())
            append(",\"reverseGeocode\":")
            append(JSONObject.quote(address))
            append(",\"clientTime\":")
            append(JSONObject.quote(now.format(CLIENT_TIME)))
            append(",\"userAgent\":")
            append(JSONObject.quote(userAgentOverride ?: userAgent))
            append(",\"coordinateSystemCode\":\"gcj02\"")
            append(",\"accuracy\":$accuracy")
            append(",\"locationDurationMs\":$locationDurationMs")
            append(",\"networkTypeCode\":")
            append(JSONObject.quote(networkTypeCode))
            append(",\"locationEnabled\":$locationEnabled")
            append(",\"locationAuthorized\":")
            append(JSONObject.quote(locationAuthorized))
            append(",\"locationAccuracy\":")
            append(JSONObject.quote(locationAccuracy))
            append(",\"errorMessage\":")
            append(JSONObject.quote(errorMessage))
            append('}')
        }
    }

    fun toStoredJson(): JSONObject = JSONObject()
        .put("account", account)
        .put("password", password)
        .put("fingerprint", fingerprint)
        .put("latitude", latitude.toPlainString())
        .put("longitude", longitude.toPlainString())
        .put("address", address)
        .put("checkinTime", checkinTime.toString())
        .put("zoneId", zoneId.id)
        .put("userAgent", userAgent)
        .put("accuracy", accuracy)
        .put("locationDurationMs", locationDurationMs)
        .put("networkTypeCode", networkTypeCode)
        .put("locationEnabled", locationEnabled)
        .put("locationAuthorized", locationAuthorized)
        .put("locationAccuracy", locationAccuracy)
        .put("errorMessage", errorMessage)

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
        val CHECKIN_WINDOW_START: LocalTime = LocalTime.of(21, 0)
        val CHECKIN_WINDOW_END: LocalTime = LocalTime.of(23, 30)
        val WINDOW_LABEL: String = "${CHECKIN_WINDOW_START.format(TIME_FORMAT)}–${CHECKIN_WINDOW_END.format(TIME_FORMAT)}"
        private val WINDOW_LABEL_SPOKEN: String = "${CHECKIN_WINDOW_START.format(TIME_FORMAT)} 到 ${CHECKIN_WINDOW_END.format(TIME_FORMAT)}"
        private val CLIENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun fromStoredJson(json: JSONObject) = CheckinConfig(
            account = json.getString("account"),
            password = json.getString("password"),
            fingerprint = json.getString("fingerprint"),
            latitude = BigDecimal(json.getString("latitude")),
            longitude = BigDecimal(json.getString("longitude")),
            address = json.getString("address"),
            checkinTime = LocalTime.parse(json.getString("checkinTime")),
            zoneId = ZoneId.of(json.getString("zoneId")),
            userAgent = json.getString("userAgent"),
            accuracy = json.getInt("accuracy"),
            locationDurationMs = json.getLong("locationDurationMs"),
            networkTypeCode = json.getString("networkTypeCode"),
            locationEnabled = json.getBoolean("locationEnabled"),
            locationAuthorized = json.getString("locationAuthorized"),
            locationAccuracy = json.getString("locationAccuracy"),
            errorMessage = json.getString("errorMessage")
        )
    }
}
