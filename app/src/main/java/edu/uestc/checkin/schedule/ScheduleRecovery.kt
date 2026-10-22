package edu.uestc.checkin.schedule

import android.content.Context
import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.model.RunResult
import edu.uestc.checkin.storage.RunLogStore
import edu.uestc.checkin.ui.FailureAlert

internal object ScheduleRecovery {
    fun fail(context: Context, reasonCode: String) {
        DailyScheduler(context).cancel()
        val result = RunResult(RunOutcome.FAILURE, "SCHEDULE", reasonCode)
        RunLogStore(context).append(result)
        FailureAlert.present(context, result)
    }
}
