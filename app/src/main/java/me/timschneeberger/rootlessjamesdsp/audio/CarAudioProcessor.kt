package me.timschneeberger.rootlessjamesdsp.audio

import android.content.Context
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** Settings for the volume-linked loudness contour. All levels are in dB. */
data class AutoLoudnessSettings(
    val enabled: Boolean = false,
    val bassMaxDb: Float = 6f,
    val trebleMaxDb: Float = 3f,
    val lowVolumeDb: Float = -30f,
    val midVolumeDb: Float = -18f,
    val highVolumeDb: Float = -6f,
)

/** Public plan/API name retained alongside the concise internal name. */
typealias AutomaticLoudnessSettings = AutoLoudnessSettings

data class CompressorBandSettings(
    val thresholdDb: Float,
    val ratio: Float,
    val makeupDb: Float = 0f,
    val attackMs: Float = 8f,
    val releaseMs: Float = 120f,
)

/** Public plan/API name for one independently timed compressor band. */
typealias BandCompressorSettings = CompressorBandSettings

/** A transparent, stereo-linked three-band compressor. */
data class ThreeBandCompressorSettings(
    val enabled: Boolean = false,
    val preset: Int = 1,
    val low: CompressorBandSettings = CompressorBandSettings(-18f, 3f, 2f, 30f, 300f),
    val mid: CompressorBandSettings = CompressorBandSettings(-16f, 2f, 1f, 15f, 180f),
    val high: CompressorBandSettings = CompressorBandSettings(-20f, 2.5f, 0f, 5f, 120f),
)

data class CarSpatializerSettings(
    val enabled: Boolean = false,
    val mode: Int = 0,
    val strength: Float = 35f,
    val frontFocus: Float = 70f,
    val envelopment: Float = 20f,
    val cabinSize: Int = 1,
)

data class CarAudioSettings(
    val loudness: AutoLoudnessSettings = AutoLoudnessSettings(),
    val compressor: ThreeBandCompressorSettings = ThreeBandCompressorSettings(),
    val spatializer: CarSpatializerSettings = CarSpatializerSettings(),
    val outputPostGainDb: Float = 0f,
)

/**
 * Settings reader for the three new DSP namespaces. Preference XML defaults are duplicated here
 * so the audio thread also behaves correctly before Android has materialised a preference entry.
 */
object CarAudioSettingsLoader {
    private const val MODE_PRIVATE = Context.MODE_PRIVATE

    private fun SharedPreferences.float(key: String, default: Float): Float {
        return try {
            getFloat(key, default)
        } catch (_: ClassCastException) {
            getString(key, null)?.toFloatOrNull() ?: default
        }
    }

    private fun SharedPreferences.int(key: String, default: Int): Int {
        return try {
            getInt(key, default)
        } catch (_: ClassCastException) {
            getString(key, null)?.toIntOrNull() ?: default
        }
    }

    fun load(context: Context): CarAudioSettings {
        val loud = context.getSharedPreferences(Constants.PREF_AUTO_LOUDNESS, MODE_PRIVATE)
        val comp = context.getSharedPreferences(Constants.PREF_THREE_BAND_COMPRESSOR, MODE_PRIVATE)
        val spatial = context.getSharedPreferences(Constants.PREF_CAR_SPATIALIZER, MODE_PRIVATE)
        val output = context.getSharedPreferences(Constants.PREF_OUTPUT, MODE_PRIVATE)
        // Natural is the first usable contour; mode 0 is the explicit Off entry.
        val spatialMode = spatial.int("car_spatializer_mode", 1).coerceIn(0, 3)

        fun spatialFloat(key: String, default: Float): Float {
            // Keep a separate contour for each mode. The unsuffixed value is retained as a
            // migration fallback for installations created before mode-specific storage existed.
            return spatial.float("${key}_mode$spatialMode", spatial.float(key, default))
        }

        val loudLow = loud.float("auto_loudness_low_threshold", -30f).coerceIn(-60f, -0.2f)
        val loudMid = loud.float("auto_loudness_mid_threshold", -18f)
            .coerceIn(loudLow + 0.1f, -0.1f)
        val loudHigh = loud.float("auto_loudness_high_threshold", -6f)
            .coerceIn(loudMid + 0.1f, 0f)

        val commonAttack = comp.float("three_band_attack", 30f)
        val commonRelease = comp.float("three_band_release", 300f)
        fun band(key: String, threshold: Float, ratio: Float, makeup: Float, attack: Float, release: Float): CompressorBandSettings {
            val hasBandTiming = comp.contains("three_band_${key}_attack") || comp.contains("three_band_${key}_release")
            return CompressorBandSettings(
                thresholdDb = comp.float("three_band_${key}_threshold", threshold),
                ratio = comp.float("three_band_${key}_ratio", ratio),
                makeupDb = comp.float("three_band_${key}_makeup", makeup),
                attackMs = if (hasBandTiming) comp.float("three_band_${key}_attack", attack) else commonAttack,
                releaseMs = if (hasBandTiming) comp.float("three_band_${key}_release", release) else commonRelease,
            )
        }

        return CarAudioSettings(
            loudness = AutoLoudnessSettings(
                enabled = loud.getBoolean("auto_loudness_enable", false),
                bassMaxDb = loud.float("auto_loudness_bass_max", 6f).coerceIn(0f, 9f),
                trebleMaxDb = loud.float("auto_loudness_treble_max", 3f).coerceIn(0f, 6f),
                lowVolumeDb = loudLow,
                midVolumeDb = loudMid,
                highVolumeDb = loudHigh,
            ),
            compressor = ThreeBandCompressorSettings(
                enabled = comp.getBoolean("three_band_enable", false),
                preset = comp.int("three_band_preset", 1),
                low = band("low", -18f, 3f, 2f, 30f, 300f),
                mid = band("mid", -16f, 2f, 1f, 15f, 180f),
                high = band("high", -20f, 2.5f, 0f, 5f, 120f),
            ),
            spatializer = CarSpatializerSettings(
                enabled = spatial.getBoolean("car_spatializer_enable", false),
                mode = spatialMode,
                strength = spatialFloat("car_spatializer_strength", 35f),
                frontFocus = spatialFloat("car_spatializer_front_focus", 70f),
                envelopment = spatialFloat("car_spatializer_envelopment", 20f),
                cabinSize = spatial.int("car_spatializer_cabin_size", 1).coerceIn(0, 2),
            ),
            outputPostGainDb = output.float("output_postgain", 0f),
        )
    }
}

