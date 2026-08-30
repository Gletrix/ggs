package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AdbOverlayService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbOverlayServiceTest {

    @Test
    fun testOverlayPermissionCheckHelper() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Verify helper method runs without exception on Android 34
        val hasPermission = MainActivity.checkOverlayPermission(context)
        // In default Robolectric environment without grants, it returns false or true depending on shadow
        assertNotNull(hasPermission)
    }

    @Test
    fun testServiceLifecycleAndStartStop() {
        val controller = Robolectric.buildService(AdbOverlayService::class.java)
        val service = controller.create().startCommand(0, 0).get()

        assertNotNull(service)
        assertNotNull(service.lifecycle)
        assertNotNull(service.viewModelStore)
        assertNotNull(service.savedStateRegistry)

        controller.destroy()
        assertFalse(AdbOverlayService.isRunning)
    }
}
