package com.rtb.rtbdemand.rewardedinterstitial

import com.google.gson.annotations.SerializedName

data class Reward(
        @SerializedName("amount")
        val amount: Int,
        @SerializedName("type")
        val type: String
)
