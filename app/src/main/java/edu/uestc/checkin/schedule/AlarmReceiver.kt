package edu.uestc.checkin.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import edu.uestc.checkin.storage.SecretStore

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = DailyScheduler(context)
        if (!scheduler.isEnabled()) return
        val config = try { SecretStore(context).load() } catch (_: Exception) { null }
        if (config == null) {
            ScheduleRecovery.fail(context, "CONFIG_MISSING")
            return
        }
        val recovered = try { scheduler.schedule(config, alertsRequired = false).enabled }
        catch (_: Exception) { false }
        if (!recovered) ScheduleRecovery.fail(context, "SCHEDULE_RECOVERY_FAILED")
        val request = OneTimeWorkRequestBuilder<CheckinWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("daily-checkin", ExistingWorkPolicy.KEEP, request)
    }
}
