package com.rtb.rtbdemand.sdk

import androidx.annotation.Keep

@Keep
data class RTBError(
        val code: Int = 0,
        val message: String = "",
        val extras: Any? = null
)
