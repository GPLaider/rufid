package io.github.rufid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WindowsWriteModeTest {
    @Test
    fun exposesRawAndThreeExplicitWindowsBackends() {
        assertEquals(
            listOf("RawImage", "WindowsFat32", "WindowsNtfsMbr", "WindowsNtfsGpt"),
            IsoWriteMode.entries.map { it.name },
        )
    }

    @Test
    fun windowsPlannerRecommendsFat32WithoutForcingRawFallback() {
        val plan = IsoExtractionPlanner.plan(
            imageName = "windows.iso",
            entries = listOf(
                IsoFileEntry("bootmgr", 1),
                IsoFileEntry("efi/boot/bootx64.efi", 1),
                IsoFileEntry("sources/install.wim", 1024),
            ),
        )

        assertEquals("WindowsFat32", plan.recommendedMode.name)
        assertFalse(plan.recommendation.contains("auto", ignoreCase = true))
    }

    @Test
    fun separatesRawByteVerificationFromWindowsStructuralVerification() {
        val actual = IsoWriteMode.entries.associate { mode ->
            mode.name to mode.verificationKind.name
        }

        assertEquals(
            mapOf(
                "RawImage" to "RawBytes",
                "WindowsFat32" to "WindowsStructure",
                "WindowsNtfsMbr" to "WindowsStructure",
                "WindowsNtfsGpt" to "WindowsStructure",
            ),
            actual,
        )
    }
}
