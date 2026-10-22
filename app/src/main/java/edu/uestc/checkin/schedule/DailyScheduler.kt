package edu.uestc.checkin.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import edu.uestc.checkin.model.CheckinConfig
import edu.uestc.checkin.ui.FailureAlert
import java.time.LocalTime
import java.time.ZonedDateTime

data class ScheduleStatus(val enabled: Boolean, val exact: Boolean, val nextAt: ZonedDateTime?, val reasonCode: String)

class DailyScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val preferences = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    companion object {
        fun nextOccurrence(now: ZonedDateTime, at: LocalTime): ZonedDateTime {
            var next = now.toLocalDate().atTime(at).atZone(now.zone)
            if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(at).atZone(now.zone)
            return next
        }
    }

    fun schedule(config: CheckinConfig, alertsRequired: Boolean = true): ScheduleStatus {
        if (alertsRequired && !FailureAlert.notificationReady(context)) {
            cancel()
            return ScheduleStatus(false, false, null, "NOTIFICATION_REQUIRED")
        }
        if (config.validate().isNotEmpty()) {
            cancel()
            return ScheduleStatus(false, false, null, "CONFIG_INVALID")
        }
        val now = ZonedDateTime.now(config.zoneId)
        val next = nextOccurrence(now, config.checkinTime)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        val intent = PendingIntent.getBroadcast(context, 294, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), intent)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), intent)
            remember(next, false)
            return ScheduleStatus(true, false, next, "APPROXIMATE")
        }
        remember(next, exact)
        return ScheduleStatus(true, exact, next, if (exact) "EXACT" else "APPROXIMATE")
    }

    private fun remember(next: ZonedDateTime, exact: Boolean) {
        preferences.edit().putBoolean("enabled", true).putBoolean("exact", exact)
            .putLong("nextAt", next.toInstant().toEpochMilli()).putString("zoneId", next.zone.id).apply()
    }

    fun currentStatus(): ScheduleStatus {
        if (!isEnabled()) return ScheduleStatus(false, false, null, "DISABLED")
        val millis = preferences.getLong("nextAt", -1)
        val zone = preferences.getString("zoneId", null)
        val next = try {
            if (millis < 0 || zone == null) null
            else java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of(zone))
        } catch (_: Exception) { null }
        val exact = preferences.getBoolean("exact", false)
        return ScheduleStatus(true, exact, next, if (exact) "EXACT" else "APPROXIMATE")
    }

    fun cancel() {
        val intent = PendingIntent.getBroadcast(context, 294, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (intent != null) alarms.cancel(intent)
        preferences.edit().putBoolean("enabled", false).remove("nextAt").remove("zoneId").remove("exact").apply()
    }

    fun isEnabled(): Boolean = preferences.getBoolean("enabled", false)
}
