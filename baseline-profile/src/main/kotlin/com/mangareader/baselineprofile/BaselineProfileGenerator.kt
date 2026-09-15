package com.mangareader.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(TARGET_PACKAGE_NAME) {
            pressHome()
            startActivityAndWait()

            device.waitForIdle()
            device.wait(Until.hasObject(By.scrollable(true)), 5_000)

            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                repeat(5) {
                    scrollable.scrollForward()
                    device.waitForIdle()
                }
                repeat(5) {
                    scrollable.scrollBackward()
                    device.waitForIdle()
                }
            }
        }
    }
}

private fun UiDevice.waitAndClick(by: BySelector) {
    wait(Until.findObject(by), 60_000).click()
}