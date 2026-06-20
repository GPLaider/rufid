package io.github.rufid.core

interface SeekableBlockDevice {
    val blockSize: Int
    val sizeBytes: Long

    fun seek(byteOffset: Long)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun flush()
    fun close()
}

