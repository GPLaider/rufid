package io.github.rufid.core

import io.github.rufid.archive.ArchiveKind
import io.github.rufid.archive.ArchivePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifierPolicyTest {
    @Test
    fun rarIsNotClassifiedAsSupportedArchive() {
        assertEquals(ImageKind.Unknown, ImageClassifier.classify("backup.rar"))
        assertEquals(ArchiveKind.Unknown, ArchivePlan.classify("backup.rar"))
    }

    @Test
    fun supportedArchivesRemainClassified() {
        assertEquals(ImageKind.Archive, ImageClassifier.classify("image.7z"))
        assertEquals(ArchiveKind.SevenZip, ArchivePlan.classify("image.7z"))
        assertEquals(ArchiveKind.Wim, ArchivePlan.classify("install.wim"))
    }
}
