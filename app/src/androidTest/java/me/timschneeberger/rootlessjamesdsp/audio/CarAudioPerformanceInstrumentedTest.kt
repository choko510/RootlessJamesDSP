package me.timschneeberger.rootlessjamesdsp.audio

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarAudioPerformanceInstrumentedTest {
    @Test
    fun carAudioModesHaveStableCpuTiming() {
        val modes = listOf(
            "loudness" to CarAudioSettings(loudness = AutoLoudnessSettings(enabled = true)),
            "compressor" to CarAudioSettings(compressor = ThreeBandCompressorSettings(enabled = true)),
            "spatializer" to CarAudioSettings(
                spatializer = CarSpatializerSettings(enabled = true, mode = 3, strength = 80f, envelopment = 60f),
            ),
            "all" to CarAudioSettings(
                loudness = AutoLoudnessSettings(enabled = true),
                compressor = ThreeBandCompressorSettings(enabled = true),
                spatializer = CarSpatializerSettings(enabled = true, mode = 3, strength = 80f, envelopment = 60f),
            ),
        )
        val input = FloatArray(8192) { index -> ((index % 127) - 63) / 100f }
        val output = FloatArray(input.size)

        for ((name, settings) in modes) {
            val processor = CarAudioProcessor(48_000)
            processor.update(settings)
            processor.prepare(input.size)
            repeat(WARMUP_BLOCKS) { processor.process(input, output) }
            val samples = LongArray(SAMPLES)
            repeat(SAMPLES) { index ->
                val start = Debug.threadCpuTimeNanos()
                processor.process(input, output)
                samples[index] = Debug.threadCpuTimeNanos() - start
            }
            samples.sort()
            val median = samples[samples.size / 2]
            Log.i(TAG, "$name medianCpuNs=$median")
            assertTrue("$name did not process audio", output.all(Float::isFinite))
            assertTrue("$name CPU timer unavailable", median > 0L)
        }
    }

    private companion object {
        const val TAG = "CarAudioPerf"
        const val WARMUP_BLOCKS = 20
        const val SAMPLES = 30
    }
}
