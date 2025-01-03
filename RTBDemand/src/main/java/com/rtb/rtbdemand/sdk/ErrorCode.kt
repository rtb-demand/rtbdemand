package com.rtb.rtbdemand.sdk

import androidx.annotation.Keep

/**
 * Error codes for reasons why an ad request may fail.
 */
@Keep
enum class ErrorCode {
    UNKNOWN, BAD_REQUEST, NETWORK_ERROR, NO_INVENTORY
}