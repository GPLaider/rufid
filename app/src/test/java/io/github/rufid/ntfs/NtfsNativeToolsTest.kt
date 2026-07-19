package io.github.rufid.ntfs

import io.github.rufid.core.OperationCancelledException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NtfsNativeToolsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pollingCancellationPreservesOperationCancelledException() {
        val command = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            listOf(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 30",
            )
        } else {
            listOf("sh", "-c", "while :; do :; done")
        }
        var checks = 0

        assertThrows(OperationCancelledException::class.java) {
            RealNtfsProcessLauncher(hostCacheDir = temp.root).run(
                command = command,
                onCancelCheck = {
                    checks++
                    throw OperationCancelledException()
                },
            )
        }

        assertTrue("launcher must poll cancellation while the process is alive", checks > 0)
    }
}
