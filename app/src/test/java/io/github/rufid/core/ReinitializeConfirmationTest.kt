package io.github.rufid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReinitializeConfirmationTest {
    @Test
    fun acceptsSingleLetterR() {
        assertTrue(ReinitializeConfirmation.accepts("R"))
        assertTrue(ReinitializeConfirmation.accepts("r"))
        assertTrue(ReinitializeConfirmation.accepts(" R "))
    }

    @Test
    fun keepsLegacyFullConfirmationWorking() {
        assertTrue(ReinitializeConfirmation.accepts("REINITIALIZE"))
        assertTrue(ReinitializeConfirmation.accepts("reinitialize"))
    }

    @Test
    fun rejectsAmbiguousInput() {
        assertFalse(ReinitializeConfirmation.accepts(""))
        assertFalse(ReinitializeConfirmation.accepts("Y"))
        assertFalse(ReinitializeConfirmation.accepts("RUN"))
    }
}
