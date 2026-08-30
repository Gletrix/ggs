package com.example

import android.app.Application
import android.os.Build
import org.conscrypt.Conscrypt
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.security.Security

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Bypass Android Hidden API reflection restrictions
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        } catch (e: Throwable) {
            // Robolectric tests may fail here, ignore
        }

        // 2. Install bundled Conscrypt provider at position 1
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (e: Throwable) {
            // Ignore for Robolectric
        }
    }
}