/**
 * Car-oriented processing that runs on the stereo PCM stream before JamesDSP's existing engine.
 * It deliberately contains no Android audio classes, which keeps the algorithm unit-testable.
 */
class CarAudioProcessor(private val sampleRate: Int) {
    @Volatile
    private lateinit var runtimeSettings: RuntimeCarAudioSettings

    @Volatile
    private var effectiveOutputGainDb = 0f

    @Volatile
    private var meterEnabled = false

    private var loudHighLpL = 0f
    private var loudHighLpR = 0f
    private var loudLowL = 0f
    private var loudLowR = 0f
    private var loudnessBassDb = 0f
    private var loudnessTrebleDb = 0f
    private var compressorLowRms = 1.0e-12f
    private var compressorMidRms = 1.0e-12f
    private var compressorHighRms = 1.0e-12f
    private var compressorLowGainDb = 0f
    private var compressorMidGainDb = 0f
    private var compressorHighGainDb = 0f
    @Volatile
    private var gainReductionDb = 0f
    @Volatile
    private var lowGainReductionDb = 0f
    @Volatile
    private var midGainReductionDb = 0f
    @Volatile
    private var highGainReductionDb = 0f
    private var spatialLowSide = 0f
    private var spatialBodySide = 0f
    private var spatialPunchLowMid = 0f
    private var spatialPunchBodyMid = 0f
    private var spatialPunchFastEnvelope = 0f
    private var spatialPunchSlowEnvelope = 0f
    private var spatialAmbienceLowMid = 0f
    private var spatialAmbienceLowResidual = 0f
    private var spatialAmbienceHighMid = 0f
    private var spatialAmbienceFastEnvelope = 0f
    private var spatialAmbienceSlowEnvelope = 0f
    private var spatialSideAmbienceMix = 0f
    private var spatialMidAmbienceMix = 0f
    private var spatialStrengthControl = 0f
    private var spatialFocusControl = 0f
    private var spatialWasEnabled = false
    private var spatialCabinSize = 1
    private val spatialAllPassA = FirstOrderAllPass(0.62f)
    private val spatialAllPassB = FirstOrderAllPass(-0.38f)
    private val spatialAmbienceMaxDelay = max(2, (sampleRate * 0.006f).toInt() + 4)
    private val spatialAmbienceL1 = VariableAllPass(spatialAmbienceMaxDelay)
    private val spatialAmbienceL2 = VariableAllPass(spatialAmbienceMaxDelay)
    private val spatialAmbienceR1 = VariableAllPass(spatialAmbienceMaxDelay)
    private val spatialAmbienceR2 = VariableAllPass(spatialAmbienceMaxDelay)
    private var floatScratch = FloatArray(0)

    // The car contour follows the plan's 100 Hz low shelf and 8 kHz high shelf.
    private val loudnessLow = onePoleCoefficient(100f)
    private val loudnessHigh = onePoleCoefficient(8000f)
    private val spatialLow = onePoleCoefficient(160f)
    private val spatialBody = onePoleCoefficient(6500f)
    private val spatialAmbienceLow = onePoleCoefficient(250f)
    private var spatialAmbienceHigh = onePoleCoefficient(6000f)
    private val spatialAmbienceMix = timeCoefficient(50f)
    private val spatialAmbienceFastAttack = timeCoefficient(4f)
    private val spatialAmbienceFastRelease = timeCoefficient(50f)
    private val spatialAmbienceSlowAttack = timeCoefficient(90f)
    private val spatialAmbienceSlowRelease = timeCoefficient(250f)
    private val spatialPunchLow = onePoleCoefficient(90f)
    private val spatialPunchBody = onePoleCoefficient(320f)
    private val spatialPunchFastAttack = timeCoefficient(3f)
    private val spatialPunchFastRelease = timeCoefficient(32f)
    private val spatialPunchSlowAttack = timeCoefficient(75f)
    private val spatialPunchSlowRelease = timeCoefficient(180f)
    private val loudnessAttack = timeCoefficient(150f)
    private val loudnessRelease = timeCoefficient(750f)

    // Two cascaded 2nd-order Butterworth sections form LR4 crossover slopes.
    private val lowL1 = Biquad.lowPass(sampleRate, 120f)
    private val lowL2 = Biquad.lowPass(sampleRate, 120f)
    private val lowR1 = Biquad.lowPass(sampleRate, 120f)
    private val lowR2 = Biquad.lowPass(sampleRate, 120f)
    private val midHighPassL1 = Biquad.highPass(sampleRate, 120f)
    private val midHighPassL2 = Biquad.highPass(sampleRate, 120f)
    private val midHighPassR1 = Biquad.highPass(sampleRate, 120f)
    private val midHighPassR2 = Biquad.highPass(sampleRate, 120f)
    private val midLowPassL1 = Biquad.lowPass(sampleRate, 2000f)
    private val midLowPassL2 = Biquad.lowPass(sampleRate, 2000f)
    private val midLowPassR1 = Biquad.lowPass(sampleRate, 2000f)
    private val midLowPassR2 = Biquad.lowPass(sampleRate, 2000f)
    private val highL1 = Biquad.highPass(sampleRate, 2000f)
    private val highL2 = Biquad.highPass(sampleRate, 2000f)
    private val highR1 = Biquad.highPass(sampleRate, 2000f)
    private val highR2 = Biquad.highPass(sampleRate, 2000f)

