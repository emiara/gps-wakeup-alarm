package dev.emiara.gpswakeup

import android.app.Application

class WakeupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
