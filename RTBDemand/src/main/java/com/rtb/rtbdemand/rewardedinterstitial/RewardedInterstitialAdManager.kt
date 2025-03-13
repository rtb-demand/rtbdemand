package com.rtb.rtbdemand.rewardedinterstitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.appharbr.sdk.engine.AdBlockReason
import com.appharbr.sdk.engine.AdSdk
import com.appharbr.sdk.engine.AppHarbr
import com.appharbr.sdk.engine.adformat.AdFormat
import com.appharbr.sdk.engine.listeners.AHIncident
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import com.google.gson.Gson
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.intersitial.InterstitialConfig
import com.rtb.rtbdemand.sdk.ConfigFetch
import com.rtb.rtbdemand.sdk.ConfigProvider
import com.rtb.rtbdemand.sdk.RTBDemand
import com.rtb.rtbdemand.sdk.SDKConfig
import com.rtb.rtbdemand.sdk.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.prebid.mobile.InterstitialAdUnit
import org.prebid.mobile.api.data.AdUnitFormat
import java.util.EnumSet
import java.util.Locale

internal class RewardedInterstitialAdManager(private val context: Activity, private val adUnit: String) {

    private var sdkConfig: SDKConfig? = null
    private var config: InterstitialConfig = InterstitialConfig()
    private var shouldBeActive: Boolean = false
    private var firstLook: Boolean = true
    private var overridingUnit: String? = null
    private var otherUnit = false

    init {
        RTBDemand.registerActivity(context)
        sdkConfig = ConfigProvider.getConfig(context)
        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
    }

