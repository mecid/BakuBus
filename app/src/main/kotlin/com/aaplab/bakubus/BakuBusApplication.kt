package com.aaplab.bakubus

import android.app.Application
import timber.log.Timber

/**
 * Created by user on 22.02.16.
 */
class BakuBusApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG)
            Timber.plant(Timber.DebugTree())
    }
}