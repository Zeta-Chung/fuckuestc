package edu.uestc.checkin.schedule

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.model.RunResult
import edu.uestc.checkin.run.CheckinRunner
import edu.uestc.checkin.run.PreferencesAttemptLedger
import edu.uestc.checkin.run.RunMode
import edu.uestc.checkin.storage.RunLogStore
import edu.uestc.checkin.storage.SecretStore
import edu.uestc.checkin.ui.FailureAlert

class CheckinWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val config = try { SecretStore(applicationContext).load() } catch (_: Exception) { null }
        val result = try {
            if (config == null) RunResult(RunOutcome.FAILURE, "CONFIG", "CONFIG_MISSING")
            else CheckinRunner(PreferencesAttemptLedger(applicationContext)).runOnce(config, RunMode.SUBMIT_ONCE)
        } catch (_: Exception) {
            RunResult(RunOutcome.FAILURE, "WORKER", "WORKER_ERROR")
        }
        try { RunLogStore(applicationContext).append(result) } catch (_: Exception) { }
        if (result.outcome == RunOutcome.FAILURE || result.outcome == RunOutcome.UNCERTAIN) FailureAlert.present(applicationContext, result)
        return Result.success()
    }
}
