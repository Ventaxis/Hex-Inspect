package com.ventaxiscorp.hexinspect

import android.app.Application

class HexApp : Application() {
    lateinit var engine: HexEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = HexEngine(this)
    }
}
