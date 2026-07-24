package me.timschneeberger.rootlessjamesdsp.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Critical user journeys for rootlessFullRelease. StartupTimingMetric exposes both TTID and
 * TTFD because MainActivity calls reportFullyDrawn() after the staged card list is rendered.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )

    @Test
    fun hotStartup() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.HOT,
        measureBlock = { startActivityAndWait() },
    )

    @Test
    fun coldStartupWithBaselineProfile() = measureCold(CompilationMode.Partial(
        baselineProfileMode = BaselineProfileMode.Require,
    ))

    @Test
    fun coldStartupWithoutBaselineProfile() = measureCold(CompilationMode.None())

    @Test
    fun cardListScroll() = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(
                androidx.test.uiautomator.Until.hasObject(By.res(targetPackage, "dsp_scrollview")),
                5_000,
            )
        },
        measureBlock = {
            device.findObject(By.res(targetPackage, "dsp_scrollview"))
                ?.fling(Direction.DOWN)
        },
    )

    private fun measureCold(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )

    private companion object {
        const val targetPackage = BuildConfig.TARGET_PACKAGE
    }
}
