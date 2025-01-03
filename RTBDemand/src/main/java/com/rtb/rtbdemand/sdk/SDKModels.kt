package com.rtb.rtbdemand.sdk

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.rtb.rtbdemand.intersitial.SilentInterstitialConfig
import java.io.Serializable

@Keep
internal data class SDKConfig(
        @SerializedName("home_country")
        val homeCountry: String = "",
        @SerializedName("aff")
        val affiliatedId: Long? = null,
        @SerializedName("hb_format")
        val hbFormat: String? = null,
        @SerializedName("events")
        val events: Events? = null,
        @SerializedName("refetch")
        val refetch: Long? = null,
        @SerializedName("country_status")
        val countryStatus: CountryStatus? = null,
        @SerializedName("retry_config")
        val retryConfig: RetryConfig? = null,
        @SerializedName("unfilled_config")
        val unfilledTimerConfig: UnfilledConfig? = null,
        @SerializedName("info")
        val infoConfig: InfoConfig? = null,
        @SerializedName("prebid")
        val prebid: Prebid? = null,
        @SerializedName("aps")
        val aps: Aps? = null,
        @SerializedName("open_rtb")
        val openRTb: OpenRTBConfig? = null,
        @SerializedName("geoedge")
        val geoEdge: GeoEdge? = null,
        @SerializedName("tracking")
        val trackingConfig: TrackingConfig? = null,
        @SerializedName("pubmatic")
        val openWrapConfig: OpenWrapConfig? = null,
        @SerializedName("network_block")
        val networkBlock: String? = null,
        @SerializedName("region_block")
        val blockedRegions: Regions? = null,
        @SerializedName("diff")
        val difference: Int? = null,
        @SerializedName("network")
        val networkId: String? = null,
        @SerializedName("networkcode")
        val networkCode: String? = null,
        @SerializedName("global")
        val switch: Int? = null,
        @SerializedName("seemless_refresh")
        val seemlessRefresh: Int? = null,
        @SerializedName("seemless_refresh_fallback")
        val seemlessRefreshFallback: Int? = null,
        @SerializedName("instant_refresh")
        val instantRefresh: Int? = null,
        @SerializedName("active")
        var activeRefreshInterval: Int? = null,
        @SerializedName("passive")
        var passiveRefreshInterval: Int? = null,
        @SerializedName("factor")
        val factor: Int? = null,
        @SerializedName("active_factor")
        val visibleFactor: Int? = null,
        @SerializedName("min_view")
        val minView: Int? = null,
        @SerializedName("min_view_rtb")
        val minViewRtb: Int? = null,
        @SerializedName("force_impression")
        val forceImpression: Int? = null,
        @SerializedName("detect_detach")
        val detectDetach: Int? = null,
        @SerializedName("unified_banner")
        val unifiedBanner: Int? = null,
        @SerializedName("config")
        val refreshConfig: List<RefreshConfig>? = null,
        @SerializedName("silent_interstitial_config")
        val silentInterstitialConfig: SilentInterstitialConfig? = null,
        @SerializedName("block")
        private val block: List<List<String>?>? = null,
        @SerializedName("halt")
        val heldUnits: ArrayList<String>? = null,
        @SerializedName("regional_halts")
        val regionalHalts: ArrayList<Regions>? = null,
        @SerializedName("section_regional_halts")
        val sectionRegionalHalt: ArrayList<Regions>? = null,
        @SerializedName("hijack")
        val hijackConfig: LoadConfigs? = null,
        @SerializedName("unfilled")
        val unfilledConfig: LoadConfigs? = null,
        @SerializedName("supported_sizes")
        val supportedSizes: List<Size>? = null,
        @SerializedName("countries")
        val countryConfigs: List<CountryConfig>? = null,
        @SerializedName("fallback")
        val fallback: Fallback? = null,
        @SerializedName("native_fallback")
        val nativeFallback: Int? = null
) : Serializable {

    @Keep
    data class Regions(
            @SerializedName("cities")
            private val cities: String? = null,
            @SerializedName("states")
            private val states: String? = null,
            @SerializedName("countries")
            private val countries: String? = null,
            @SerializedName("mode")
            val mode: String? = null,
            @SerializedName("units")
            val units: ArrayList<String>? = null,
            @SerializedName("sections")
            val sections: ArrayList<String>? = null,
            @SerializedName("percentage")
            val percentage: Int? = null,
    ) : Serializable {
        fun getCities(): List<String> {
            return cities?.split(",")?.filter { it.isNotBlank() }?.map { it.trim() } ?: listOf()
        }

        fun getStates(): List<String> {
            return states?.split(",")?.filter { it.isNotBlank() }?.map { it.trim() } ?: listOf()
        }

        fun getCountries(): List<String> {
            return countries?.split(",")?.filter { it.isNotBlank() }?.map { it.trim() } ?: listOf()
        }
    }

    @Keep
    data class OpenWrapConfig(
            val playStoreUrl: String? = null
    ) : Serializable

    @Keep
    data class TrackingConfig(
            @SerializedName("percentage")
            val percentage: Int? = null,
            @SerializedName("script")
            private val script: String? = null
    ) : Serializable {
        fun getScript() = if (script.isNullOrEmpty()) {
            null
        } else {
            String.format("<script type=\"text/javascript\" src=\"%s\"></script>", script)
        }
    }

    @Keep
    data class Events(
            @SerializedName("self")
            val self: Int? = null,
            @SerializedName("other")
            val other: Int? = null,
            @SerializedName("oom")
            val oom: Int? = null,
            @SerializedName("sentry")
            val sentry: Int? = null
    ) : Serializable

    @Keep
    fun getBlockList() = arrayListOf<String>().apply {
        block?.forEach {
            it?.forEach { unit -> add(unit) }
        }
    }

    @Keep
    data class RetryConfig(
            @SerializedName("retries")
            var retries: Int? = null,
            @SerializedName("retry_interval")
            val retryInterval: Int? = null,
            @SerializedName("alternate_units")
            var adUnits: ArrayList<String> = arrayListOf()
    ) : Serializable

    @Keep
    data class UnfilledConfig(
            @SerializedName("time")
            val time: Int? = null,
            @SerializedName("unit")
            val unit: String? = null
    ) : Serializable

    @Keep
    data class InfoConfig(
            @SerializedName("normal_info")
            val normalInfo: Int? = null,
            @SerializedName("special_tag")
            val specialTag: String? = null,
            @SerializedName("refresh_callbacks")
            val refreshCallbacks: Int? = null
    ) : Serializable

    @Keep
    data class Prebid(
            @SerializedName("firstlook")
            val firstLook: Int? = null,
            @SerializedName("other")
            val other: Int? = null,
            @SerializedName("retry")
            val retry: Int? = null,
            @SerializedName("host")
            val host: String? = null,
            @SerializedName("accountid")
            val accountId: String? = null,
            @SerializedName("timeout")
            val timeout: String? = null,
            @SerializedName("debug")
            val debug: Int = 0,
            @SerializedName("location")
            val location: Int? = null,
            @SerializedName("gdpr")
            val gdpr: Int? = null,
            @SerializedName("bundle_name")
            val bundleName: String? = null,
            @SerializedName("domain")
            val domain: String? = null,
            @SerializedName("store_url")
            val storeURL: String? = null,
            @SerializedName("omid_partner_name")
            val omidPartnerName: String? = null,
            @SerializedName("omit_partner_version", alternate = ["omid_partner_version"])
            val omidPartnerVersion: String? = null,
            @SerializedName("key_values")
            val extParams: List<KeyValuePair>? = null,
            @SerializedName("banner_api_parameters")
            val bannerAPIParameters: List<Int>? = null,
            @SerializedName("whitelisted_formats")
            val whitelistedFormats: List<String>? = null
    ) : Serializable {
        @Keep
        data class KeyValuePair(
                @SerializedName("key")
                val key: String? = null,
                @SerializedName("value")
                val value: String? = null
        ) : Serializable
    }

    data class Aps(
            @SerializedName("firstlook")
            val firstLook: Int? = null,
            @SerializedName("other")
            val other: Int? = null,
            @SerializedName("retry")
            val retry: Int? = null,
            @SerializedName("timeout")
            val timeout: String? = null,
            @SerializedName("delay")
            val delay: String? = null,
            @SerializedName("app_key")
            val appKey: String? = null,
            @SerializedName("location")
            val location: Int? = null,
            @SerializedName("slots")
            val slots: List<Slot>? = null,
            @SerializedName("whitelisted_formats")
            val whitelistedFormats: List<String>? = null,
            @SerializedName("omid_partner_name")
            val omidPartnerName: String? = null,
            @SerializedName("omid_partner_version")
            val omidPartnerVersion: String? = null,
            @SerializedName("mraid_supported_versions")
            val mRaidSupportedVersions: List<String>? = null
    ) : Serializable {
        @Keep
        data class Slot(
                @SerializedName("width")
                var width: String? = null,
                @SerializedName("height")
                var height: String? = null,
                @SerializedName("slot_id")
                val slotId: String? = null
        ) : Serializable
    }

    @Keep
    data class GeoEdge(
            @SerializedName("firstlook")
            val firstLook: Int? = null,
            @SerializedName("other")
            val other: Int? = null,
            @SerializedName("api_key")
            val apiKey: String? = null,
            @SerializedName("creative_id")
            val creativeIds: String? = null,
            @SerializedName("reasons")
            val reasons: String? = null,
            @SerializedName("whitelist")
            val whitelistedRegions: Regions? = null
    ) : Serializable

    @Keep
    data class OpenRTBConfig(
            @SerializedName("percentage")
            val percentage: Int? = null,
            @SerializedName("inter_percentage")
            val interPercentage: Int? = null,
            @SerializedName("timeout")
            val timeout: Int? = null,
            @SerializedName("tagid")
            val tagId: String? = null,
            @SerializedName("pubid")
            val pubId: String? = null,
            @SerializedName("geocode")
            val geoCode: Int? = null,
            @SerializedName("url")
            val url: String? = null,
            @SerializedName("headers")
            val headers: List<KeyValuePair>? = null,
            @SerializedName("request")
            val request: String? = null
    ) : Serializable {
        @Keep
        data class KeyValuePair(
                @SerializedName("key")
                val key: String? = null,
                @SerializedName("value")
                val value: String? = null
        ) : Serializable
    }


    @Keep
    data class RefreshConfig(
            @SerializedName("type")
            val type: String? = null,
            @SerializedName("name_type")
            val nameType: String? = null,
            @SerializedName("format")
            val format: String? = null,
            @SerializedName("sizes")
            val sizes: List<Size>? = null,
            @SerializedName("follow")
            val follow: Int? = null,
            @SerializedName("pos")
            val position: Int? = null,
            @SerializedName("placement")
            val placement: Placement? = null,
            @SerializedName("specific")
            val specific: String? = null,
            @SerializedName("expiry")
            val expiry: Int? = null
    ) : Serializable

    @Keep
    data class Size(
            @SerializedName("width")
            val width: String? = null,
            @SerializedName("height")
            val height: String? = null,
            @SerializedName("sizes")
            val sizes: List<Size>? = null
    ) : Serializable {
        @Keep
        fun toSizes(): String {
            return sizes?.joinToString(",") ?: ""
        }

        @Keep
        override fun toString(): String {
            return String.format("%s x %s", width, height)
        }
    }

    @Keep
    data class Placement(
            @SerializedName("firstlook")
            val firstLook: String? = null,
            @SerializedName("other")
            val other: String? = null
    ) : Serializable

    @Keep
    data class LoadConfigs(
            @SerializedName("INTERSTITIAL")
            val inter: LoadConfig? = null,
            @SerializedName("REWARDEDINTERSTITIAL")
            val reward: LoadConfig? = null,
            @SerializedName("REWARDED")
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
            @SerializedName("APPOPEN")
            val appOpen: LoadConfig? = null,
            @SerializedName("ALL", alternate = ["all"])
            val other: LoadConfig? = null
    ) : Serializable

    @Keep
    data class LoadConfig(
            @SerializedName("status")
            val status: Int? = null,
            @SerializedName("per")
            val per: Int? = null,
            @SerializedName("number")
            val number: Int? = null,
            @SerializedName("region_wise")
            val regionWise: Int? = null,
            @SerializedName("region_wise_per")
            val regionalPercentage: List<Regions>? = null
    ) : Serializable

    @Keep
    data class CountryStatus(
            @SerializedName("active")
            val active: Int? = null,
            @SerializedName("url")
            val url: String? = null
    ) : Serializable

    @Keep
    data class CountryConfig(
            @SerializedName("name")
            val name: String? = null,
            @SerializedName("diff")
            val diff: Int? = null,
            @SerializedName("active")
            val activeRefreshInterval: Int? = null,
            @SerializedName("passive")
            val passiveRefreshInterval: Int? = null,
            @SerializedName("factor")
            val factor: Int? = null,
            @SerializedName("active_factor")
            val visibleFactor: Int? = null,
            @SerializedName("min_view")
            val minView: Int? = null,
            @SerializedName("min_view_rtb")
            val minViewRtb: Int? = null,
            @SerializedName("hijack")
            val hijackConfig: LoadConfigs? = null,
            @SerializedName("unfilled")
            val unfilledConfig: LoadConfigs? = null,
            @SerializedName("config")
            val refreshConfig: List<RefreshConfig>? = null,
            @SerializedName("supported_sizes")
            val supportedSizes: List<Size>? = null,
            @SerializedName("fallback")
            val fallback: Fallback? = null,
            @SerializedName("geoedge")
            val geoEdge: GeoEdge? = null,
            @SerializedName("native_fallback")
            val nativeFallback: Int? = null
    ) : Serializable
}

