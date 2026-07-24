package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InPlaceProcessingInstrumentedTest {
    @Test
    fun floatInPlaceMatchesSeparateArrayForNativeEffects() {
        assertMatching { handle ->
            val bands = DoubleArray(30) { index -> if (index % 3 == 0) 1.0 else 0.0 }
            assertTrue(JamesDspWrapper.setMultiEqualizer(handle, true, 0, 0, bands))
        }
        assertMatching { handle ->
            val bands = doubleArrayOf(
                95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            )
            assertTrue(JamesDspWrapper.setCompander(handle, true, 0.22f, 0, 0, bands))
        }
        assertMatching { handle ->
            assertTrue(JamesDspWrapper.setCrossfeed(handle, true, 0, 0, 0))
        }
        assertMatching { handle ->
            val impulse = FloatArray(64)
            impulse[0] = 1f
            assertTrue(JamesDspWrapper.setConvolver(handle, true, impulse, 1, impulse.size))
        }
    }

    @Test
    fun shortInPlaceMatchesSeparateArrayForNativeEffects() {
        assertShortMatching { handle ->
            val bands = doubleArrayOf(
                95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            )
            assertTrue(JamesDspWrapper.setCompander(handle, true, 0.22f, 0, 0, bands))
        }
        assertShortMatching { handle ->
            assertTrue(JamesDspWrapper.setCrossfeed(handle, true, 0, 0, 0))
        }
        assertShortMatching { handle ->
            val impulse = FloatArray(64)
            impulse[0] = 1f
            assertTrue(JamesDspWrapper.setConvolver(handle, true, impulse, 1, impulse.size))
        }
    }

    @Test
    fun disabledFloatAndShortPathsAreBitTransparent() {
        val handle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(handle != 0L)
        try {
            val floatInput = FloatArray(1024) { index -> ((index % 37) - 18) / 24f }
            val floatExpected = floatInput.copyOf()
            JamesDspWrapper.processFloat(handle, floatInput, floatExpected)
            val floatActual = floatInput.copyOf()
            JamesDspWrapper.processFloat(handle, floatActual, floatActual)
            assertArrayEquals(floatExpected, floatActual, 1.0e-5f)

            val shortInput = ShortArray(1024) { index -> ((index % 127) * 211 - 12_000).toShort() }
            val shortExpected = shortInput.copyOf()
            JamesDspWrapper.processInt16(handle, shortInput, shortExpected)
            val shortActual = shortInput.copyOf()
            JamesDspWrapper.processInt16(handle, shortActual, shortActual)
            assertArrayEquals(shortExpected, shortActual)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    @Test
    fun reverbInPlaceProducesFinitePcm() {
        val handle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(handle != 0L)
        try {
            assertTrue(JamesDspWrapper.setReverb(handle, true, 15))
            val samples = FloatArray(2048) { index -> ((index % 37) - 18) / 24f }
            JamesDspWrapper.processFloat(handle, samples, samples)
            assertTrue(samples.all(Float::isFinite))
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    @Test
    fun liveProgInPlaceMatchesSeparateArray() {
        val handle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(handle != 0L)
        try {
            val script = "@init\ngain = 0.5;\n@sample\nspl0 = spl0 * gain;\nspl1 = spl1 * gain;\n"
            assertTrue(JamesDspWrapper.setLiveprog(handle, true, "in-place", script))
            val input = FloatArray(1024) { index -> ((index % 31) - 15) / 20f }
            val expected = FloatArray(input.size)
            JamesDspWrapper.processFloat(handle, input, expected)

            // Recompiling the stateless script resets the code path while retaining one VM.
            assertTrue(JamesDspWrapper.setLiveprog(handle, true, "in-place", script))
            val actual = input.copyOf()
            JamesDspWrapper.processFloat(handle, actual, actual)
            assertArrayEquals(expected, actual, 1.0e-5f)
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    private fun assertMatching(configure: (Long) -> Unit) {
        val separateHandle = JamesDspWrapper.alloc(TestCallbacks)
        val inPlaceHandle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(separateHandle != 0L && inPlaceHandle != 0L)
        try {
            configure(separateHandle)
            configure(inPlaceHandle)
            val input = FloatArray(2048) { index -> ((index % 37) - 18) / 24f }
            val expected = FloatArray(input.size)
            JamesDspWrapper.processFloat(separateHandle, input, expected)
            val actual = input.copyOf()
            JamesDspWrapper.processFloat(inPlaceHandle, actual, actual)
            assertArrayEquals(expected, actual, 1.0e-5f)
        } finally {
            JamesDspWrapper.free(separateHandle)
            JamesDspWrapper.free(inPlaceHandle)
        }
    }

    private fun assertShortMatching(configure: (Long) -> Unit) {
        val separateHandle = JamesDspWrapper.alloc(TestCallbacks)
        val inPlaceHandle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(separateHandle != 0L && inPlaceHandle != 0L)
        try {
            configure(separateHandle)
            configure(inPlaceHandle)
            val input = ShortArray(2048) { index -> ((index % 37) * 701 - 12_000).toShort() }
            val expected = ShortArray(input.size)
            JamesDspWrapper.processInt16(separateHandle, input, expected)
            val actual = input.copyOf()
            JamesDspWrapper.processInt16(inPlaceHandle, actual, actual)
            assertArrayEquals(expected, actual)
        } finally {
            JamesDspWrapper.free(separateHandle)
            JamesDspWrapper.free(inPlaceHandle)
        }
    }

    private object TestCallbacks : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) = Unit
        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }
}
