package io.github.rufid.core

import io.github.rufid.windows.WindowsIsoPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoExtractionPlanTest {
    @Test
    fun windowsInstallerIsoRequiresWimSplitWhenInstallWimExceedsFat32Limit() {
        val plan = IsoExtractionPlanner.plan(
            imageName = "Win11_English_x64.iso",
            entries = listOf(
                IsoFileEntry("bootmgr", 415_000),
                IsoFileEntry("efi/boot/bootx64.efi", 1_500_000),
                IsoFileEntry("sources/install.wim", WindowsIsoPlan.FAT32_MAX_FILE_SIZE + 1),
            ),
        )

        assertEquals(IsoExtractionSupport.WindowsInstaller, plan.support)
        assertEquals(IsoWriteMode.WindowsFat32, plan.recommendedMode)
        assertEquals("FAT32 UEFI installer", plan.targetLayout)
        assertTrue(plan.requiresWimSplit)
        assertTrue(plan.summaryLines().any { it.contains("install.wim split required: yes") })
    }

    @Test
    fun unknownIsoFallsBackToRawImageRecommendation() {
        val plan = IsoExtractionPlanner.plan(
            imageName = "debian-13.5.0-amd64-netinst.iso",
            entries = listOf(
                IsoFileEntry("efi/boot/bootx64.efi", 1_500_000),
                IsoFileEntry("install.amd/vmlinuz", 8_000_000),
            ),
        )

        assertEquals(IsoExtractionSupport.Unsupported, plan.support)
        assertEquals(IsoWriteMode.RawImage, plan.recommendedMode)
        assertEquals("Use Raw image mode.", plan.recommendation)
        assertTrue(plan.summaryLines().any { it.contains("ISO extraction not supported") })
    }

    @Test
    fun isoWriteMessagesNameCurrentModeAndFollowUpChecks() {
        val notice = IsoExtractionPlanner.preWriteNotice("Win11_English_x64.iso")
        val finished = IsoExtractionPlanner.rawWriteFinishedMessage("debian-13.5.0-amd64-netinst.iso")

        assertTrue(notice?.contains("never fall back to raw writing") == true)
        assertTrue(notice?.contains("explicit Windows write method") == true)
        assertTrue(finished.contains("Raw image write finished"))
        assertTrue(finished.contains("Verify or Inspect USB"))
        assertTrue(finished.contains("format or repair raw ISO media"))
    }
}
