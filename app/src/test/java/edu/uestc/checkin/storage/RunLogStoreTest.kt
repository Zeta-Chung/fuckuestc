package edu.uestc.checkin.storage

import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.model.RunResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogStoreTest {
    @Test fun redactsSecretsBeforePersistence() {
        val secret = "SESSION=secret; ticket=ST-secret; 30.123456; home-address"
        val result = RunResult(RunOutcome.FAILURE, secret, secret)
        val stored = result.toSafeJson().toString()
        assertFalse(stored.contains("secret"))
        assertFalse(stored.contains("30.123456"))
        assertFalse(stored.contains("home-address"))
        assertTrue(stored.contains("DETAIL_OMITTED"))
    }
}