    fun load(adRequest: AdRequest, callBack: (rewardedInterstitialAd: RewardedInterstitialAd?) -> Unit) {
        var adManagerAdRequest = adRequest.getAdRequest()
        if (adManagerAdRequest == null) {
            callBack(null)
            return
        }
        shouldSetConfig {
            if (it) {
                setConfig()
                if (config.isNewUnitApplied()) {
                    adUnit.log { "new unit override on $adUnit" }
                    createRequest().getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(getAdUnitName(false, hijacked = false, newUnit = true), request, callBack)
                    }
                } else if (checkHijack(config.hijack)) {
                    adUnit.log { "hijack override on $adUnit" }
                    createRequest(hijacked = true).getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(getAdUnitName(false, hijacked = true, newUnit = false), request, callBack)
                    }
                } else {
                    loadAd(adUnit, adManagerAdRequest!!, callBack)
                }
            } else {
                loadAd(adUnit, adManagerAdRequest!!, callBack)
            }
        }
    }

    private fun checkHijack(hijackConfig: SDKConfig.LoadConfig?): Boolean {
        return if (hijackConfig?.status == 1) {
            val number = (1..100).random()
            number in 1..(hijackConfig.per ?: 100)
        } else {
            false
        }
    }

    private fun loadAd(adUnit: String, adRequest: AdManagerAdRequest, callBack: (rewardedInterstitialAd: RewardedInterstitialAd?) -> Unit) {
        otherUnit = adUnit != this.adUnit
        this.adUnit.log { "loading $adUnit by Rewarded Interstitial" }
        fetchDemand(adRequest) {
            RewardedInterstitialAd.load(context, adUnit, adRequest, object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedInterstitialAd) {
                    this@RewardedInterstitialAdManager.adUnit.log { "loaded $adUnit by GAM" }
                    config.retryConfig = sdkConfig?.retryConfig
                    addGeoEdge(ad, firstLook)
                    callBack(ad)
                    firstLook = false
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    this@RewardedInterstitialAdManager.adUnit.log { "loading $adUnit failed by GAM with error : ${adError.message}" }
                    val tempStatus = firstLook
                    if (firstLook) {
                        firstLook = false
                    }
                    try {
                        adFailedToLoad(tempStatus, callBack)
                    } catch (_: Throwable) {
                        callBack(null)
                    }
                }
            })
        }
    }

    private fun addGeoEdge(rewarded: RewardedInterstitialAd, otherUnit: Boolean) {
        try {
            val number = (1..100).random()
            if ((!otherUnit && (number in 1..(sdkConfig?.geoEdge?.firstLook ?: 0))) ||
                    (otherUnit && (number in 1..(sdkConfig?.geoEdge?.other ?: 0)))) {
                AppHarbr.addRewardedInterstitialAd(AdSdk.GAM, rewarded, object : AHIncident {
                    override fun onAdBlocked(p0: Any?, p1: String?, p2: AdFormat, reasons: Array<out AdBlockReason>) {
                        adUnit.log { "RewardedInterstitialAd : onAdBlocked : ${Gson().toJson(reasons.asList().map { it.reason })}" }
                    }

                    override fun onAdIncident(p0: Any?, p1: String?, p2: AdSdk?, p3: String?, p4: AdFormat, p5: Array<out AdBlockReason>, reportReasons: Array<out AdBlockReason>) {
                        adUnit.log { "RewardedInterstitialAd: onAdIncident : ${Gson().toJson(reportReasons.asList().map { it.reason })}" }
                    }
                })
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun adFailedToLoad(firstLook: Boolean, callBack: (rewardedInterstitialAd: RewardedInterstitialAd?) -> Unit) {
        adUnit.log { "Failed with Unfilled Config: ${Gson().toJson(config.unFilled)} && Retry config : ${Gson().toJson(config.retryConfig)}" }
        fun requestAd() {
            config.isNewUnit = false
            createRequest(unfilled = true).getAdRequest()?.let {
                loadAd(getAdUnitName(unfilled = true, hijacked = false, newUnit = false), it, callBack)
            }
        }
        if (shouldBeActive) {
            if (config.unFilled?.status == 1) {
                if (firstLook && !config.isNewUnitApplied()) {
                    requestAd()
                } else {
                    if ((config.retryConfig?.retries ?: 0) > 0) {
                        config.retryConfig?.retries = (config.retryConfig?.retries ?: 0) - 1
                        Handler(Looper.getMainLooper()).postDelayed({
                            config.retryConfig?.adUnits?.firstOrNull()?.let {
                                config.retryConfig?.adUnits?.removeAt(0)
                                overridingUnit = it
                                requestAd()
                            } ?: kotlin.run {
                                overridingUnit = null
                                callBack(null)
                            }
                        }, (config.retryConfig?.retryInterval ?: 0).toLong() * 1000)
                    } else {
                        overridingUnit = null
                        callBack(null)
                    }
                }
            } else {
                callBack(null)
            }
        } else {
            callBack(null)
        }
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun shouldSetConfig(callback: (Boolean) -> Unit) = CoroutineScope(Dispatchers.Main).launch {
        ConfigProvider.configStatus.collect {
            if (it is ConfigFetch.Completed) {
                sdkConfig = it.config
                shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
                callback(shouldBeActive)
            }
        }
    }

    private fun setConfig() {
        adUnit.log { String.format("%s:%s- Version:%s", "setConfig", "entry", BuildConfig.ADAPTER_VERSION) }
        if (!shouldBeActive) return
        if (sdkConfig?.getBlockList()?.contains(adUnit) == true) {
            shouldBeActive = false
            return
        }
        val validConfig = sdkConfig?.refreshConfig?.firstOrNull { config -> config.specific?.equals(adUnit, true) == true || config.type == AdTypes.REWARDEDINTERSTITIAL || config.type.equals("all", true) }
        if (validConfig == null) {
            shouldBeActive = false
            return
        }
        val networkName = if (sdkConfig?.networkCode.isNullOrEmpty()) sdkConfig?.networkId else String.format("%s,%s", sdkConfig?.networkId, sdkConfig?.networkCode)
        config.apply {
            customUnitName = String.format("/%s/%s-%s", networkName, sdkConfig?.affiliatedId.toString(), validConfig.nameType ?: "")
            position = validConfig.position ?: 0
            isNewUnit = adUnit.contains(sdkConfig?.networkId ?: "")
            placement = validConfig.placement
            newUnit = sdkConfig?.hijackConfig?.newUnit
            retryConfig = sdkConfig?.retryConfig
            hijack = sdkConfig?.hijackConfig?.reward ?: sdkConfig?.hijackConfig?.other
            unFilled = sdkConfig?.unfilledConfig?.reward ?: sdkConfig?.unfilledConfig?.other
        }
        adUnit.log { "setConfig :$config" }
    }

    private fun getAdUnitName(unfilled: Boolean, hijacked: Boolean, newUnit: Boolean): String {
        return overridingUnit ?: String.format(Locale.ENGLISH, "%s-%d", config.customUnitName, if (unfilled) config.unFilled?.number else if (newUnit) config.newUnit?.number else if (hijacked) config.hijack?.number else config.position)
    }

    private fun createRequest(unfilled: Boolean = false, hijacked: Boolean = false) = AdRequest().Builder().apply {
        addCustomTargeting("adunit", adUnit)
        addCustomTargeting("hb_format", sdkConfig?.hbFormat ?: "amp")
        if (unfilled) addCustomTargeting("retry", "1")
        if (hijacked) addCustomTargeting("hijack", "1")
    }.build()

    private fun fetchDemand(adRequest: AdManagerAdRequest, callback: () -> Unit) {
        if (sdkConfig?.prebid?.whitelistedFormats != null && sdkConfig?.prebid?.whitelistedFormats?.contains(AdTypes.REWARDEDINTERSTITIAL) == false) {
            callback()
            return
        }

        if ((config.isNewUnitApplied() && sdkConfig?.prebid?.other == 1) ||
                (otherUnit && !config.isNewUnitApplied() && sdkConfig?.prebid?.retry == 1) ||
                (!otherUnit && sdkConfig?.prebid?.firstLook == 1)
        ) {
            adUnit.log { "Fetch Demand with prebid" }
            val adUnit = InterstitialAdUnit((if (otherUnit) config.placement?.other ?: 0 else config.placement?.firstLook ?: 0).toString(), EnumSet.of(AdUnitFormat.VIDEO))
            adUnit.fetchDemand(adRequest) { callback() }
        } else {
            callback()
        }
    }
}