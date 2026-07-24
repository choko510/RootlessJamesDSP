package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    @Volatile
    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            val currentHandle = handle
            if (currentHandle != 0L) {
                JamesDspWrapper.setSamplingRate(currentHandle, value, false)
            }
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    @Synchronized
    override fun close() {
        val oldHandle = handle
        if (oldHandle == 0L) return
        handle = 0
        super.close()

        // Make sure ongoing async calls to native have enough time to finish
        val releaseTimer = Timer("JamesDSP handle release", true)
        releaseTimer.schedule(100) {
            try {
                JamesDspWrapper.free(oldHandle)
                Timber.d("Handle $oldHandle has been freed")
            } finally {
                releaseTimer.cancel()
            }
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        val currentHandle = handle
        if(!enabled || currentHandle == 0L)
        {
            if (input === output) return
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt16(currentHandle, input, output, offset, length)
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        val currentHandle = handle
        if(!enabled || currentHandle == 0L)
        {
            if (input === output) return
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt32(currentHandle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        val currentHandle = handle
        if(!enabled || currentHandle == 0L)
        {
            if (input === output) return
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processFloat(currentHandle, input, output, offset, length)
        }
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return withOpenHandle { currentHandle ->
            JamesDspWrapper.setLimiter(currentHandle, threshold, release) and
                JamesDspWrapper.setPostGain(currentHandle, postGain)
        }
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.setReverb(it, enable, preset)
        }
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.setCrossfeed(it, enable, mode, 0, 0)
        }
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.setCrossfeed(it, enable, 99, fcut, feed)
        }
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.setBassBoost(it, enable, maxGain)
        }
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.setStereoEnhancement(it, enable, level)
        }
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.setVacuumTube(it, enable, level)
        }
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return withOpenHandle {
            JamesDspWrapper.setMultiEqualizer(it, enable, filterType, interpolationMode, bands)
        }
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return withOpenHandle {
            JamesDspWrapper.setCompander(it, enable, timeConstant, granularity, tfTransforms, bands)
        }
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return withOpenHandle {
            JamesDspWrapper.setVdc(it, enable, vdc)
        }
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return withOpenHandle {
            JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames)
        }
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return withOpenHandle {
            JamesDspWrapper.setGraphicEq(it, enable, bands)
        }
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return withOpenHandle {
            JamesDspWrapper.setLiveprog(it, enable, name, script)
        }
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        val currentHandle = handle
        if (currentHandle == 0L) return arrayListOf()
        return JamesDspWrapper.enumerateEelVariables(currentHandle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return withOpenHandle {
            JamesDspWrapper.manipulateEelVariable(it, name, value)
        }
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        val currentHandle = handle
        if (currentHandle != 0L) {
            JamesDspWrapper.freezeLiveprogExecution(currentHandle, freeze)
        }
    }

    private inline fun withOpenHandle(operation: (JamesDspHandle) -> Boolean): Boolean {
        val currentHandle = handle
        if (currentHandle == 0L) return false
        return operation(currentHandle)
    }
}
