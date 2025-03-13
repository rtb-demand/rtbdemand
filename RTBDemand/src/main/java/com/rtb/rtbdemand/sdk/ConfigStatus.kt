package com.rtb.rtbdemand.sdk

internal sealed class ConfigFetch {
    class NotStarted : ConfigFetch()
    class Loading : ConfigFetch()
    class Completed(val config: SDKConfig?) : ConfigFetch()
}