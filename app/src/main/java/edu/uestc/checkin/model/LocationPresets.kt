package edu.uestc.checkin.model

import java.math.BigDecimal

/**
 * 一个允许打卡的位置预设。
 *
 * 经纬度与地址由使用者从 `/checkin-location/available-locations` 接口获取后填入；
 * 空字符串表示尚未配置，此时界面提示改用自定义坐标，保存时仍以输入框内容为准。
 */
data class LocationPreset(
    val id: String,
    val label: String,
    val latitude: String = "",
    val longitude: String = "",
    val address: String = ""
) {
    val configured: Boolean get() = latitude.isNotBlank() && longitude.isNotBlank()

    fun matches(latitudeText: String, longitudeText: String): Boolean =
        configured && sameNumber(latitude, latitudeText) && sameNumber(longitude, longitudeText)

    private fun sameNumber(expected: String, actual: String): Boolean =
        runCatching { BigDecimal(expected).compareTo(BigDecimal(actual.trim())) == 0 }.getOrDefault(false)
}

object LocationPresets {
    val XINYUAN = LocationPreset(
        id = "xinyuan",
        label = "欣苑",
        latitude = "30.674677",
        longitude = "104.096273",
        address = "成都市成华区一环路东一段240号"
    )
    val XIAONEI = LocationPreset(
        id = "xiaonei",
        label = "校内",
        latitude = "30.678471",
        longitude = "104.098075",
        address = "成都市成华区建设北路二段4号"
    )

    val ALL: List<LocationPreset> = listOf(XINYUAN, XIAONEI)

    fun find(latitude: String, longitude: String): LocationPreset? =
        ALL.firstOrNull { it.matches(latitude, longitude) }
}