@Keep
data class Fallback(
        @SerializedName("firstlook")
        val firstlook: Int? = null,
        @SerializedName("other")
        val other: Int? = null,
        @SerializedName("banners")
        val banners: List<Banner>? = null
) : Serializable {
    @Keep
    data class Banner(
            @SerializedName("width")
            var width: String? = null,
            @SerializedName("height")
            var height: String? = null,
            @SerializedName("type")
            val type: String? = null,
            @SerializedName("image")
            val image: String? = null,
            @SerializedName("script")
            val script: String? = null,
            @SerializedName("url")
            val url: String? = null,
            @SerializedName("tag")
            private val tag: String? = null
    ) : Serializable {
        fun getScriptSource() = if (tag.isNullOrEmpty())
            null
        else
            String.format("<SCRIPT language='JavaScript1.1' SRC=\"%s\" attributionsrc ></SCRIPT>", tag)
    }
}


@Keep
data class CountryModel(
        @SerializedName("countryCode", alternate = ["country"])
        val countryCode: String? = null,
        @SerializedName("latitude", alternate = ["lat"])
        val latitude: Double? = null,
        @SerializedName("longitude", alternate = ["lon"])
        val longitude: Double? = null,
        @SerializedName("city")
        val city: String? = null,
        @SerializedName("regionCode", alternate = ["state"])
        val state: String? = null,
        @SerializedName("zip", alternate = ["postalCode"])
        val zip: String? = null,
        @SerializedName("ip")
        val ip: String? = null
) : Serializable