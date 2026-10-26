package edu.uestc.checkin.run

import edu.uestc.checkin.model.CheckinConfig
import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.model.RunResult
import edu.uestc.checkin.model.RunStep
import edu.uestc.checkin.protocol.HttpTransport
import edu.uestc.checkin.protocol.IdasClient
import edu.uestc.checkin.protocol.JzszClient
import edu.uestc.checkin.protocol.LoginResult
import edu.uestc.checkin.protocol.TodayStatus
import edu.uestc.checkin.protocol.UrlConnectionTransport
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RunMode { READ_ONLY, SUBMIT_ONCE }

class CheckinRunner(
    private val ledger: AttemptLedger,
    private val transportFactory: () -> HttpTransport = { UrlConnectionTransport() },
    private val clock: Clock = Clock.systemUTC()
) {
    fun runOnce(config: CheckinConfig, mode: RunMode, onStep: (RunStep) -> Unit = {}): RunResult {
        if (config.validate().isNotEmpty()) return result(RunOutcome.FAILURE, "CONFIG", "CONFIG_INVALID")
        val steps = mutableListOf<RunStep>()
        val report: (RunStep) -> Unit = { step -> steps += step; onStep(step) }
        val final = try { runInternal(config, mode, report) }
        catch (_: IOException) { report(RunStep("NETWORK", "NETWORK_IO")); result(RunOutcome.FAILURE, "NETWORK", "NETWORK_IO") }
        catch (_: Exception) { report(RunStep("PROTOCOL", "PROTOCOL_ERROR")); result(RunOutcome.FAILURE, "PROTOCOL", "PROTOCOL_ERROR") }
        return final.copy(steps = steps.toList())
    }

    private fun runInternal(config: CheckinConfig, mode: RunMode, report: (RunStep) -> Unit): RunResult {
        val transport = transportFactory()
        val login = IdasClient().login(config, transport)
        when (login) {
            LoginResult.Success -> report(RunStep("IDAS", "SUCCESS"))
            LoginResult.NeedsHuman -> { report(RunStep("IDAS", "NEEDS_HUMAN")); return result(RunOutcome.FAILURE, "IDAS", "NEEDS_HUMAN") }
            is LoginResult.Failure -> { report(RunStep("IDAS", login.reasonCode, login.httpStatus)); return result(RunOutcome.FAILURE, "IDAS", login.reasonCode, login.httpStatus) }
        }
        val client = JzszClient(transport, config.userAgent)
        val portal = client.activate()
        report(RunStep("JZSZ", portal.reasonCode, portal.httpStatus))
        if (!portal.active) return result(RunOutcome.FAILURE, "JZSZ", portal.reasonCode, portal.httpStatus)
        val date = LocalDate.now(clock.withZone(config.zoneId))
        val first = client.today()
        report(RunStep("TODAY", first.status.name, first.httpStatus))
        if (first.status == TodayStatus.CHECKED_IN) return checkedIn(first.businessDate, first.abnormal, date, first.httpStatus)
        if (first.status != TodayStatus.PENDING || first.businessDate != date.toString())
            return result(RunOutcome.FAILURE, "TODAY", "TODAY_UNKNOWN", first.httpStatus)
        if (mode == RunMode.READ_ONLY) return result(RunOutcome.READ_ONLY, "TODAY", "PENDING", first.httpStatus)

        val schedule = client.schedule()
        report(RunStep("SCHEDULE", if (schedule.effective == true) "EFFECTIVE" else "UNKNOWN", schedule.httpStatus))
        val now = LocalTime.now(clock.withZone(config.zoneId))
        val minute = now.hour * 60 + now.minute
        if (schedule.effective != true || schedule.startMinute == null || schedule.endMinute == null)
            return result(RunOutcome.FAILURE, "SCHEDULE", "SCHEDULE_UNKNOWN", schedule.httpStatus)
        if (minute !in schedule.startMinute..schedule.endMinute)
            return result(RunOutcome.FAILURE, "SCHEDULE", "OUTSIDE_WINDOW", schedule.httpStatus)
        val locations = client.locations()
        report(RunStep("LOCATION", if (locations.locations.isNullOrEmpty()) "UNKNOWN" else "AVAILABLE", locations.httpStatus))
        val availableLocations = locations.locations?.takeIf { it.isNotEmpty() }
            ?: return result(RunOutcome.FAILURE, "LOCATION", "LOCATIONS_UNKNOWN", locations.httpStatus)
        val inside = availableLocations.any {
            distanceMeters(config.latitude.toDouble(), config.longitude.toDouble(), it.latitude.toDouble(), it.longitude.toDouble()) <= it.radiusMeters
        }
        if (!inside) return result(RunOutcome.FAILURE, "LOCATION", "CONFIG_LOCATION_OUTSIDE", locations.httpStatus)
        if (!ledger.claim(date)) return result(RunOutcome.UNCERTAIN, "CHECKIN", "ALREADY_ATTEMPTED")

        val payload = config.payloadJson(LocalDateTime.now(clock.withZone(config.zoneId)), JzszClient.effectiveSubmitUserAgent(config.userAgent))
        val submitted = try { client.submit(payload) } catch (_: IOException) { null }
        report(RunStep("CHECKIN", submitted?.status?.name ?: "TIMEOUT", submitted?.httpStatus, submitted?.serverMessage))
        val after = try { client.today() } catch (_: Exception) { null }
        report(RunStep("READBACK", after?.status?.name ?: "UNAVAILABLE", after?.httpStatus))
        if (after?.status == TodayStatus.CHECKED_IN) return checkedIn(after.businessDate, after.abnormal, date, after.httpStatus)
        if (submitted?.status == edu.uestc.checkin.protocol.SubmitStatus.REJECTED)
            return result(RunOutcome.FAILURE, "CHECKIN", "POST_REJECTED", submitted.httpStatus)
        return result(RunOutcome.UNCERTAIN, "CHECKIN", "POST_UNCONFIRMED", submitted?.httpStatus)
    }

    private fun checkedIn(businessDate: String?, abnormal: Boolean?, expected: LocalDate, httpStatus: Int?): RunResult {
        return if (businessDate == expected.toString() && abnormal == false)
            result(RunOutcome.SUCCESS, "TODAY", "CHECKED_IN", httpStatus)
        else result(RunOutcome.FAILURE, "TODAY", "CHECKED_IN_ABNORMAL_OR_STALE", httpStatus)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun result(outcome: RunOutcome, step: String, reason: String, status: Int? = null) =
        RunResult(outcome, step, reason, status, clock.instant())
}