    init {
        // Build lookup tables before the service starts its realtime thread.
        FastAudioMath.warmUp()
        runtimeSettings = buildRuntimeSettings(CarAudioSettings())
    }

    fun update(newSettings: CarAudioSettings) {
        runtimeSettings = buildRuntimeSettings(newSettings)
    }

    val requiresProcessing: Boolean
        get() = runtimeSettings.requiresProcessing

    fun setEffectiveOutputGainDb(gainDb: Float) {
        effectiveOutputGainDb = if (gainDb.isFinite()) gainDb else 0f
    }

    fun getEffectiveOutputGainDb(): Float = effectiveOutputGainDb

    fun setMeterEnabled(enabled: Boolean) {
        meterEnabled = enabled
        if (!enabled) {
            gainReductionDb = 0f
            lowGainReductionDb = 0f
            midGainReductionDb = 0f
            highGainReductionDb = 0f
        }
    }

    fun getGainReductionDb(): Float = gainReductionDb
    fun getLowGainReductionDb(): Float = lowGainReductionDb
    fun getMidGainReductionDb(): Float = midGainReductionDb
    fun getHighGainReductionDb(): Float = highGainReductionDb

    /** Preallocates all conversion buffers on the service/control thread. */
    fun prepare(bufferSize: Int) {
        ensureFloatScratch(bufferSize)
    }

    fun process(input: FloatArray, output: FloatArray) {
        process(input, output, min(input.size, output.size))
    }

