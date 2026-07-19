package io.github.rufid.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BulkOnlyValidationTest {
    @Test
    fun cswResidueParsesAsUnsigned32Bit() {
        // Production uses ByteBuffer.int bits then parseCswResidueUnsigned (not a separate byte parser).
        val highBit = BulkOnlyValidation.parseCswResidueUnsigned(0x8000_0000.toInt())
        assertEquals(0x8000_0000L, highBit)

        val max = BulkOnlyValidation.parseCswResidueUnsigned(-1)
        assertEquals(0xffff_ffffL, max)

        val zero = BulkOnlyValidation.parseCswResidueUnsigned(0)
        assertEquals(0L, zero)

        val small = BulkOnlyValidation.parseCswResidueUnsigned(512)
        assertEquals(512L, small)
    }

    @Test
    fun zeroLengthControlOnlyNegativeFails() {
        assertTrue(BulkOnlyValidation.isZeroLengthControlTransferOk(0))
        assertTrue(BulkOnlyValidation.isZeroLengthControlTransferOk(1))
        assertFalse(BulkOnlyValidation.isZeroLengthControlTransferOk(-1))
        assertFalse(BulkOnlyValidation.isZeroLengthControlTransferOk(-32))
    }

    @Test
    fun zeroLengthControlFailureMessageNamesStepAndIds() {
        val msg = BulkOnlyValidation.zeroLengthControlFailureMessage(
            step = "MASS_STORAGE_RESET",
            result = -1,
            interfaceId = 0,
        )
        assertTrue(msg.contains("step=MASS_STORAGE_RESET"))
        assertTrue(msg.contains("result=-1"))
        assertTrue(msg.contains("interfaceId=0"))

        val halt = BulkOnlyValidation.zeroLengthControlFailureMessage(
            step = "CLEAR_FEATURE(ENDPOINT_HALT)",
            result = -1,
            endpointAddress = 0x81,
        )
        assertTrue(halt.contains("endpointAddress=0x81"))
    }

    @Test
    fun requireZeroLengthControlOkThrowsOnNegative() {
        try {
            requireZeroLengthControlOk(-1, step = "MASS_STORAGE_RESET", interfaceId = 2)
            fail("expected IOException")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("MASS_STORAGE_RESET"))
            assertTrue(error.message!!.contains("interfaceId=2"))
        }
    }

    @Test
    fun passedStatusWithResidueIsNotSuccessMessage() {
        val msg = BulkOnlyValidation.cswPassedWithUnexpectedResidueMessage(
            commandName = "WRITE(10)",
            status = 0,
            residue = 512L,
            dataLength = 4096,
        )
        assertTrue(msg.contains("WRITE(10)"))
        assertTrue(msg.contains("residue=512"))
        assertTrue(msg.contains("dataLength=4096"))
        assertTrue(msg.contains("status=0"))
    }

    @Test
    fun setInterfacePolicyHardOnlyForNonZeroAlt() {
        assertFalse(BulkOnlyValidation.setInterfaceFailureIsHard(0))
        assertTrue(BulkOnlyValidation.setInterfaceFailureIsHard(1))
    }

    @Test
    fun scsiCommandExceptionResidueIsLongAndInMessage() {
        val ex = ScsiCommandException(
            commandName = "WRITE(10)",
            status = 1,
            residue = 0x8000_0000L,
            sense = null,
        )
        assertEquals(0x8000_0000L, ex.residue)
        assertTrue(ex.message!!.contains("residue=${0x8000_0000L}"))
    }
}
