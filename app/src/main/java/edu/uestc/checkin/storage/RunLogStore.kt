package edu.uestc.checkin.storage

import android.content.Context
import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.model.RunResult
import org.json.JSONArray
import org.json.JSONObject

class RunLogStore(context: Context) {
    private val preferences = context.getSharedPreferences("run_log", Context.MODE_PRIVATE)

    @Synchronized
    fun append(result: RunResult) {
        val all = loadArray()
        all.put(result.toSafeJson())
        val trimmed = JSONArray()
        for (i in maxOf(0, all.length() - 50) until all.length()) trimmed.put(all.getJSONObject(i))
        val edit = preferences.edit().putString("recent", trimmed.toString())
        if (result.outcome == RunOutcome.FAILURE || result.outcome == RunOutcome.UNCERTAIN)
            edit.putString("unacknowledged", result.toSafeJson().toString())
        edit.apply()
    }

    @Synchronized
    fun recent(): List<RunResult> {
        val all = loadArray()
        return (0 until all.length()).mapNotNull { i ->
            try { RunResult.fromSafeJson(all.getJSONObject(i)) } catch (_: Exception) { null }
        }.reversed()
    }

    fun latestUnacknowledgedFailure(): RunResult? = try {
        preferences.getString("unacknowledged", null)?.let { RunResult.fromSafeJson(JSONObject(it)) }
    } catch (_: Exception) { null }

    fun acknowledgeLatestFailure() { preferences.edit().remove("unacknowledged").apply() }

    private fun loadArray(): JSONArray = try {
        JSONArray(preferences.getString("recent", "[]"))
    } catch (_: Exception) { JSONArray() }
}
