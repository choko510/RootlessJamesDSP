package me.timschneeberger.rootlessjamesdsp.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generates the startup and first-scroll profile consumed by every app flavor. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndCardScroll() = baselineProfileRule.collect(
        packageName = targetPackage,
        includeInStartupProfile = true,
        maxIterations = 10,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.res(targetPackage, "dsp_scrollview")), 5_000)
        device.findObject(By.res(targetPackage, "dsp_scrollview"))?.fling(Direction.DOWN)
    }

    private companion object {
        val targetPackage: String
            get() = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .packageName
    }
}
