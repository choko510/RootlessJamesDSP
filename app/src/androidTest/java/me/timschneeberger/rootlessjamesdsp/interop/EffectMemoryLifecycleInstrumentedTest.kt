package me.timschneeberger.rootlessjamesdsp.interop

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffectMemoryLifecycleInstrumentedTest {
    @Test
    fun largeEffectResourcesAreAllocatedOnDemandAndReleased() {
        val handle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(handle != 0L)

        try {
            val baselineBytes = Debug.getNativeHeapAllocatedSize()
            val input = FloatArray(512) { if (it % 2 == 0) 0.25f else -0.25f }
            val output = FloatArray(input.size)

            JamesDspWrapper.setSamplingRate(handle, 44_100f, true)
            JamesDspWrapper.setSamplingRate(handle, 48_000f, true)
            assertReleased("Sample-rate refresh", baselineBytes)

            assertTrue(JamesDspWrapper.setReverb(handle, true, 15))
            assertAllocated("Reverb", baselineBytes, MINIMUM_REVERB_ALLOCATION_BYTES)
            JamesDspWrapper.processFloat(handle, input, output)
            assertTrue(output.all(Float::isFinite))
            assertTrue(JamesDspWrapper.setReverb(handle, false, 15))
            assertReleased("Reverb", baselineBytes)

            val companderBands = doubleArrayOf(
                95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            )
            assertTrue(JamesDspWrapper.setCompander(handle, true, 0.22f, 0, 0, companderBands))
            assertAllocated("Compander", baselineBytes, MINIMUM_COMPANDER_ALLOCATION_BYTES)
            JamesDspWrapper.processFloat(handle, input, output)
            assertTrue(output.all(Float::isFinite))
            assertTrue(JamesDspWrapper.setCompander(handle, false, 0.22f, 0, 0, companderBands))
            assertReleased("Compander", baselineBytes)

            assertTrue(JamesDspWrapper.setCrossfeed(handle, true, 0, 0, 0))
            val simpleCrossfeedBytes = Debug.getNativeHeapAllocatedSize()
            assertTrue(
                "Simple crossfeed allocated ${simpleCrossfeedBytes - baselineBytes} bytes",
                simpleCrossfeedBytes - baselineBytes <= SIMPLE_CROSSFEED_TOLERANCE_BYTES,
            )
            JamesDspWrapper.processFloat(handle, input, output)
            assertTrue(JamesDspWrapper.setCrossfeed(handle, false, 0, 0, 0))
            assertReleased("Simple crossfeed", baselineBytes)

            assertTrue(JamesDspWrapper.setCrossfeed(handle, true, 2, 0, 0))
            assertAllocated("HRTF crossfeed", baselineBytes, MINIMUM_HRTF_ALLOCATION_BYTES)
            JamesDspWrapper.processFloat(handle, input, output)
            assertTrue(output.all(Float::isFinite))
            assertTrue(JamesDspWrapper.setCrossfeed(handle, false, 2, 0, 0))
            assertReleased("HRTF crossfeed", baselineBytes)

            assertTrue(JamesDspWrapper.setCrossfeed(handle, true, 5, 0, 0))
            assertAllocated("Long HRTF crossfeed", baselineBytes, MINIMUM_HRTF_ALLOCATION_BYTES)
            JamesDspWrapper.processFloat(handle, input, output)
            assertTrue(output.all(Float::isFinite))
            assertTrue(JamesDspWrapper.setCrossfeed(handle, false, 5, 0, 0))
            assertReleased("Long HRTF crossfeed", baselineBytes)

            val impulse = FloatArray(32_768)
            impulse[0] = 1f
            assertTrue(JamesDspWrapper.setConvolver(handle, true, impulse, 1, impulse.size))
            assertAllocated("Convolver", baselineBytes, MINIMUM_CONVOLVER_ALLOCATION_BYTES)
            JamesDspWrapper.processFloat(handle, input, output)
            assertTrue(output.all(Float::isFinite))
            assertTrue(JamesDspWrapper.setConvolver(handle, false, FloatArray(0), 0, 0))
            assertReleased("Convolver", baselineBytes)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    private fun assertAllocated(name: String, baselineBytes: Long, minimumBytes: Long) {
        val allocatedBytes = Debug.getNativeHeapAllocatedSize() - baselineBytes
        assertTrue("$name allocated only $allocatedBytes bytes", allocatedBytes >= minimumBytes)
    }

    private fun assertReleased(name: String, baselineBytes: Long) {
        val retainedBytes = Debug.getNativeHeapAllocatedSize() - baselineBytes
        assertTrue(
            "$name retained $retainedBytes bytes after disable",
            retainedBytes <= RELEASE_TOLERANCE_BYTES,
        )
    }

    private object TestCallbacks : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) = Unit
        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }

    private companion object {
        const val MINIMUM_REVERB_ALLOCATION_BYTES = 512L * 1024L
        const val MINIMUM_COMPANDER_ALLOCATION_BYTES = 1024L * 1024L
        const val MINIMUM_HRTF_ALLOCATION_BYTES = 256L * 1024L
        const val MINIMUM_CONVOLVER_ALLOCATION_BYTES = 128L * 1024L
        const val SIMPLE_CROSSFEED_TOLERANCE_BYTES = 128L * 1024L
        const val RELEASE_TOLERANCE_BYTES = 2L * 1024L * 1024L
    }
}
