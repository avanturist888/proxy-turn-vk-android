package com.wdtt.client

import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun twoFailuresRestartButASuccessResetsTheStreak() {
        val policy = RecoveryPolicy()
        assertFalse(policy.record(false, 0))
        assertFalse(policy.record(true, 1000))
        assertFalse(policy.record(false, 2000))
        assertTrue(policy.record(false, 3000))
    }

    @Test fun repeatedBadCoverageIsRateLimitedAndRecoversAgain() {
        val policy = RecoveryPolicy()
        assertFalse(policy.record(false, 0))
        assertTrue(policy.record(false, 1000))
        assertFalse(policy.record(false, 2000))
        assertFalse(policy.record(false, 3000))
        assertTrue(policy.record(false, 16000))
        assertFalse(policy.record(true, 17000))
        assertFalse(policy.record(false, 32000))
        assertTrue(policy.record(false, 33000))
    }
}
