package edu.uestc.checkin.model

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant

enum class RunOutcome { SUCCESS, READ_ONLY, FAILURE, UNCERTAIN }

data class RunStep(val stage: String, val resultCode: String, val httpStatus: Int? = null) {
    fun toSafeJson(): JSONObject = JSONObject()
        .put("stage", stage.takeIf { it.matches(Regex("[A-Z_]{2,24}")) } ?: "PROTOCOL")
        .put("resultCode", resultCode.takeIf { it.matches(Regex("[A-Z_]{2,48}")) } ?: "DETAIL_OMITTED")
        .put("httpStatus", httpStatus ?: JSONObject.NULL)
}

data class RunResult(
    val outcome: RunOutcome,
    val step: String,
    val reasonCode: String,
    val httpStatus: Int? = null,
    val occurredAt: Instant = Instant.now(),
    val steps: List<RunStep> = emptyList()
) {
    val safeStep: String get() = step.takeIf { it.matches(Regex("[A-Z_]{2,24}")) } ?: "PROTOCOL"
    val safeReasonCode: String get() = reasonCode.takeIf { it.matches(Regex("[A-Z_]{2,48}")) } ?: "DETAIL_OMITTED"

    fun toSafeJson(): JSONObject = JSONObject()
        .put("outcome", outcome.name)
        .put("step", safeStep)
        .put("reasonCode", safeReasonCode)
        .put("httpStatus", httpStatus ?: JSONObject.NULL)
        .put("occurredAt", occurredAt.toString())
        .put("steps", JSONArray().apply { steps.forEach { put(it.toSafeJson()) } })

    companion object {
        fun fromSafeJson(json: JSONObject) = RunResult(
            outcome = RunOutcome.valueOf(json.getString("outcome")),
            step = json.getString("step"),
            reasonCode = json.getString("reasonCode"),
            httpStatus = if (json.isNull("httpStatus")) null else json.getInt("httpStatus"),
            occurredAt = Instant.parse(json.getString("occurredAt")),
            steps = buildList {
                val items = json.optJSONArray("steps") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    add(RunStep(item.optString("stage"), item.optString("resultCode"),
                        if (item.isNull("httpStatus")) null else item.optInt("httpStatus")))
                }
            }
        )
    }
}
