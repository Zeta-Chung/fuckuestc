package edu.uestc.checkin.run

import android.content.Context
import java.time.LocalDate

interface AttemptLedger {
    fun claim(day: LocalDate): Boolean
}

class PreferencesAttemptLedger(context: Context) : AttemptLedger {
    private val preferences = context.getSharedPreferences("attempts", Context.MODE_PRIVATE)

    companion object { private val claimLock = Any() }

    override fun claim(day: LocalDate): Boolean = synchronized(claimLock) {
        val key = day.toString()
        if (preferences.getBoolean(key, false)) false
        else preferences.edit().putBoolean(key, true).commit()
    }

    fun attempted(day: LocalDate): Boolean = synchronized(claimLock) {
        preferences.getBoolean(day.toString(), false)
    }

    fun clear(day: LocalDate) = synchronized(claimLock) {
        preferences.edit().remove(day.toString()).apply()
    }
}
