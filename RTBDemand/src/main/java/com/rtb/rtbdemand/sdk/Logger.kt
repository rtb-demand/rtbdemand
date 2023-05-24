package com.rtb.rtbdemand.sdk

import android.util.Log
import com.rtb.rtbdemand.common.LogLevel
import com.rtb.rtbdemand.common.TAG


internal fun LogLevel.log(msg: String) {
    if (!RTBDemand.logEnabled()) return
    when (this) {
        LogLevel.INFO -> Log.i(TAG, msg)
        LogLevel.DEBUG -> Log.d(TAG, msg)
        LogLevel.ERROR -> Log.e(TAG, msg)
    }
}