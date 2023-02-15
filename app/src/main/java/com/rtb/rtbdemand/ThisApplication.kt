package com.rtb.rtbdemand

import android.app.Application
import com.rtb.rtbdemand.sdk.RTBDemand

class ThisApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        RTBDemand.initialize(this)
    }
}