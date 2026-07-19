package io.github.rufid.core

import org.junit.Assert.assertThrows
import org.junit.Test

class WritePlanTest {
    @Test
    fun extractionAllowsIsoContainerLargerThanTarget() {
        WritePlan("windows.iso", 10_000, "USB", 9_000).validateForExtraction()
    }

    @Test
    fun rawWriteStillRejectsImageLargerThanTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            WritePlan("disk.img", 10_000, "USB", 9_000).validate()
        }
    }
}
