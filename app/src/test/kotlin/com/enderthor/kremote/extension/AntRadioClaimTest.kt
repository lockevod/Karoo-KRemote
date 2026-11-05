package com.enderthor.kremote.extension

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.RequestAnt
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AntRadioClaimTest {
    @get:Rule val karoo = FakeKarooRule(requireReleasedRadios = true)

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
        remote.seed()
    }

    @Test
    fun `the extension holds the ANT radio while it runs and gives it back when it stops`() {
        remote.start()

        karoo.awaitEffect<RequestAnt> { it.resourceId == "kremote" }
        assertEquals(setOf("kremote"), karoo.system.antClaims)

        // The rule also checks this at teardown; closing early makes the leak the failing line.
        karoo.close()
        assertEquals(emptySet<String>(), karoo.system.antClaims)
    }
}
