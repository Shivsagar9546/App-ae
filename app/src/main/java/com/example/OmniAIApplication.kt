package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.local.AppDatabase
import com.example.data.preferences.AdminPreferencesRepository

class OmniAIApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var adminPreferences: AdminPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getDatabase(this)
        adminPreferences = AdminPreferencesRepository(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_FLOATING_SERVICE,
                "Floating Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status while Floating AI Assistant is active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_FLOATING_SERVICE = "channel_floating_ai_service"
        lateinit var instance: OmniAIApplication
            private set
    }
}
