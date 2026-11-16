/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSyncPhase1Test {
    private val now = 1_800_000_000_000L

    @Test fun plausibleTimesAccepted() {
        assertTrue(ClipboardSyncRules.isPlausibleVersionTime(0L, now))
        assertTrue(ClipboardSyncRules.isPlausibleVersionTime(now, now))
        // A logical clock that drifted days ahead in an existing room must still sync.
        assertTrue(ClipboardSyncRules.isPlausibleVersionTime(now + 30L * 24 * 3600 * 1000, now))
        assertTrue(ClipboardSyncRules.isPlausibleVersionTime(now + ClipboardSyncRules.MAX_REMOTE_FUTURE_MS, now))
    }

    @Test fun absurdTimesRejected() {
        assertFalse(ClipboardSyncRules.isPlausibleVersionTime(-1L, now))
        assertFalse(ClipboardSyncRules.isPlausibleVersionTime(now + ClipboardSyncRules.MAX_REMOTE_FUTURE_MS + 1, now))
        assertFalse(ClipboardSyncRules.isPlausibleVersionTime(Long.MAX_VALUE, now))
        // Saturation: a wall clock near MAX must not overflow the bound into negatives.
        assertTrue(ClipboardSyncRules.isPlausibleVersionTime(Long.MAX_VALUE, Long.MAX_VALUE - 5))
    }

    @Test fun logicalClockNeverOverflows() {
        assertEquals(Long.MAX_VALUE, ClipboardSyncRules.nextLogicalTime(now, 0, Long.MAX_VALUE, 0))
        assertEquals(Long.MAX_VALUE, ClipboardSyncRules.nextLogicalTime(now, 0, 0, Long.MAX_VALUE))
    }

    @Test fun logicalClockMatchesOldBehaviourInNormalRange() {
        for ((wall, remote, persisted, last) in listOf(
            listOf(now, 0L, 0L, 0L), listOf(now, now + 10, now, now), listOf(now, 0L, now + 99, now + 5),
            listOf(now, now - 1, now + 1, now + 7))) {
            val old = maxOf(wall, remote, persisted + 1L, last + 1L)
            assertEquals(old, ClipboardSyncRules.nextLogicalTime(wall, remote, persisted, last))
        }
    }

    @Test fun cacheHitsOnlyForSameGeneration() {
        val c = ClipboardSyncFingerprintCache(maxAgeMs = 1_000)
        assertNull(c.fresh(1, 0))
        c.put(1, 100, "a")
        assertEquals("a", c.fresh(1, 500))
        assertNull(c.fresh(2, 500))
        assertEquals("a", c.any())
    }

    @Test fun cacheExpiresByAgeAndClockJumpBack() {
        val c = ClipboardSyncFingerprintCache(maxAgeMs = 1_000)
        c.put(1, 100, "a")
        assertNull(c.fresh(1, 1_100))
        assertNull(c.fresh(1, 50)) // monotonic clock going backwards never trusts the cache
        c.clear(); assertNull(c.any())
    }

    @Test fun exchangeRuleUnchanged() {
        assertFalse(ClipboardSyncRules.requiresExchange("l", "l", "r", "r"))
        assertTrue(ClipboardSyncRules.requiresExchange("l2", "l", "r", "r"))
        assertTrue(ClipboardSyncRules.requiresExchange("l", "l", "r2", "r"))
        assertTrue(ClipboardSyncRules.requiresExchange("l", "l", "r", ""))
    }
}
