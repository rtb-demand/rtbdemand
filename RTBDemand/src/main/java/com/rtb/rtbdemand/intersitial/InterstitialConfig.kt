package com.rtb.rtbdemand.intersitial

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.rtb.rtbdemand.sdk.SDKConfig
import java.io.Serializable

@Keep
internal data class InterstitialConfig(
        @SerializedName("customUnitName")
        var customUnitName: String = "",
        @SerializedName("isNewUnit")
        var isNewUnit: Boolean = false,
        @SerializedName("position")
        var position: Int = 0,
        @SerializedName("retryConfig")
        var retryConfig: SDKConfig.RetryConfig? = null,
        @SerializedName("newUnit")
        var newUnit: SDKConfig.LoadConfig? = null,
        @SerializedName("hijack")
        var hijack: SDKConfig.LoadConfig? = null,
        @SerializedName("unFilled")
        var unFilled: SDKConfig.LoadConfig? = null,
        @SerializedName("placement")
        var placement: SDKConfig.Placement? = null,
        @SerializedName("format")
        var format: String? = null
) : Serializable {
        @Keep
        fun isNewUnitApplied() = isNewUnit && newUnit?.status == 1
}

@Keep
internal data class SilentInterstitialConfig(
        @SerializedName("active")
        val activePercentage: Int? = null,
        @SerializedName("adunit")
        val adunit: String? = null,
        @SerializedName("custom")
        val custom: Int? = null,
        @SerializedName("rewarded")
        val rewarded: Int? = null,
        @SerializedName("timer")
        val timer: Int? = null,
        @SerializedName("close_delay")
        val closeDelay: Int? = null,
        @SerializedName("load_frequency")
        val loadFrequency: Int? = null,
        @SerializedName("regions")
        val regionConfig: SDKConfig.Regions? = null,
        @SerializedName("sizes")
        val bannerSizes: List<SDKConfig.Size>? = null
) : Serializable
