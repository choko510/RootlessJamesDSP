package me.timschneeberger.rootlessjamesdsp.service

import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmProcessingBuffersTest {
    @Test
    fun floatEncodingAllocatesOnlyFloatBuffers() {
        val buffers = createPcmProcessingBuffers(AudioEncoding.PcmFloat, 8_192)

        assertTrue(buffers is FloatPcmProcessingBuffers)
        buffers as FloatPcmProcessingBuffers
        assertEquals(8_192, buffers.samples.size)
    }

    @Test
    fun shortEncodingAllocatesOnlyShortBuffers() {
        val buffers = createPcmProcessingBuffers(AudioEncoding.PcmShort, 8_192)

        assertTrue(buffers is ShortPcmProcessingBuffers)
        buffers as ShortPcmProcessingBuffers
        assertEquals(8_192, buffers.samples.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyBuffers() {
        createPcmProcessingBuffers(AudioEncoding.PcmFloat, 0)
    }
}
