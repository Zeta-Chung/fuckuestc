package edu.uestc.checkin.schedule

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import edu.uestc.checkin.model.CheckinConfig
import edu.uestc.checkin.run.PreferencesAttemptLedger
import edu.uestc.checkin.storage.SecretStore
import java.time.ZonedDateTime

/**
 * 补偿执行：开机或打开应用时，如果当天已过设定时间、仍在学校窗口内且当天未提交过，
 * 立即补跑一次，避免关机或闹钟延迟导致漏打。
 */
internal object CheckinCompensation {
    fun runIfDue(context: Context) {
        val config = try { SecretStore(context).load() } catch (_: Exception) { null } ?: return
        if (!DailyScheduler(context).isEnabled()) return
        if (config.validate().isNotEmpty()) return
        val now = ZonedDateTime.now(config.zoneId)
        val attempted = PreferencesAttemptLedger(context).attempted(now.toLocalDate())
        if (!shouldRun(now, config, attempted)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "daily-checkin",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CheckinWorker>().build()
        )
    }

    internal fun shouldRun(now: ZonedDateTime, config: CheckinConfig, attemptedToday: Boolean): Boolean {
        if (attemptedToday) return false
        val date = now.toLocalDate()
        val start = date.atTime(CheckinConfig.CHECKIN_WINDOW_START).atZone(now.zone)
        val due = date.atTime(config.checkinTime).atZone(now.zone)
        val end = date.atTime(CheckinConfig.CHECKIN_WINDOW_END).atZone(now.zone)
        return !now.isBefore(start) && !now.isBefore(due) && !now.isAfter(end)
    }
}