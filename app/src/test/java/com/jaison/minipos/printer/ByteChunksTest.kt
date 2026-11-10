package com.jaison.minipos.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteChunksTest {
    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun splitsIntoFullChunksPlusARemainder() {
        val chunks = bytes(1124).inChunksOf(512)
        assertEquals(listOf(512, 512, 100), chunks.map { it.size })
    }

    @Test
    fun exactMultipleHasNoEmptyTrailingChunk() {
        val chunks = bytes(1024).inChunksOf(512)
        assertEquals(listOf(512, 512), chunks.map { it.size })
    }

    @Test
    fun smallerThanOneChunkIsASingleChunk() {
        assertEquals(listOf(5), bytes(5).inChunksOf(512).map { it.size })
    }

    @Test
    fun emptyInputGivesNoChunks() {
        assertTrue(ByteArray(0).inChunksOf(512).isEmpty())
    }

    @Test
    fun puttingTheChunksBackTogetherGivesTheOriginalBytes() {
        val original = bytes(2000)
        val rejoined = original.inChunksOf(512).fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        assertArrayEquals(original, rejoined)
    }

    @Test(expected = IllegalArgumentException::class)
    fun chunkSizeMustBePositive() {
        bytes(10).inChunksOf(0)
    }
}
