package me.timschneeberger.rootlessjamesdsp.interop

import android.os.Debug
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveProgLifecycleInstrumentedTest {
    @Test
    fun repeatedEngineCloseDoesNotRetainNativeHeapOrTimerThreads() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        JamesDspLocalEngine(context).close()
        Thread.sleep(ENGINE_RELEASE_WAIT_MS)
        val baselineBytes = Debug.getNativeHeapAllocatedSize()

        repeat(5) {
            val engine = JamesDspLocalEngine(context)
            assertTrue(engine.handle != 0L)
            engine.close()
            engine.close()
            Thread.sleep(ENGINE_RELEASE_WAIT_MS)
            assertEquals(0L, engine.handle)
            assertFalse(engine.setOutputControl(-0.1f, 60f, 0f))
            assertTrue(engine.enumerateEelVariables().isEmpty())
            engine.freezeLiveprogExecution(true)
            val closedInput = floatArrayOf(0.25f, -0.25f)
            val closedOutput = FloatArray(closedInput.size)
            engine.processFloat(closedInput, closedOutput)
            assertTrue(closedInput.contentEquals(closedOutput))
            assertFalse(
                Thread.getAllStackTraces().keys.any {
                    it.isAlive && it.name == ENGINE_RELEASE_THREAD_NAME
                },
            )
        }

        val finalBytes = Debug.getNativeHeapAllocatedSize()
        assertTrue(
            "Repeated engine close retained ${finalBytes - baselineBytes} bytes",
            finalBytes - baselineBytes <= RELEASE_TOLERANCE_BYTES,
        )
    }

    @Test
    fun liveProgCanBeLoadedProcessedAndUnloaded() {
        val handle = JamesDspWrapper.alloc(TestCallbacks)
        assertTrue(handle != 0L)

        try {
            val disabledBytes = Debug.getNativeHeapAllocatedSize()
            val script = """
                @init
                gain = 0.5;
                @sample
                spl0 = spl0 * gain;
                spl1 = spl1 * gain;
            """.trimIndent()
            assertTrue(JamesDspWrapper.setLiveprog(handle, true, "memory-test", script))
            val enabledBytes = Debug.getNativeHeapAllocatedSize()
            assertTrue(
                "LiveProg VM allocation was ${enabledBytes - disabledBytes} bytes",
                enabledBytes - disabledBytes >= MINIMUM_VM_ALLOCATION_BYTES,
            )

            val input = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
            val output = FloatArray(input.size)
            JamesDspWrapper.processFloat(handle, input, output)
            assertEquals(0.125f, output[0], 0.001f)
            assertEquals(-0.125f, output[1], 0.001f)

            assertTrue(JamesDspWrapper.setLiveprog(handle, false, "memory-test", ""))
            val releasedBytes = Debug.getNativeHeapAllocatedSize()
            assertTrue(
                "LiveProg VM retained ${releasedBytes - disabledBytes} bytes after disable",
                releasedBytes - disabledBytes <= RELEASE_TOLERANCE_BYTES,
            )
            assertTrue(JamesDspWrapper.enumerateEelVariables(handle).isEmpty())
            assertFalse(JamesDspWrapper.manipulateEelVariable(handle, "gain", 1f))
            JamesDspWrapper.freezeLiveprogExecution(handle, true)

            val beforeInvalidScriptBytes = Debug.getNativeHeapAllocatedSize()
            assertFalse(
                JamesDspWrapper.setLiveprog(
                    handle,
                    true,
                    "invalid-memory-test",
                    "@init\ngain = (;\n@sample\nspl0 = spl0;",
                ),
            )
            val afterInvalidScriptBytes = Debug.getNativeHeapAllocatedSize()
            assertTrue(
                "Invalid script retained ${afterInvalidScriptBytes - beforeInvalidScriptBytes} bytes",
                afterInvalidScriptBytes - beforeInvalidScriptBytes <= RELEASE_TOLERANCE_BYTES,
            )
            assertTrue(JamesDspWrapper.enumerateEelVariables(handle).isEmpty())
        } finally {
            JamesDspWrapper.free(handle)
        }
    }

    private object TestCallbacks : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) = Unit
        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }

    private companion object {
        const val MINIMUM_VM_ALLOCATION_BYTES = 60L * 1024L * 1024L
        const val RELEASE_TOLERANCE_BYTES = 2L * 1024L * 1024L
        const val ENGINE_RELEASE_WAIT_MS = 250L
        const val ENGINE_RELEASE_THREAD_NAME = "JamesDSP handle release"
    }
}