    fun process(input: FloatArray, output: FloatArray, requestedCount: Int) {
        val count = requestedCount.coerceIn(0, min(input.size, output.size))
        val current = runtimeSettings
        val spatial = current.spatial
        val spatialEnabled = spatial.enabled
        val updateMeter = meterEnabled
        if (spatial.cabinSize != spatialCabinSize) {
            spatialCabinSize = spatial.cabinSize
            spatialAmbienceHigh = onePoleCoefficient(spatial.cabinProfile.highCutoffHz)
            resetSpatialState()
        }
        if (spatialEnabled != spatialWasEnabled) {
            resetSpatialState()
            spatialWasEnabled = spatialEnabled
        }
        if (!current.requiresProcessing) {
            gainReductionDb = 0f
            lowGainReductionDb = 0f
            midGainReductionDb = 0f
            highGainReductionDb = 0f
            if (input !== output) input.copyInto(output, 0, 0, count)
            return
        }

        val loudness = current.loudness
        val loudnessBassTarget = if (loudness.enabled) {
            loudness.bassMaxDb * loudnessStrength(
                effectiveOutputGainDb,
                loudness.lowVolumeDb,
                loudness.midVolumeDb,
                loudness.highVolumeDb,
                treble = false,
            )
        } else 0f
        val loudnessTrebleTarget = if (loudness.enabled) {
            loudness.trebleMaxDb * loudnessStrength(
                effectiveOutputGainDb,
                loudness.lowVolumeDb,
                loudness.midVolumeDb,
                loudness.highVolumeDb,
                treble = true,
            )
        } else 0f
        val comp = current.compressor
        val lowAttackCoeff = comp.low.attack
        val lowReleaseCoeff = comp.low.release
        val midAttackCoeff = comp.mid.attack
        val midReleaseCoeff = comp.mid.release
        val highAttackCoeff = comp.high.attack
        val highReleaseCoeff = comp.high.release

        val spatialStrengthTarget = if (spatialEnabled) spatial.strength else 0f
        val focusTarget = if (spatialEnabled) spatial.frontFocus else 0f
        val envelope = if (spatialEnabled) spatial.envelopment else 0f
        val ambienceMix = spatial.ambienceMixScale * envelope
        val midAmbienceMix = spatial.midAmbienceMixScale * envelope
        val cabinProfile = spatial.cabinProfile
        val ambienceDelayL1 = spatial.ambienceDelayL1
        val ambienceDelayL2 = spatial.ambienceDelayL2
        val ambienceDelayR1 = spatial.ambienceDelayR1
        val ambienceDelayR2 = spatial.ambienceDelayR2

        var maxGainReduction = 0f
        var maxLowGainReduction = 0f
        var maxMidGainReduction = 0f
        var maxHighGainReduction = 0f
        var index = 0
        while (index + 1 < count) {
            var left = input[index]
            var right = input[index + 1]

            loudnessBassDb = smoothLoudnessGain(loudnessBassDb, loudnessBassTarget)
            loudnessTrebleDb = smoothLoudnessGain(loudnessTrebleDb, loudnessTrebleTarget)
            if (loudness.enabled) {
                val bassLinear = FastAudioMath.dbToLinear(loudnessBassDb) - 1f
                val trebleLinear = FastAudioMath.dbToLinear(loudnessTrebleDb) - 1f
                loudLowL += loudnessLow * (left - loudLowL)
                loudLowR += loudnessLow * (right - loudLowR)
                // The explicit one-pole state below avoids a second filter allocation and keeps
                // the shelf transition smooth at block boundaries.
                val highStateL = loudHighLpL + loudnessHigh * (left - loudHighLpL)
                val highStateR = loudHighLpR + loudnessHigh * (right - loudHighLpR)
                loudHighLpL = highStateL
                loudHighLpR = highStateR
                left += loudLowL * bassLinear + (left - highStateL) * trebleLinear
                right += loudLowR * bassLinear + (right - highStateR) * trebleLinear
            }

            if (comp.enabled) {
                val lowL = lowL2.process(lowL1.process(left))
                val lowR = lowR2.process(lowR1.process(right))
                // Each band is a genuine LR4 path. The mid band is a 120 Hz LR4 high-pass
                // followed by a 2 kHz LR4 low-pass; using upper-low here would mix filters with
                // different phase delays and create a large, false mid-band signal at 60 Hz.
                val midHpL = midHighPassL2.process(midHighPassL1.process(left))
                val midHpR = midHighPassR2.process(midHighPassR1.process(right))
                val midL = midLowPassL2.process(midLowPassL1.process(midHpL))
                val midR = midLowPassR2.process(midLowPassR1.process(midHpR))
                val highL = highL2.process(highL1.process(left))
                val highR = highR2.process(highR1.process(right))

                // RMS-linked power is averaged across L/R so both channels share the same gain
                // envelope and stereo localization remains stable.
                val lowPower = (lowL * lowL + lowR * lowR) * 0.5f
                val midPower = (midL * midL + midR * midR) * 0.5f
                val highPower = (highL * highL + highR * highR) * 0.5f
                compressorLowRms = smoothRms(compressorLowRms, lowPower, lowAttackCoeff, lowReleaseCoeff)
                compressorMidRms = smoothRms(compressorMidRms, midPower, midAttackCoeff, midReleaseCoeff)
                compressorHighRms = smoothRms(compressorHighRms, highPower, highAttackCoeff, highReleaseCoeff)

                val lowTarget = compressionGainDb(FastAudioMath.powerToDb(compressorLowRms), comp.low.settings)
                val midTarget = compressionGainDb(FastAudioMath.powerToDb(compressorMidRms), comp.mid.settings)
                val highTarget = compressionGainDb(FastAudioMath.powerToDb(compressorHighRms), comp.high.settings)
                compressorLowGainDb = smoothGain(compressorLowGainDb, lowTarget, lowAttackCoeff, lowReleaseCoeff)
                compressorMidGainDb = smoothGain(compressorMidGainDb, midTarget, midAttackCoeff, midReleaseCoeff)
                compressorHighGainDb = smoothGain(compressorHighGainDb, highTarget, highAttackCoeff, highReleaseCoeff)

                if (updateMeter) {
                    maxGainReduction = max(
                        maxGainReduction,
                        max(
                            max(-compressorLowGainDb, 0f),
                            max(max(-compressorMidGainDb, 0f), max(-compressorHighGainDb, 0f)),
                        ),
                    )
                    maxLowGainReduction = max(maxLowGainReduction, max(-compressorLowGainDb, 0f))
                    maxMidGainReduction = max(maxMidGainReduction, max(-compressorMidGainDb, 0f))
                    maxHighGainReduction = max(maxHighGainReduction, max(-compressorHighGainDb, 0f))
                }

                val lowGain = FastAudioMath.dbToLinear(compressorLowGainDb)
                val midGain = FastAudioMath.dbToLinear(compressorMidGainDb)
                val highGain = FastAudioMath.dbToLinear(compressorHighGainDb)
                left = lowL * lowGain + midL * midGain + highL * highGain
                right = lowR * lowGain + midR * midGain + highR * highGain
            }

            if (spatialEnabled) {
                spatialStrengthControl = smoothEnvelope(
                    spatialStrengthControl,
                    spatialStrengthTarget,
                    spatialAmbienceMix,
                    spatialAmbienceMix,
                )
                spatialFocusControl = smoothEnvelope(
                    spatialFocusControl,
                    focusTarget,
                    spatialAmbienceMix,
                    spatialAmbienceMix,
                )
                val strengthCurve = spatialStrengthControl * (0.65f + 0.35f * spatialStrengthControl)
                val widthAmount = strengthCurve * spatial.widthScale
                val punchScale = strengthCurve * (0.35f + 0.65f * spatialFocusControl)
                val punchBodyAmount = punchScale * spatial.punchBodyScale
                val punchTransientAmount = punchScale * spatial.punchTransientScale
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f
                val lowSide = spatialLowSide + spatialLow * (side - spatialLowSide)
                spatialLowSide = lowSide
                val lowAndBodySide = spatialBodySide + spatialBody * (side - spatialBodySide)
                spatialBodySide = lowAndBodySide
                val bodySide = lowAndBodySide - lowSide
                val airSide = side - lowAndBodySide

                // Keep bass and upper treble stable, where large antiphase boosts are most
                // fatiguing. Front focus narrows the body expansion; the separate direct stage
                // below supplies low-mid impact without a broadband center lift.
                val lowWidth = 1f - 0.08f * strengthCurve
                val bodyWidth = 1f - 0.15f * spatialFocusControl +
                    widthAmount * (1f - 0.35f * spatialFocusControl)
                val airWidth = 1f + 0.45f * widthAmount

                // Short all-pass diffusion changes phase without adding the previous 5–20 ms
                // discrete Side echo. Its contribution is deliberately bounded per mode.
                val ambienceInput = bodySide + 0.35f * airSide
                val diffusedSide = spatialAllPassB.process(spatialAllPassA.process(ambienceInput))
                spatialSideAmbienceMix = smoothEnvelope(
                    spatialSideAmbienceMix,
                    ambienceMix,
                    spatialAmbienceMix,
                    spatialAmbienceMix,
                )
                spatialMidAmbienceMix = smoothEnvelope(
                    spatialMidAmbienceMix,
                    midAmbienceMix,
                    spatialAmbienceMix,
                    spatialAmbienceMix,
                )
                val sideOut = lowSide * lowWidth +
                    bodySide * bodyWidth +
                    airSide * airWidth +
                    (diffusedSide - ambienceInput) * spatialSideAmbienceMix

                // Restore impact from the direct signal, not from more antiphase width. The
                // The 90–320 Hz band receives a small body lift plus a short attack-only lift.
                // Keeping body gain below transient gain avoids turning cabin bass into a bloom.
                val punchLowMid = spatialPunchLowMid + spatialPunchLow * (mid - spatialPunchLowMid)
                spatialPunchLowMid = punchLowMid
                val punchBodyMid = spatialPunchBodyMid + spatialPunchBody * (mid - spatialPunchBodyMid)
                spatialPunchBodyMid = punchBodyMid
                val punchBand = punchBodyMid - punchLowMid
                val punchMagnitude = abs(punchBand)
                spatialPunchFastEnvelope = smoothEnvelope(
                    spatialPunchFastEnvelope,
                    punchMagnitude,
                    spatialPunchFastAttack,
                    spatialPunchFastRelease,
                )
                spatialPunchSlowEnvelope = smoothEnvelope(
                    spatialPunchSlowEnvelope,
                    punchMagnitude,
                    spatialPunchSlowAttack,
                    spatialPunchSlowRelease,
                )
                val transient = (
                    (spatialPunchFastEnvelope - spatialPunchSlowEnvelope) /
                        max(spatialPunchFastEnvelope, 0.0001f)
                ).coerceIn(0f, 1f)
                val midOut = mid + punchBand * (
                    punchBodyAmount + punchTransientAmount * transient
                )

                // Build a quiet cabin bed from sustained Mid content. Separate short all-pass
                // paths lower inter-channel coherence without creating a single Haas tap, while
                // the attack gate keeps the front image anchored to the direct signal.
                val ambienceLowMid = spatialAmbienceLowMid + spatialAmbienceLow * (mid - spatialAmbienceLowMid)
                spatialAmbienceLowMid = ambienceLowMid
                // Cascade two high-pass stages so sub-bass never enters the recirculating paths.
                val ambienceHighPassed = mid - ambienceLowMid
                val ambienceLowResidual = spatialAmbienceLowResidual +
                    spatialAmbienceLow * (ambienceHighPassed - spatialAmbienceLowResidual)
                spatialAmbienceLowResidual = ambienceLowResidual
                val tightenedAmbience = ambienceHighPassed - ambienceLowResidual
                val ambienceHighMid = spatialAmbienceHighMid +
                    spatialAmbienceHigh * (tightenedAmbience - spatialAmbienceHighMid)
                spatialAmbienceHighMid = ambienceHighMid
                val ambienceBand = ambienceHighMid
                val ambienceMagnitude = abs(ambienceBand)
                spatialAmbienceFastEnvelope = smoothEnvelope(
                    spatialAmbienceFastEnvelope,
                    ambienceMagnitude,
                    spatialAmbienceFastAttack,
                    spatialAmbienceFastRelease,
                )
                spatialAmbienceSlowEnvelope = smoothEnvelope(
                    spatialAmbienceSlowEnvelope,
                    ambienceMagnitude,
                    spatialAmbienceSlowAttack,
                    spatialAmbienceSlowRelease,
                )
                val ambienceTransient = (
                    (spatialAmbienceFastEnvelope - spatialAmbienceSlowEnvelope) /
                        max(spatialAmbienceFastEnvelope, 0.0001f)
                    ).coerceIn(0f, 1f)
                val ambienceSource = ambienceBand * (1f - 0.75f * ambienceTransient)
                val ambienceLeft = spatialAmbienceL2.process(
                    spatialAmbienceL1.process(ambienceSource, ambienceDelayL1, cabinProfile.firstFeedback),
                    ambienceDelayL2,
                    cabinProfile.secondFeedback,
                )
                val ambienceRight = spatialAmbienceR2.process(
                    spatialAmbienceR1.process(ambienceSource, ambienceDelayR1, cabinProfile.firstFeedback),
                    ambienceDelayR2,
                    cabinProfile.secondFeedback,
                )
                val ambienceCommon = (ambienceLeft + ambienceRight) * 0.175f
                val ambienceSide = (ambienceLeft - ambienceRight) * 0.5f
                left = midOut + sideOut + spatialMidAmbienceMix * (ambienceCommon + ambienceSide)
                right = midOut - sideOut + spatialMidAmbienceMix * (ambienceCommon - ambienceSide)
            }

            output[index] = safeLimit(left)
            output[index + 1] = safeLimit(right)
            index += 2
        }
        if (updateMeter) {
            gainReductionDb = maxGainReduction
            lowGainReductionDb = maxLowGainReduction
            midGainReductionDb = maxMidGainReduction
            highGainReductionDb = maxHighGainReduction
        }
        if (index < count) output[index] = safeLimit(input[index])
        if (count < output.size && input !== output) input.copyInto(output, count, count, min(input.size, output.size))
    }

