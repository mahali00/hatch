package dev.mahali.hatch.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs a paired host showing a tab named "Many panes QA" with several panes (another tab can be named
 * with the instrumentation argument hatch.tab). The app on the device must already be paired with that
 * host: the benchmark build is, and this variant installs over it with the same signature.
 * Run with ./gradlew :app:generateReleaseBaselineProfile
 * -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true (without it the app is uninstalled
 * afterwards, and its pairing with it).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    private val tab = InstrumentationRegistry.getArguments().getString("hatch.tab") ?: "Many panes QA"

    @Test fun generate() = rule.collect(packageName = "dev.mahali.hatch", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        // The sidebar may have been left hidden.
        device.wait(Until.findObject(By.desc("Show sidebar")), 2_000)?.click()
        runCatching { UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(tab) }
        device.clickLabel(tab)
        for (layout in listOf("Focus pane", "Fit all panes")) {
            device.clickWhenShown(By.desc("More"))
            device.clickLabel(layout)
        }
        device.clickWhenShown(By.desc("Hide sidebar"))
        device.clickWhenShown(By.desc("Show sidebar"))
    }

    /** A row's label is its text or its content description, depending on how Compose merged it. */
    private fun UiDevice.clickLabel(label: String) {
        val target = wait(Until.findObject(By.text(label)), 3_000) ?: wait(Until.findObject(By.desc(label)), 3_000)
        checkNotNull(target) { "not on screen: $label" }.click()
        waitForIdle()
    }

    private fun UiDevice.clickWhenShown(selector: BySelector) {
        val target = checkNotNull(wait(Until.findObject(selector), 5_000)) { "not on screen: $selector" }
        target.click()
        waitForIdle()
    }
}
