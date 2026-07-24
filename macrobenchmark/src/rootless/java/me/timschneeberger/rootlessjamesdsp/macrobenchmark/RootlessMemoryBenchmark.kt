package me.timschneeberger.rootlessjamesdsp.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@OptIn(ExperimentalMetricApi::class, ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class RootlessMemoryBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun steadyStateMemoryFloat() = measureSteadyStateMemory(PcmEncoding.Float)

    @Test
    fun steadyStateMemoryShort() = measureSteadyStateMemory(PcmEncoding.Short)

    private fun measureSteadyStateMemory(encoding: PcmEncoding) {
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Last,
                    subMetrics = memorySubMetrics,
                ),
                MemoryUsageMetric(
                    mode = MemoryUsageMetric.Mode.Max,
                    subMetrics = memorySubMetrics,
                ),
            ),
            compilationMode = CompilationMode.Ignore(),
            iterations = 5,
            startupMode = null,
            setupBlock = {
                device.executeShellCommand("pm clear $targetPackage")
                grantPermission("android.permission.DUMP")
                grantPermission("android.permission.RECORD_AUDIO")
                grantPermission("android.permission.POST_NOTIFICATIONS")

                pressHome()
                device.executeShellCommand("am force-stop $targetPackage")
                startActivityAndWait()
                waitForPowerToggle()
                if (encoding == PcmEncoding.Short) {
                    selectShortEncoding()
                }
                device.findObject(By.res(targetPackage, "power_toggle")).click()
                confirmMediaProjection()
                waitForRootlessService()
                device.pressBack()
                device.executeShellCommand("am send-trim-memory $targetPackage COMPLETE")
                Thread.sleep(1_000)
            },
            measureBlock = {
                Thread.sleep(STEADY_STATE_MS)
            },
        )
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForPowerToggle() {
        if (device.wait(Until.hasObject(By.res(targetPackage, "power_toggle")), 2_000)) return
        device.pressBack()
        check(
            device.wait(
                Until.hasObject(By.res(targetPackage, "power_toggle")),
                UI_TIMEOUT_MS,
            ),
        ) { "Rootless power toggle did not appear" }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.selectShortEncoding() {
        val settings = checkNotNull(
            device.wait(
                Until.findObject(By.res(targetPackage, "action_settings")),
                UI_TIMEOUT_MS,
            ),
        ) { "Settings button did not appear" }
        settings.click()

        clickText(AUDIO_PROCESSING_PATTERN, "Audio processing setting")
        clickText(AUDIO_ENCODING_PATTERN, "Audio encoding setting")
        clickText(PCM_SHORT_PATTERN, "16-bit PCM option")

        device.pressBack()
        device.pressBack()
        waitForPowerToggle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickText(
        pattern: Pattern,
        description: String,
    ) {
        val item = checkNotNull(
            device.wait(
                Until.findObject(By.text(pattern)),
                UI_TIMEOUT_MS,
            ),
        ) { "$description did not appear" }
        item.click()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.grantPermission(permission: String) {
        device.executeShellCommand("pm grant $targetPackage $permission")
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.confirmMediaProjection() {
        val permissionButton = device.wait(
            Until.findObject(By.res("android", "button1")),
            2_000,
        ) ?: device.wait(
            Until.findObject(By.res("com.android.systemui", "button_start")),
            2_000,
        ) ?: device.wait(
            Until.findObject(
                By.text(
                    Pattern.compile(
                        "Start now|Start|今すぐ開始|開始",
                        Pattern.CASE_INSENSITIVE,
                    ),
                ),
            ),
            UI_TIMEOUT_MS,
        )

        if (permissionButton == null) {
            fail("MediaProjection confirmation button did not appear")
        }
        permissionButton.click()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForRootlessService() {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val services = device.executeShellCommand(
                "dumpsys activity services $targetPackage",
            )
            if (services.contains("RootlessAudioProcessorService")) return
            Thread.sleep(100)
        }
        fail("RootlessAudioProcessorService did not start")
    }

    private companion object {
        enum class PcmEncoding {
            Float,
            Short,
        }

        const val UI_TIMEOUT_MS = 10_000L
        const val STEADY_STATE_MS = 5_000L

        val memorySubMetrics = listOf(
            MemoryUsageMetric.SubMetric.HeapSize,
            MemoryUsageMetric.SubMetric.RssAnon,
        )

        val AUDIO_PROCESSING_PATTERN = Pattern.compile(
            "Audio processing|オーディオ処理",
            Pattern.CASE_INSENSITIVE,
        )
        val AUDIO_ENCODING_PATTERN = Pattern.compile(
            "Audio encoding|音声エンコーディング",
            Pattern.CASE_INSENSITIVE,
        )
        val PCM_SHORT_PATTERN = Pattern.compile(
            "16-bit integer PCM|16ビット整数 PCM",
            Pattern.CASE_INSENSITIVE,
        )

        const val targetPackage = BuildConfig.TARGET_PACKAGE
    }
}
