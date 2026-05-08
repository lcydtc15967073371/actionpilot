package com.operit.actionpilot

import android.app.Application
import com.operit.actionpilot.storage.MapRepository

class ActionPilotApp : Application() {

    lateinit var repository: MapRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = MapRepository(this)
    }
}
