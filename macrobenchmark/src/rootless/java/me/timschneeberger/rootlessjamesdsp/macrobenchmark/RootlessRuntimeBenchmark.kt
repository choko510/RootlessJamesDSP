package me.timschneeberger.rootlessjamesdsp.macrobenchmark

import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMetricApi::class, ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class RootlessRuntimeBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun float8192TraceSections() = measureRuntime(PcmEncoding.Float)

    @Test
    fun short8192TraceSections() = measureRuntime(PcmEncoding.Short)

    @Test
    fun float1024TraceSections() = measureRuntime(PcmEncoding.Float, bufferSize = 1024)

    @Test
    fun carAudioEnabledFloat8192TraceSections() = measureRuntime(
        PcmEncoding.Float,
        carAudioEnabled = true,
    )

    private fun measureRuntime(
        encoding: PcmEncoding,
        bufferSize: Int = 8192,
        carAudioEnabled: Boolean = false,
    ) {
        var tone: CapturableTone? = null
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = buildTraceMetrics(carAudioEnabled),
            compilationMode = CompilationMode.Ignore(),
            iterations = 5,
            startupMode = null,
            setupBlock = {
                tone = CapturableTone(encoding).also { it.start() }
                device.executeShellCommand("pm clear $targetPackage")
                grantPermission("android.permission.DUMP")
                grantPermission("android.permission.RECORD_AUDIO")
                grantPermission("android.permission.POST_NOTIFICATIONS")

                pressHome()
                device.executeShellCommand("am force-stop $targetPackage")
                startActivityAndWait()
                waitForPowerToggle()
                if (bufferSize != 8192) selectBufferSize(bufferSize)
                if (encoding == PcmEncoding.Short) selectShortEncoding()
                if (carAudioEnabled) enableCarAudio()
                device.findObject(By.res(targetPackage, "power_toggle")).click()
                confirmMediaProjection()
                waitForRootlessService()
                device.pressBack()
                Thread.sleep(WARMUP_MS)
            },
            measureBlock = {
                try {
                    Thread.sleep(MEASURE_MS)
                } finally {
                    tone?.stop()
                    tone = null
                }
            },
        )
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForPowerToggle() {
        if (device.wait(Until.hasObject(By.res(targetPackage, "power_toggle")), 2_000)) return
        // A previous iteration can leave SettingsActivity at the top of the task even after the
        // target process was force-stopped. Return to MainActivity before declaring setup failed.
        device.pressBack()
        check(device.wait(Until.hasObject(By.res(targetPackage, "power_toggle")), UI_TIMEOUT_MS)) {
            "Rootless power toggle did not appear"
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.selectShortEncoding() {
        val settings = checkNotNull(device.wait(Until.findObject(By.res(targetPackage, "action_settings")), UI_TIMEOUT_MS)) {
            "Settings button did not appear"
        }
        settings.click()
        clickText(AUDIO_PROCESSING_PATTERN)
        clickText(AUDIO_ENCODING_PATTERN)
        clickText(PCM_SHORT_PATTERN)
        device.pressBack()
        device.pressBack()
        waitForPowerToggle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.enableCarAudio() {
        val scrollView = device.findObject(By.res(targetPackage, "dsp_scrollview"))
        var compressor = device.findObject(By.text(CAR_COMPRESSOR_TITLE_PATTERN))
        repeat(6) {
            if (compressor != null) return@repeat
            scrollView?.swipe(Direction.DOWN, 0.8f)
            Thread.sleep(100)
            compressor = device.findObject(By.text(CAR_COMPRESSOR_TITLE_PATTERN))
        }
        checkNotNull(compressor) { "Car Audio compressor preference did not appear" }.click()
        val switch = checkNotNull(
            device.wait(Until.findObject(By.res(SWITCH_RESOURCE)), UI_TIMEOUT_MS),
        ) { "Car Audio compressor switch did not appear" }
        if (!switch.isChecked) switch.click()
        check(device.wait(Until.findObject(By.res(SWITCH_RESOURCE)), UI_TIMEOUT_MS)?.isChecked == true) {
            "Car Audio compressor could not be enabled"
        }
        device.pressBack()
        waitForPowerToggle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.selectBufferSize(bufferSize: Int) {
        val settings = checkNotNull(device.wait(Until.findObject(By.res(targetPackage, "action_settings")), UI_TIMEOUT_MS)) {
            "Settings button did not appear"
        }
        settings.click()
        clickText(AUDIO_PROCESSING_PATTERN)
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        var bounds: Rect? = null
        while (System.currentTimeMillis() < deadline && bounds == null) {
            // Resource namespaces differ between debug and benchmark variants. The id itself is
            // unique on this preference screen, so match the fully-qualified resource id.
            val seekbar = device.findObject(By.res(SEEK_BAR_RESOURCE))
                ?: device.findObject(By.clazz("android.widget.SeekBar"))
            if (seekbar != null) {
                bounds = try {
                    seekbar.visibleBounds
                } catch (_: RuntimeException) {
                    // Preference rows are briefly recreated while the settings fragment settles.
                    null
                }
            }
            if (bounds == null) Thread.sleep(50)
        }
        val seekbarBounds = checkNotNull(bounds) { "Buffer size slider did not appear" }
        val fraction = ((bufferSize - BUFFER_MIN).toFloat() / (BUFFER_MAX - BUFFER_MIN))
            .coerceIn(0f, 1f)
        val targetX = seekbarBounds.left + (seekbarBounds.width() * fraction).roundToInt()
        device.swipe(seekbarBounds.left + 2, seekbarBounds.centerY(), targetX, seekbarBounds.centerY(), 10)
        val valueDeadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        var selectedValue: String? = null
        while (System.currentTimeMillis() < valueDeadline) {
            selectedValue = device.findObject(By.res(targetPackage, "seekbar_value"))?.text
            if (selectedValue?.trim()?.startsWith(bufferSize.toString()) == true) break
            Thread.sleep(50)
        }
        check(selectedValue?.trim()?.startsWith(bufferSize.toString()) == true) {
            "Buffer size slider selected '$selectedValue', expected $bufferSize"
        }
        device.pressBack()
        waitForPowerToggle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickText(pattern: Pattern) {
        checkNotNull(device.wait(Until.findObject(By.text(pattern)), UI_TIMEOUT_MS)) {
            "Preference option did not appear: $pattern"
        }.click()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.grantPermission(permission: String) {
        device.executeShellCommand("pm grant $targetPackage $permission")
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.confirmMediaProjection() {
        val button = device.wait(Until.findObject(By.res("android", "button1")), 2_000)
            ?: device.wait(Until.findObject(By.res("com.android.systemui", "button_start")), 2_000)
            ?: device.wait(
                Until.findObject(By.text(Pattern.compile("Start now|Start|今すぐ開始|開始", Pattern.CASE_INSENSITIVE))),
                UI_TIMEOUT_MS,
            )
        if (button == null) fail("MediaProjection confirmation button did not appear")
        button.click()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForRootlessService() {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (device.executeShellCommand("dumpsys activity services $targetPackage")
                    .contains("RootlessAudioProcessorService")) return
            Thread.sleep(100)
        }
        fail("RootlessAudioProcessorService did not start")
    }

    private enum class PcmEncoding(val audioEncoding: Int) {
        Float(AudioFormat.ENCODING_PCM_FLOAT),
        Short(AudioFormat.ENCODING_PCM_16BIT),
    }

    private class CapturableTone(private val encoding: PcmEncoding) {
        private val running = AtomicBoolean(false)
        private var track: AudioTrack? = null
        private var writer: Thread? = null

        fun start() {
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(encoding.audioEncoding)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                .build()
            val bytesPerSample = if (encoding == PcmEncoding.Float) Float.SIZE_BYTES else Short.SIZE_BYTES
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, encoding.audioEncoding)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, BLOCK_SAMPLES * 2 * bytesPerSample))
                .build()
            val audioTrack = checkNotNull(track)
            val floatBlock = FloatArray(BLOCK_SAMPLES * 2) { i ->
                sin(2.0 * PI * 440.0 * (i / 2) / SAMPLE_RATE).toFloat() * 0.1f
            }
            val shortBlock = ShortArray(floatBlock.size) { i -> (floatBlock[i] * 32767f).toInt().toShort() }
            running.set(true)
            audioTrack.play()
            writer = Thread {
                while (running.get()) {
                    val written = if (encoding == PcmEncoding.Float) {
                        audioTrack.write(floatBlock, 0, floatBlock.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        audioTrack.write(shortBlock, 0, shortBlock.size, AudioTrack.WRITE_BLOCKING)
                    }
                    if (written < 0) break
                }
            }.also { it.start() }
        }

        fun stop() {
            running.set(false)
            writer?.interrupt()
            writer?.join(1_000)
            writer = null
            track?.runCatching { stop() }
            track?.release()
            track = null
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK_SAMPLES = 8192
        const val WARMUP_MS = 5_000L
        const val MEASURE_MS = 10_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val targetPackage = BuildConfig.TARGET_PACKAGE
        val traceModes = listOf(
            TraceSectionMetric.Mode.Average to "average",
            TraceSectionMetric.Mode.Max to "max",
            TraceSectionMetric.Mode.Sum to "sum",
            TraceSectionMetric.Mode.Count to "count",
        )
        // The standard scenario keeps Car Audio disabled. TraceSectionMetric omits a metric
        // entirely when a section has zero occurrences, which crashes the macrobenchmark
        // result formatter; the car section remains available in the captured Perfetto trace.
        fun buildTraceMetrics(carAudioEnabled: Boolean): List<TraceSectionMetric> =
            (listOf("read", "engine", "write") + if (carAudioEnabled) listOf("car") else emptyList()).flatMap { section ->
            traceModes.map { (mode, label) ->
                TraceSectionMetric("JDSP.$section", mode, "${section}_$label")
            }
        }
        val AUDIO_PROCESSING_PATTERN = Pattern.compile("Audio processing|オーディオ処理", Pattern.CASE_INSENSITIVE)
        val AUDIO_ENCODING_PATTERN = Pattern.compile("Audio encoding|音声エンコーディング", Pattern.CASE_INSENSITIVE)
        val PCM_SHORT_PATTERN = Pattern.compile("16-bit integer PCM|16ビット整数 PCM", Pattern.CASE_INSENSITIVE)
        val CAR_COMPRESSOR_TITLE_PATTERN = Pattern.compile("Three-band compressor|3バンドコンプレッサー", Pattern.CASE_INSENSITIVE)
        val SWITCH_RESOURCE = Pattern.compile(".+:id/switchWidget")
        const val BUFFER_MIN = 128
        const val BUFFER_MAX = 16384
        val SEEK_BAR_RESOURCE = Pattern.compile(".+:id/seekbar")
    }
}
