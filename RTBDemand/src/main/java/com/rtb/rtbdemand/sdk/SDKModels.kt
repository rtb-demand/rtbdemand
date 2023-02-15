package com.rtb.rtbdemand.sdk

import com.google.gson.annotations.SerializedName


internal data class SDKConfig(
        @SerializedName("aff")
        val affiliatedId: Long? = null,
        val prebid: Prebid? = null,
        @SerializedName("diff")
        val difference: Int? = null,
        @SerializedName("network")
        val networkId: String? = null,
        @SerializedName("networkcode")
        val networkCode: String? = null,
        @SerializedName("global")
        val switch: Int? = null,
        @SerializedName("active")
        var activeRefreshInterval: Int? = null,
        @SerializedName("passive")
        var passiveRefreshInterval: Int? = null,
        val factor: Int? = null,
        @SerializedName("min_view")
        val minView: Int? = null,
        @SerializedName("min_view_rtb")
        val minViewRtb: Int? = null,
        @SerializedName("config")
        val refreshConfig: List<RefreshConfig>? = null,
        @SerializedName("block")
        private val block: List<List<String>?>? = null,
        @SerializedName("hijack")
        val hijackConfig: LoadConfigs? = null,
        @SerializedName("unfilled")
        val unfilledConfig: LoadConfigs? = null
) {

    fun getBlockList() = arrayListOf<String>().apply {
        block?.forEach {
            it?.forEach { unit -> add(unit) }
        }
    }

    data class Prebid(
            @SerializedName("firstlook")
            val firstLook: Int? = null,
            val other: Int? = null,
            val host: String? = null,
            @SerializedName("accountid")
            val accountId: String? = null
    )

    data class RefreshConfig(
            val type: String? = null,
            @SerializedName("name_type")
            val nameType: String? = null,
            val sizes: List<Size>? = null,
            val follow: Int? = null,
            @SerializedName("pos")
            val position: Int? = null,
            val placement: Placement? = null,
            val specific: String? = null
    )

    data class Size(
            val width: String? = null,
            val height: String? = null,
            val sizes: List<Size>? = null
    ) {
        fun toSizes(): String {
            return sizes?.joinToString(",") ?: ""
        }

        override fun toString(): String {
            return String.format("%s x %s", width, height)
        }
    }

    data class Placement(
            @SerializedName("firstlook")
            val firstLook: String? = null,
            val other: String? = null
    )

    data class LoadConfigs(
            @SerializedName("INTER")
            val inter: LoadConfig? = null,
            @SerializedName("REWARD")
            val reward: LoadConfig? = null,
            @SerializedName("REWARDV")
            val rewardVideos: LoadConfig? = null,
            @SerializedName("NATIVE")
            val native: LoadConfig? = null,
            @SerializedName("newunit")
            val newUnit: LoadConfig? = null,
            @SerializedName("BANNER")
            val banner: LoadConfig? = null,
            @SerializedName("ADAPTIVE")
            val adaptive: LoadConfig? = null,
            @SerializedName("INLINE")
            val inline: LoadConfig? = null,
            @SerializedName("INREAD")
            val inread: LoadConfig? = null,
            @SerializedName("STICKY")
            val sticky: LoadConfig? = null,
            @SerializedName("ALL")
            val other: LoadConfig? = null
    )

    data class LoadConfig(
            val status: Int? = null,
            val number: Int? = null
    )
}