package me.timschneeberger.rootlessjamesdsp.service

import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding

internal sealed interface PcmProcessingBuffers

internal class FloatPcmProcessingBuffers(bufferSize: Int) : PcmProcessingBuffers {
    val samples = FloatArray(bufferSize)
}

internal class ShortPcmProcessingBuffers(bufferSize: Int) : PcmProcessingBuffers {
    val samples = ShortArray(bufferSize)
}

internal fun createPcmProcessingBuffers(
    encoding: AudioEncoding,
    bufferSize: Int,
): PcmProcessingBuffers {
    require(bufferSize > 0) { "PCM buffer size must be positive" }
    return when (encoding) {
        AudioEncoding.PcmFloat -> FloatPcmProcessingBuffers(bufferSize)
        AudioEncoding.PcmShort -> ShortPcmProcessingBuffers(bufferSize)
    }
}