    fun process(input: ShortArray, output: ShortArray) {
        process(input, output, min(input.size, output.size))
    }

    fun process(input: ShortArray, output: ShortArray, requestedCount: Int) {
        val count = requestedCount.coerceIn(0, min(input.size, output.size))
        if (count == 0) return
        if (floatScratch.size < count) {
            // The service calls prepare() before starting the realtime thread. Never allocate on
            // this path: a missed preparation must fail transparently instead of causing an xrun.
            if (input !== output) input.copyInto(output, 0, 0, count)
            return
        }
        for (i in 0 until count) floatScratch[i] = input[i] / 32768f
        process(floatScratch, floatScratch, count)
        for (i in 0 until count) {
            output[i] = (floatScratch[i].coerceIn(-1f, 0.9999695f) * 32768f).toInt().toShort()
        }
        if (count < output.size && input !== output) input.copyInto(output, count, count, min(input.size, output.size))
    }

    private fun ensureFloatScratch(size: Int) {
        if (floatScratch.size < size) {
            floatScratch = FloatArray(size)
        }
    }

    private fun buildRuntimeSettings(input: CarAudioSettings): RuntimeCarAudioSettings {
        val compressorSettings = effectiveCompressorSettings(input.compressor)
        val compressor = RuntimeCompressorSettings(
            enabled = input.compressor.enabled,
            low = RuntimeCompressorBand(
                compressorSettings.low,
                timeCoefficient(compressorSettings.low.attackMs),
                timeCoefficient(compressorSettings.low.releaseMs),
            ),
            mid = RuntimeCompressorBand(
                compressorSettings.mid,
                timeCoefficient(compressorSettings.mid.attackMs),
                timeCoefficient(compressorSettings.mid.releaseMs),
            ),
            high = RuntimeCompressorBand(
                compressorSettings.high,
                timeCoefficient(compressorSettings.high.attackMs),
                timeCoefficient(compressorSettings.high.releaseMs),
            ),
        )

        val spatialSettings = input.spatializer
        val spatialMode = spatialSettings.mode.coerceIn(0, 3)
        val spatialEnabled = spatialSettings.enabled && spatialMode != 0
        val cabinSize = spatialSettings.cabinSize.coerceIn(0, 2)
        val profile = cabinProfile(cabinSize)
        var ambienceMixScale = 0f
        var midAmbienceMixScale = 0f
        var widthScale = 0f
        var punchBodyScale = 0f
        var punchTransientScale = 0f
        when (spatialMode) {
            1 -> {
                ambienceMixScale = 0.10f
                midAmbienceMixScale = 0.06f
                widthScale = 0.20f
                punchBodyScale = 0.02f
                punchTransientScale = 0.09f
            }
            2 -> {
                ambienceMixScale = 0.16f
                midAmbienceMixScale = 0.10f
                widthScale = 0.42f
                punchBodyScale = 0.035f
                punchTransientScale = 0.16f
            }
            3 -> {
                ambienceMixScale = 0.22f
                midAmbienceMixScale = 0.15f
                widthScale = 0.58f
                punchBodyScale = 0.05f
                punchTransientScale = 0.24f
            }
        }
        return RuntimeCarAudioSettings(
            loudness = input.loudness,
            compressor = compressor,
            spatial = RuntimeSpatialSettings(
                enabled = spatialEnabled,
                strength = normalizedPercent(spatialSettings.strength),
                frontFocus = normalizedPercent(spatialSettings.frontFocus),
                envelopment = normalizedPercent(spatialSettings.envelopment),
                cabinSize = cabinSize,
                cabinProfile = profile,
                ambienceDelayL1 = delaySamples(profile.leftFirstDelayMs),
                ambienceDelayL2 = delaySamples(profile.leftSecondDelayMs),
                ambienceDelayR1 = delaySamples(profile.rightFirstDelayMs),
                ambienceDelayR2 = delaySamples(profile.rightSecondDelayMs),
                ambienceMixScale = ambienceMixScale,
                midAmbienceMixScale = midAmbienceMixScale,
                widthScale = widthScale,
                punchBodyScale = punchBodyScale,
                punchTransientScale = punchTransientScale,
            ),
        )
    }

