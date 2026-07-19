package io.github.rufid.core

object IsoImageReader {
    fun listFiles(source: SeekableByteSource): List<ExtractableIsoFile> {
        UdfReader(source).listFiles()?.let { return it }
        val reader = Iso9660Reader(source)
        return reader.listFiles().map { file ->
            ExtractableIsoFile(
                path = file.path,
                size = file.size,
                reader = { fileOffset, buffer, outputOffset, length ->
                    reader.readFile(file, buffer, fileOffset, outputOffset, length)
                },
            )
        }
    }

    fun plan(source: SeekableByteSource, imageName: String): IsoExtractionPlan =
        IsoExtractionPlanner.plan(imageName, listFiles(source).map { IsoFileEntry(it.path, it.size) })
}
