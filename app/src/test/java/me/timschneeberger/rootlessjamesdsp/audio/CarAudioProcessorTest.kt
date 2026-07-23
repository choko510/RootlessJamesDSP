package me.timschneeberger.rootlessjamesdsp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CarAudioProcessorTest {
    @Test
    fun loudnessCurveHasQuietMidAndLoudPoints() {
        assertEquals(1f, CarAudioProcessor.loudnessStrength(-30f, -30f, -18f, -6f, false), 0.001f)
        assertEquals(0.5f, CarAudioProcessor.loudnessStrength(-18f, -30f, -18f, -6f, false), 0.001f)
        assertEquals(0f, CarAudioProcessor.loudnessStrength(-6f, -30f, -18f, -6f, false), 0.001f)
        assertEquals(1f / 3f, CarAudioProcessor.loudnessStrength(-18f, -30f, -18f, -6f, true), 0.001f)
    }

    @Test
    fun compressorPresetsMatchCarPlan() {
        val gentle = CarAudioProcessor.presetSettings(0)
        assertEquals(-12f, gentle.low.thresholdDb, 0.001f)
        assertEquals(35f, gentle.low.attackMs, 0.001f)
        assertEquals(0.5f, gentle.mid.makeupDb, 0.001f)

        val normal = CarAudioProcessor.presetSettings(1)
        assertEquals(-16f, normal.mid.thresholdDb, 0.001f)
        assertEquals(30f, normal.low.attackMs, 0.001f)
        assertEquals(0f, normal.high.makeupDb, 0.001f)

        val strong = CarAudioProcessor.presetSettings(2)
        assertEquals(4f, strong.low.ratio, 0.001f)
        assertEquals(3f, strong.high.attackMs, 0.001f)
        assertEquals(150f, strong.mid.releaseMs, 0.001f)
    }

    @Test
    fun loudnessPresetsScaleCorrectionStrength() {
        val weak = CarAudioProcessor.loudnessPresetSettings(0)
        val medium = CarAudioProcessor.loudnessPresetSettings(1)
        val strong = CarAudioProcessor.loudnessPresetSettings(2)

        assertEquals(3f, weak.bassMaxDb, 0.001f)
        assertEquals(1.5f, weak.trebleMaxDb, 0.001f)
        assertEquals(6f, medium.bassMaxDb, 0.001f)
        assertEquals(3f, medium.trebleMaxDb, 0.001f)
        assertEquals(9f, strong.bassMaxDb, 0.001f)
        assertEquals(6f, strong.trebleMaxDb, 0.001f)
        assertEquals(medium.lowVolumeDb, strong.lowVolumeDb, 0.001f)
        assertEquals(medium.midVolumeDb, strong.midVolumeDb, 0.001f)
        assertEquals(medium.highVolumeDb, strong.highVolumeDb, 0.001f)
    }

    @Test
    fun disabledProcessorIsBitTransparent() {
        val input = FloatArray(256) { ((it * 37) % 100 - 50) / 100f }
        val output = FloatArray(input.size)
        CarAudioProcessor(48_000).process(input, output)
        assertTrue(input.contentEquals(output))
    }

    @Test
    fun quietLoudnessRaisesAConstantQuietSignal() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            loudness = AutoLoudnessSettings(enabled = true),
        ))
        processor.setEffectiveOutputGainDb(-30f)
        val input = FloatArray(48_000) { 0.05f }
        val output = FloatArray(input.size)
        processor.process(input, output)
        assertTrue("last=${output.last()} expected>${input.last() * 1.3f}", output.last() > input.last() * 1.3f)
        assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun compressorReducesSustainedLowBandEnergy() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            compressor = ThreeBandCompressorSettings(
                enabled = true,
                low = CompressorBandSettings(-30f, 8f, attackMs = 1f, releaseMs = 100f),
                mid = CompressorBandSettings(0f, 1f, attackMs = 1f, releaseMs = 100f),
                high = CompressorBandSettings(0f, 1f, attackMs = 1f, releaseMs = 100f),
            ),
        ))
        val input = FloatArray(96_000) { i -> (0.8 * kotlin.math.sin(2.0 * Math.PI * 60.0 * i / 48_000.0)).toFloat() }
        val output = FloatArray(input.size)
        processor.process(input, output)
        val inRms = rms(input, 48_000)
        val outRms = rms(output, 48_000)
        assertTrue("expected low-band gain reduction in=$inRms out=$outRms gr=${processor.getGainReductionDb()}", outRms < inRms * 0.9f)
        assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun spatializerKeepsMonoCentered() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(enabled = true, mode = 3, strength = 80f, envelopment = 70f),
        ))
        val input = FloatArray(4_800) { i ->
            (0.2 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 48_000.0)).toFloat()
        }
        val stereoInput = FloatArray(9_600)
        for (i in input.indices) {
            stereoInput[2 * i] = input[i]
            stereoInput[2 * i + 1] = input[i]
        }
        val output = FloatArray(stereoInput.size)
        processor.process(stereoInput, output)
        for (i in output.indices step 2) {
            assertEquals(output[i], output[i + 1], 0.0001f)
            assertEquals(input[i / 2], output[i], 0.0001f)
        }
    }

    @Test
    fun zeroStrengthAndEnvelopmentAreTransparentInEveryMode() {
        val input = FloatArray(9_600) { i ->
            if (i % 2 == 0) {
                (0.1 * kotlin.math.sin(2.0 * Math.PI * 700.0 * (i / 2) / 48_000.0)).toFloat()
            } else {
                (0.08 * kotlin.math.sin(2.0 * Math.PI * 1_300.0 * (i / 2) / 48_000.0)).toFloat()
            }
        }
        for (mode in 1..3) {
            val processor = CarAudioProcessor(48_000)
            processor.update(CarAudioSettings(
                spatializer = CarSpatializerSettings(
                    enabled = true,
                    mode = mode,
                    strength = 0f,
                    frontFocus = 0f,
                    envelopment = 0f,
                ),
            ))
            val output = FloatArray(input.size)
            processor.process(input, output)
            for (i in input.indices) assertEquals(input[i], output[i], 0.000001f)
        }
    }

    @Test
    fun immersiveStrengthProducesAudibleButBoundedWidth() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 100f,
                frontFocus = 0f,
                envelopment = 0f,
            ),
        ))
        val input = FloatArray(48_000) { i ->
            val sample = (0.12 * kotlin.math.sin(2.0 * Math.PI * 1_000.0 * (i / 2) / 48_000.0)).toFloat()
            if (i % 2 == 0) sample else -sample
        }
        val output = FloatArray(input.size)
        processor.process(input, output)
        val widthRatio = rms(output, output.size / 2) / rms(input, input.size / 2)
        assertTrue("widthRatio=$widthRatio", widthRatio > 1.4f)
        assertTrue("widthRatio=$widthRatio", widthRatio < 1.7f)
    }

    @Test
    fun spatializerPreservesTheCenterSignalAtMaximumSettings() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 100f,
                frontFocus = 100f,
                envelopment = 100f,
            ),
        ))
        val input = FloatArray(9_600) { i ->
            if (i % 2 == 0) {
                (0.05 * kotlin.math.sin(2.0 * Math.PI * 700.0 * (i / 2) / 48_000.0)).toFloat()
            } else {
                (0.05 * kotlin.math.sin(2.0 * Math.PI * 1_300.0 * (i / 2) / 48_000.0)).toFloat()
            }
        }
        val output = FloatArray(input.size)
        processor.process(input, output)
        for (i in input.indices step 2) {
            val inputCenter = (input[i] + input[i + 1]) * 0.5f
            val outputCenter = (output[i] + output[i + 1]) * 0.5f
            assertEquals(inputCenter, outputCenter, 0.000001f)
        }
    }

    @Test
    fun envelopmentDiffusesEarlyWithoutCreatingAHaasEcho() {
        val spatialSettings = CarSpatializerSettings(
            enabled = true,
            mode = 3,
            strength = 0f,
            frontFocus = 0f,
            envelopment = 100f,
        )
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(spatializer = spatialSettings))
        val dryProcessor = CarAudioProcessor(48_000)
        dryProcessor.update(CarAudioSettings(
            spatializer = spatialSettings.copy(envelopment = 0f),
        ))
        val input = FloatArray(3_000)
        input[0] = 0.2f
        val output = FloatArray(input.size)
        val dryOutput = FloatArray(input.size)
        processor.process(input, output)
        dryProcessor.process(input, dryOutput)

        var earlyDifference = 0f
        for (i in 0 until 128) {
            earlyDifference = maxOf(earlyDifference, abs(output[i] - dryOutput[i]))
        }
        assertTrue("earlyDifference=$earlyDifference", earlyDifference > 0.005f)

        var latePeak = 0f
        for (frame in 192 until 1_200) {
            latePeak = maxOf(latePeak, abs(output[2 * frame]), abs(output[2 * frame + 1]))
        }
        assertTrue("latePeak=$latePeak", latePeak < 0.001f)
    }

    @Test
    fun spatializerHandlesAudioBufferBoundaries() {
        for (bufferSize in listOf(1, 2, 255, 256, 511, 512, 1023)) {
            val processor = CarAudioProcessor(48_000)
            processor.update(CarAudioSettings(
                spatializer = CarSpatializerSettings(
                    enabled = true,
                    mode = 3,
                    strength = 100f,
                    frontFocus = 0f,
                    envelopment = 100f,
                ),
            ))
            processor.prepare(bufferSize)
            val input = FloatArray(bufferSize) { if (it % 2 == 0) 0.2f else -0.2f }
            val output = FloatArray(bufferSize)
            processor.process(input, output)
            assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    private fun rms(values: FloatArray, start: Int): Float {
        var sum = 0.0
        for (i in start until values.size) sum += values[i].toDouble() * values[i].toDouble()
        return sqrt(sum / (values.size - start)).toFloat()
    }
}