    private fun resetSpatialState() {
        spatialLowSide = 0f
        spatialBodySide = 0f
        spatialPunchLowMid = 0f
        spatialPunchBodyMid = 0f
        spatialPunchFastEnvelope = 0f
        spatialPunchSlowEnvelope = 0f
        spatialAmbienceLowMid = 0f
        spatialAmbienceLowResidual = 0f
        spatialAmbienceHighMid = 0f
        spatialAmbienceFastEnvelope = 0f
        spatialAmbienceSlowEnvelope = 0f
        spatialSideAmbienceMix = 0f
        spatialMidAmbienceMix = 0f
        spatialStrengthControl = 0f
        spatialFocusControl = 0f
        spatialAllPassA.reset()
        spatialAllPassB.reset()
        spatialAmbienceL1.reset()
        spatialAmbienceL2.reset()
        spatialAmbienceR1.reset()
        spatialAmbienceR2.reset()
    }

    private fun normalizedPercent(value: Float): Float {
        return if (value.isFinite()) value.coerceIn(0f, 100f) / 100f else 0f
    }

    private fun delaySamples(milliseconds: Float): Int {
        return ((sampleRate * milliseconds / 1000f).toInt())
            .coerceIn(1, spatialAmbienceMaxDelay - 1)
    }

    private fun onePoleCoefficient(cutoff: Float): Float {
        return (1.0 - exp(-2.0 * Math.PI * cutoff / sampleRate)).toFloat().coerceIn(0.0001f, 1f)
    }

    private fun timeCoefficient(milliseconds: Float): Float {
        val exponent = (-1.0 / (sampleRate * (milliseconds.coerceAtLeast(0.1f) / 1000f)))
            .coerceAtLeast(-50.0)
        return (1.0 - exp(exponent)).toFloat()
            .coerceIn(0.0001f, 1f)
    }

    private fun smoothRms(previous: Float, target: Float, attack: Float, release: Float): Float {
        val coefficient = if (target > previous) attack else release
        return max(1.0e-12f, previous + coefficient * (target - previous))
    }

    private fun smoothGain(previous: Float, target: Float, attack: Float, release: Float): Float {
        val coefficient = if (target < previous) attack else release
        return previous + coefficient * (target - previous)
    }

    private fun smoothEnvelope(previous: Float, target: Float, attack: Float, release: Float): Float {
        val coefficient = if (target > previous) attack else release
        return previous + coefficient * (target - previous)
    }

    private fun smoothLoudnessGain(previous: Float, target: Float): Float {
        val coefficient = if (target > previous) loudnessAttack else loudnessRelease
        return previous + coefficient * (target - previous)
    }

    /** 6 dB soft-knee compressor curve plus per-band makeup gain. */
    private fun compressionGainDb(levelDb: Float, band: CompressorBandSettings): Float {
        val ratio = band.ratio.coerceIn(1f, 20f)
        val x = levelDb - band.thresholdDb
        val knee = 6f
        val gainReduction = if (x <= -knee * 0.5f) {
            0f
        } else if (x >= knee * 0.5f) {
            x * (1f / ratio - 1f)
        } else {
            val distance = x + knee * 0.5f
            (1f / ratio - 1f) * distance * distance / (2f * knee)
        }
        return gainReduction + band.makeupDb.coerceIn(0f, 6f)
    }

