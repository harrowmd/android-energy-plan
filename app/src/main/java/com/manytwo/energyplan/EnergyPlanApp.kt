package com.manytwo.energyplan

import android.app.Application

class EnergyPlanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // As early as possible, so the crash handler covers everything.
        AppLog.init(this)
    }
}
