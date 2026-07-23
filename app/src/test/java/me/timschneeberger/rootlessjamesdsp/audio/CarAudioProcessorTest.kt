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
            spatializer = CarSpatializerSettings(enabled = true, mode = 3, strength = 80f, envelopment = 0f),
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
        }
        assertTrue(rms(output, 0) < rms(stereoInput, 0) * 1.15f)
    }

    @Test
    fun envelopmentCreatesAQuietMonoBedForFrontSeats() {
        for (cabinSize in 0..2) {
            val processor = CarAudioProcessor(48_000)
            processor.update(CarAudioSettings(
                spatializer = CarSpatializerSettings(
                    enabled = true,
                    mode = 3,
                    strength = 0f,
                    frontFocus = 0f,
                    envelopment = 100f,
                    cabinSize = cabinSize,
                ),
            ))
            val warmup = FloatArray(96_000)
            processor.process(warmup, FloatArray(warmup.size))

            val input = FloatArray(96_000)
            for (frame in 0 until 48_000) {
                val sample = (
                    0.08 * kotlin.math.sin(2.0 * Math.PI * 380.0 * frame / 48_000.0) +
                        0.05 * kotlin.math.sin(2.0 * Math.PI * 910.0 * frame / 48_000.0) +
                        0.04 * kotlin.math.sin(2.0 * Math.PI * 1_730.0 * frame / 48_000.0) +
                        0.03 * kotlin.math.sin(2.0 * Math.PI * 3_100.0 * frame / 48_000.0)
                    ).toFloat()
                input[2 * frame] = sample
                input[2 * frame + 1] = sample
            }
            val output = FloatArray(input.size)
            processor.process(input, output)
            val inputRms = stereoRms(input, 4_800, 48_000)
            val directRms = stereoRms(output, 4_800, 48_000)
            val differenceRms = stereoDifferenceRms(output, 4_800, 48_000)
            assertTrue("cabin=$cabinSize difference=$differenceRms", differenceRms > directRms * 0.005f)
            assertTrue("cabin=$cabinSize difference=$differenceRms", differenceRms < directRms * 0.25f)
            val gainRatio = directRms / inputRms
            assertTrue("cabin=$cabinSize gainRatio=$gainRatio", gainRatio in 0.85f..1.20f)
            assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    @Test
    fun spatializerKeepsSubBassOutOfCabinAmbience() {
        for (cabinSize in 0..2) {
            val processor = CarAudioProcessor(48_000)
            processor.update(CarAudioSettings(
                spatializer = CarSpatializerSettings(
                    enabled = true,
                    mode = 3,
                    strength = 0f,
                    frontFocus = 0f,
                    envelopment = 100f,
                    cabinSize = cabinSize,
                ),
            ))
            val input = FloatArray(96_000)
            for (frame in 0 until 48_000) {
                val sample = (0.12 * kotlin.math.sin(2.0 * Math.PI * 55.0 * frame / 48_000.0)).toFloat()
                input[2 * frame] = sample
                input[2 * frame + 1] = sample
            }
            val output = FloatArray(input.size)
            processor.process(input, output)

            val inputRms = stereoRms(input, 12_000, 48_000)
            val outputRms = stereoRms(output, 12_000, 48_000)
            val differenceRms = stereoDifferenceRms(output, 12_000, 48_000)
            val gainRatio = outputRms / inputRms
            assertTrue("cabin=$cabinSize gainRatio=$gainRatio", gainRatio in 0.98f..1.02f)
            assertTrue(
                "cabin=$cabinSize difference=$differenceRms",
                differenceRms < inputRms * 0.015f,
            )
        }
    }

    @Test
    fun cabinProfileChangeStaysFiniteAndClickFree() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 0f,
                frontFocus = 0f,
                envelopment = 100f,
                cabinSize = 0,
            ),
        ))
        val firstInput = FloatArray(9_600) { i ->
            (0.12 * kotlin.math.sin(2.0 * Math.PI * 1_000.0 * (i / 2) / 48_000.0)).toFloat()
        }
        val firstOutput = FloatArray(firstInput.size)
        processor.process(firstInput, firstOutput)

        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 0f,
                frontFocus = 0f,
                envelopment = 100f,
                cabinSize = 2,
            ),
        ))
        val secondInput = FloatArray(firstInput.size) { i ->
            (0.12 * kotlin.math.sin(2.0 * Math.PI * 1_000.0 * ((i / 2) + 4_800) / 48_000.0)).toFloat()
        }
        val secondOutput = FloatArray(secondInput.size)
        processor.process(secondInput, secondOutput)

        var maxStep = abs(secondOutput[0] - firstOutput.last())
        for (i in 1 until secondOutput.size) {
            maxStep = maxOf(maxStep, abs(secondOutput[i] - secondOutput[i - 1]))
        }
        assertTrue("maxStep=$maxStep", maxStep < 0.25f)
        assertTrue(secondOutput.all { it.isFinite() && abs(it) <= 1f })
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
    fun spatializerKeepsCenterIndependentOfSideAtMaximumSettings() {
        val spatialSettings = CarSpatializerSettings(
            enabled = true,
            mode = 3,
            strength = 100f,
            frontFocus = 100f,
            envelopment = 100f,
        )
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(spatializer = spatialSettings))
        val mirroredProcessor = CarAudioProcessor(48_000)
        mirroredProcessor.update(CarAudioSettings(spatializer = spatialSettings))
        val input = FloatArray(9_600) { i ->
            if (i % 2 == 0) {
                (0.05 * kotlin.math.sin(2.0 * Math.PI * 700.0 * (i / 2) / 48_000.0)).toFloat()
            } else {
                (0.05 * kotlin.math.sin(2.0 * Math.PI * 1_300.0 * (i / 2) / 48_000.0)).toFloat()
            }
        }
        val mirroredInput = FloatArray(input.size)
        for (i in input.indices step 2) {
            mirroredInput[i] = input[i + 1]
            mirroredInput[i + 1] = input[i]
        }
        val output = FloatArray(input.size)
        val mirroredOutput = FloatArray(input.size)
        processor.process(input, output)
        mirroredProcessor.process(mirroredInput, mirroredOutput)
        for (i in input.indices step 2) {
            val outputCenter = (output[i] + output[i + 1]) * 0.5f
            val mirroredCenter = (mirroredOutput[i] + mirroredOutput[i + 1]) * 0.5f
            assertEquals(outputCenter, mirroredCenter, 0.000001f)
        }
    }

    @Test
    fun spatializerAddsTransientPunchWithoutSustainedBoom() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 100f,
                frontFocus = 100f,
                envelopment = 0f,
            ),
        ))
        val input = FloatArray(96_000)
        for (frame in 4_800 until 48_000) {
            val sample = (0.12 * kotlin.math.sin(2.0 * Math.PI * 140.0 * frame / 48_000.0)).toFloat()
            input[2 * frame] = sample
            input[2 * frame + 1] = sample
        }
        val output = FloatArray(input.size)
        processor.process(input, output)

        val attackRatio = stereoRms(output, 4_800, 5_760) / stereoRms(input, 4_800, 5_760)
        val sustainRatio = stereoRms(output, 38_400, 48_000) / stereoRms(input, 38_400, 48_000)
        assertTrue("attack=$attackRatio sustain=$sustainRatio", attackRatio > sustainRatio + 0.05f)
        assertTrue("sustainRatio=$sustainRatio", sustainRatio in 1.00f..1.09f)
        assertTrue("attackRatio=$attackRatio", attackRatio < 1.4f)
    }

    @Test
    fun spatializerLowFrequencyBurstStopsCleanly() {
        val processor = CarAudioProcessor(48_000)
        processor.update(CarAudioSettings(
            spatializer = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 100f,
                frontFocus = 100f,
                envelopment = 100f,
                cabinSize = 2,
            ),
        ))
        val input = FloatArray(96_000)
        for (frame in 4_800 until 9_600) {
            val sample = (0.12 * kotlin.math.sin(2.0 * Math.PI * 110.0 * frame / 48_000.0)).toFloat()
            input[2 * frame] = sample
            input[2 * frame + 1] = sample
        }
        val output = FloatArray(input.size)
        processor.process(input, output)

        val burstRms = stereoRms(output, 7_200, 9_600)
        val tailRms = stereoRms(output, 10_320, 12_000)
        assertTrue("burst=$burstRms tail=$tailRms", tailRms < burstRms * 0.03f)
        assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun envelopmentDoesNotCreateADominantDelayedEcho() {
        for (cabinSize in 0..2) {
            val spatialSettings = CarSpatializerSettings(
                enabled = true,
                mode = 3,
                strength = 0f,
                frontFocus = 0f,
                envelopment = 100f,
                cabinSize = cabinSize,
            )
            val processor = CarAudioProcessor(48_000)
            processor.update(CarAudioSettings(spatializer = spatialSettings))
            val warmup = FloatArray(96_000)
            processor.process(warmup, FloatArray(warmup.size))

            val input = FloatArray(9_600)
            input[0] = 0.2f
            input[1] = 0.2f
            val output = FloatArray(input.size)
            processor.process(input, output)

            var latePeak = 0f
            for (frame in 240 until 2_400) {
                latePeak = maxOf(latePeak, abs(output[2 * frame]), abs(output[2 * frame + 1]))
            }
            assertTrue("cabin=$cabinSize latePeak=$latePeak", latePeak < 0.05f)
        }
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

    private fun stereoRms(values: FloatArray, startFrame: Int, endFrame: Int): Float {
        var sum = 0.0
        for (frame in startFrame until endFrame) {
            val left = values[2 * frame].toDouble()
            val right = values[2 * frame + 1].toDouble()
            sum += left * left + right * right
        }
        return sqrt(sum / ((endFrame - startFrame) * 2)).toFloat()
    }

    private fun stereoDifferenceRms(values: FloatArray, startFrame: Int, endFrame: Int): Float {
        var sum = 0.0
        for (frame in startFrame until endFrame) {
            val difference = values[2 * frame].toDouble() - values[2 * frame + 1].toDouble()
            sum += difference * difference
        }
        return sqrt(sum / (endFrame - startFrame)).toFloat()
    }
}