    private fun effectiveCompressorSettings(input: ThreeBandCompressorSettings): ThreeBandCompressorSettings {
        val normal = ThreeBandCompressorSettings()
        // A preset is materialized into the preference fields by the UI. Only legacy data that
        // contains a non-Normal preset but still has untouched Normal fields is expanded here;
        // changing any band deliberately turns that preset into a custom contour.
        val isUntuned = input.low == normal.low && input.mid == normal.mid && input.high == normal.high
        return if (isUntuned && input.preset != 1) presetSettings(input.preset) else input
    }

    private fun safeLimit(value: Float): Float {
        if (!value.isFinite()) return 0f
        val magnitude = abs(value)
        if (magnitude <= SOFT_LIMIT) return value
        val sign = if (value < 0f) -1f else 1f
        return sign * (SOFT_LIMIT + LIMIT_HEADROOM * tanh(((magnitude - SOFT_LIMIT) * LIMIT_CURVE).toDouble()).toFloat())
    }

    companion object {
        // Leave a small headroom for the existing JamesDSP limiter after the car DSP stages.
        private const val SOFT_LIMIT = 0.98f
        private const val LIMIT_HEADROOM = 0.02f
        private const val LIMIT_CURVE = 4f

        fun presetSettings(preset: Int): ThreeBandCompressorSettings {
            return when (preset.coerceIn(0, 2)) {
                0 -> ThreeBandCompressorSettings(
                    enabled = true,
                    preset = 0,
                    low = CompressorBandSettings(-12f, 2f, 1f, 35f, 350f),
                    mid = CompressorBandSettings(-10f, 1.5f, 0.5f, 20f, 220f),
                    high = CompressorBandSettings(-14f, 2f, 0f, 8f, 150f),
                )
                2 -> ThreeBandCompressorSettings(
                    enabled = true,
                    preset = 2,
                    low = CompressorBandSettings(-24f, 4f, 3f, 25f, 250f),
                    mid = CompressorBandSettings(-22f, 3f, 1.5f, 10f, 150f),
                    high = CompressorBandSettings(-26f, 4f, 0f, 3f, 100f),
                )
                else -> ThreeBandCompressorSettings()
            }
        }

        fun loudnessPresetSettings(preset: Int): AutoLoudnessSettings {
            return when (preset.coerceIn(0, 2)) {
                0 -> AutoLoudnessSettings(
                    bassMaxDb = 3f,
                    trebleMaxDb = 1.5f,
                    lowVolumeDb = -30f,
                    midVolumeDb = -18f,
                    highVolumeDb = -6f,
                )
                2 -> AutoLoudnessSettings(
                    bassMaxDb = 9f,
                    trebleMaxDb = 6f,
                    lowVolumeDb = -30f,
                    midVolumeDb = -18f,
                    highVolumeDb = -6f,
                )
                else -> AutoLoudnessSettings()
            }
        }

        /** Returns 1 at the low-volume point, 0.5 at the mid point and 0 at high volume. */
        fun loudnessStrength(gainDb: Float, lowDb: Float, midDb: Float, highDb: Float, treble: Boolean): Float {
            // The treble table is 3 dB -> 1 dB -> 0 dB, hence normalized strengths 1, 1/3, 0.
            // Bass is 6 dB -> 3 dB -> 0 dB, hence normalized strengths 1, 1/2, 0.
            if (gainDb <= lowDb) return 1f
            if (gainDb >= highDb) return 0f
            return if (gainDb <= midDb) {
                val t = ((gainDb - lowDb) / (midDb - lowDb).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                if (treble) 1f - (2f / 3f) * t else 1f - 0.5f * t
            } else {
                val t = ((gainDb - midDb) / (highDb - midDb).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                if (treble) (1f / 3f) * (1f - t) else 0.5f * (1f - t)
            }
        }
    }
}

private data class RuntimeCompressorBand(
    val settings: CompressorBandSettings,
    val attack: Float,
    val release: Float,
)

private data class RuntimeCompressorSettings(
    val enabled: Boolean,
    val low: RuntimeCompressorBand,
    val mid: RuntimeCompressorBand,
    val high: RuntimeCompressorBand,
)

private data class RuntimeSpatialSettings(
    val enabled: Boolean,
    val strength: Float,
    val frontFocus: Float,
    val envelopment: Float,
    val cabinSize: Int,
    val cabinProfile: CabinProfile,
    val ambienceDelayL1: Int,
    val ambienceDelayL2: Int,
    val ambienceDelayR1: Int,
    val ambienceDelayR2: Int,
    val ambienceMixScale: Float,
    val midAmbienceMixScale: Float,
    val widthScale: Float,
    val punchBodyScale: Float,
    val punchTransientScale: Float,
)

private data class RuntimeCarAudioSettings(
    val loudness: AutoLoudnessSettings,
    val compressor: RuntimeCompressorSettings,
    val spatial: RuntimeSpatialSettings,
) {
    val requiresProcessing: Boolean
        get() = loudness.enabled || compressor.enabled || spatial.enabled
}

/** Allocation-free approximations used by the realtime compressor and loudness paths. */
internal object FastAudioMath {
    private const val DB_MIN = -120f
    private const val DB_MAX = 12f
    private const val DB_STEPS_PER_UNIT = 16f
    private const val LN_10_OVER_20 = 0.11512925465f
    private const val LOG2_TO_DB = 3.01029995664f
    private const val LOG2_MANTISSA_STEPS = 256
    private const val LOG2_MANTISSA_SCALE = 1f / LOG2_MANTISSA_STEPS
    private const val MIN_POWER = 1.0e-12f

    private val dbLinearTable = FloatArray(((DB_MAX - DB_MIN) * DB_STEPS_PER_UNIT).toInt() + 1) {
        exp((DB_MIN + it / DB_STEPS_PER_UNIT) * LN_10_OVER_20)
    }
    private val log2MantissaTable = FloatArray(LOG2_MANTISSA_STEPS + 1) {
        (ln(1.0 + it * LOG2_MANTISSA_SCALE) / ln(2.0)).toFloat()
    }

    fun warmUp() {
        // Accessing the tables from the service/control thread forces class initialization there.
        dbLinearTable[0] += 0f
        log2MantissaTable[0] += 0f
    }

    fun dbToLinear(db: Float): Float {
        if (!db.isFinite()) return if (db < 0f) 0f else dbLinearTable.last()
        val clamped = db.coerceIn(DB_MIN, DB_MAX)
        val position = (clamped - DB_MIN) * DB_STEPS_PER_UNIT
        val index = position.toInt().coerceAtMost(dbLinearTable.lastIndex)
        if (index == dbLinearTable.lastIndex) return dbLinearTable[index]
        val fraction = position - index
        return dbLinearTable[index] + fraction * (dbLinearTable[index + 1] - dbLinearTable[index])
    }

    fun powerToDb(power: Float): Float {
        if (!power.isFinite() || power <= MIN_POWER) return -120f
        val bits = power.toRawBits()
        val exponentBits = (bits ushr 23) and 0xff
        if (exponentBits == 0) {
            return (10.0 * ln(power.toDouble()) / ln(10.0)).toFloat().coerceIn(DB_MIN, DB_MAX)
        }

        val mantissa = (bits and 0x7fffff) * (1f / 0x800000)
        val position = mantissa * LOG2_MANTISSA_STEPS
        val index = position.toInt().coerceAtMost(LOG2_MANTISSA_STEPS - 1)
        val fraction = position - index
        val log2Mantissa = log2MantissaTable[index] + fraction *
            (log2MantissaTable[index + 1] - log2MantissaTable[index])
        return ((exponentBits - 127) + log2Mantissa) * LOG2_TO_DB
            .coerceIn(DB_MIN, DB_MAX)
    }
}

private data class CabinProfile(
    val leftFirstDelayMs: Float,
    val leftSecondDelayMs: Float,
    val rightFirstDelayMs: Float,
    val rightSecondDelayMs: Float,
    val firstFeedback: Float,
    val secondFeedback: Float,
    val highCutoffHz: Float,
)

private val COMPACT_CABIN_PROFILE = CabinProfile(
    leftFirstDelayMs = 0.7f,
    leftSecondDelayMs = 2.1f,
    rightFirstDelayMs = 1.0f,
    rightSecondDelayMs = 1.7f,
    firstFeedback = 0.45f,
    secondFeedback = 0.35f,
    highCutoffHz = 6500f,
)

private val STANDARD_CABIN_PROFILE = CabinProfile(
    leftFirstDelayMs = 0.9f,
    leftSecondDelayMs = 3.1f,
    rightFirstDelayMs = 1.4f,
    rightSecondDelayMs = 2.65f,
    firstFeedback = 0.50f,
    secondFeedback = 0.40f,
    highCutoffHz = 6000f,
)

private val LARGE_CABIN_PROFILE = CabinProfile(
    leftFirstDelayMs = 1.5f,
    leftSecondDelayMs = 4.85f,
    rightFirstDelayMs = 2.0f,
    rightSecondDelayMs = 4.1f,
    firstFeedback = 0.55f,
    secondFeedback = 0.45f,
    highCutoffHz = 5200f,
)

private fun cabinProfile(size: Int): CabinProfile {
    return when (size.coerceIn(0, 2)) {
        0 -> COMPACT_CABIN_PROFILE
        2 -> LARGE_CABIN_PROFILE
        else -> STANDARD_CABIN_PROFILE
    }
}

/** Sub-millisecond phase diffuser; unlike a Haas tap it has no isolated delayed copy. */
private class FirstOrderAllPass(private val coefficient: Float) {
    private var previousInput = 0f
    private var previousOutput = 0f

    fun process(input: Float): Float {
        val output = -coefficient * input + previousInput + coefficient * previousOutput
        previousInput = input
        previousOutput = output
        return output
    }

    fun reset() {
        previousInput = 0f
        previousOutput = 0f
    }
}

/** Allocation-free Schroeder all-pass with a selectable short delay. */
private class VariableAllPass(maxDelay: Int) {
    private val buffer = FloatArray(maxDelay)
    private var writeIndex = 0

    fun process(input: Float, delaySamples: Int, feedback: Float): Float {
        var readIndex = writeIndex - delaySamples
        if (readIndex < 0) readIndex += buffer.size
        val delayed = buffer[readIndex]
        val output = -feedback * input + delayed
        buffer[writeIndex] = input + feedback * output
        writeIndex = if (writeIndex + 1 == buffer.size) 0 else writeIndex + 1
        return output
    }

    fun reset() {
        buffer.fill(0f)
        writeIndex = 0
    }
}

/** Allocation-free biquad state used by the LR4 crossover. */
private class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(input: Float): Float {
        // Store normalized cookbook a1/a2 coefficients; the recurrence below subtracts them.
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    companion object {
        private const val Q = 0.70710677

        fun lowPass(sampleRate: Int, cutoff: Float): Biquad = create(sampleRate, cutoff, false)

        fun highPass(sampleRate: Int, cutoff: Float): Biquad = create(sampleRate, cutoff, true)

        private fun create(sampleRate: Int, cutoff: Float, highPass: Boolean): Biquad {
            val omega = 2.0 * Math.PI * cutoff / sampleRate
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / (2.0 * Q)
            val a0 = 1.0 + alpha
            val b0: Double
            val b1: Double
            val b2: Double
            if (highPass) {
                b0 = (1.0 + cosOmega) / 2.0
                b1 = -(1.0 + cosOmega)
                b2 = b0
            } else {
                b0 = (1.0 - cosOmega) / 2.0
                b1 = 1.0 - cosOmega
                b2 = b0
            }
            return Biquad(
                (b0 / a0).toFloat(),
                (b1 / a0).toFloat(),
                (b2 / a0).toFloat(),
                (-2.0 * cosOmega / a0).toFloat(),
                ((1.0 - alpha) / a0).toFloat(),
            )
        }
    }
}
