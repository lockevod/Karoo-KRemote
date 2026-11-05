package com.enderthor.kremote.extension

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The wiring the extension sets up at startup that does not go through a key press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtensionWiringTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var remote: KremoteHarness

    @Before
    fun setUp() {
        remote = KremoteHarness(karoo)
        remote.resetConfig()
    }

    @Test
    fun `debug builds register a diagnostic consumer for every ride event`() {
        remote.seed()
        remote.start()

        listOf(
            RideState.Params,
            OnNavigationState.Params,
            OnGlobalPOIs.Params,
            SavedDevices.Params,
            Bikes.Params,
            ActiveRideProfile.Params,
            ActiveRidePage.Params,
            UserProfile.Params,
        ).forEach { karoo.awaitConsumer(it) }
    }

    @Test
    fun `the extension asks the app to start the connection service on launch`() {
        remote.seed()
        remote.start()

        // The application also sends this broadcast with is_extension=false; take the extension's.
        val launches = shadowOf(karoo.app).broadcastIntents.filter {
            it.action == "com.enderthor.kremote.START_CONNECTION_SERVICE" &&
                it.getBooleanExtra("is_extension", false)
        }

        assertEquals(1, launches.size)
    }
}
