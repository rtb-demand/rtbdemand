package com.rtb.rtbdemand.intersitial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.amazon.device.ads.AdError
import com.amazon.device.ads.AdRegistration
import com.amazon.device.ads.DTBAdCallback
import com.amazon.device.ads.DTBAdRequest
import com.amazon.device.ads.DTBAdResponse
import com.amazon.device.ads.DTBAdSize
import com.amazon.device.ads.DTBAdUtil
import com.appharbr.sdk.engine.AdBlockReason
import com.appharbr.sdk.engine.AdSdk
import com.appharbr.sdk.engine.AppHarbr
import com.appharbr.sdk.engine.adformat.AdFormat
import com.appharbr.sdk.engine.listeners.AHIncident
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.admanager.AdManagerInterstitialAd
import com.google.android.gms.ads.admanager.AdManagerInterstitialAdLoadCallback
import com.google.gson.Gson
import com.pubmatic.sdk.common.POBError
import com.pubmatic.sdk.openwrap.eventhandler.dfp.DFPInterstitialEventHandler
import com.pubmatic.sdk.openwrap.interstitial.POBInterstitial
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.sdk.ConfigFetch
import com.rtb.rtbdemand.sdk.ConfigProvider
import com.rtb.rtbdemand.sdk.CountryModel
import com.rtb.rtbdemand.sdk.RTBDemand
import com.rtb.rtbdemand.sdk.RTBError
import com.rtb.rtbdemand.sdk.SDKConfig
import com.rtb.rtbdemand.sdk.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.prebid.mobile.InterstitialAdUnit
import org.prebid.mobile.Signals
import org.prebid.mobile.VideoParameters
import org.prebid.mobile.api.data.AdUnitFormat
import java.util.EnumSet
import java.util.Locale

internal class InterstitialAdManager(private val context: Activity, private val adUnit: String) {

    private var sdkConfig: SDKConfig? = null
    private var countryData: CountryModel? = null
    private var interstitialConfig: InterstitialConfig = InterstitialConfig()
    private var shouldBeActive: Boolean = false
    private var firstLook: Boolean = true
    private var overridingUnit: String? = null
    private var otherUnit = false
    var owBidSummary: Boolean? = null
    var owDebugState: Boolean? = null
    var owTestMode: Boolean? = null

    init {
        RTBDemand.registerActivity(context)
        sdkConfig = ConfigProvider.getConfig(context)
        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
        countryData = ConfigProvider.getDetectedCountry(context)
    }

    fun loadWithOW(pubID: String, profile: Int, owAdUnitId: String, configListener: DFPInterstitialEventHandler.DFPConfigListener?,
                   callBack1: (interstitialAd: POBInterstitial?) -> Unit, callBack2: (interstitialAd: AdManagerInterstitialAd?, error: RTBError?) -> Unit, listener: POBInterstitial.POBInterstitialListener) {
        shouldSetConfig {
            if (it) {
                setConfig()
                if (interstitialConfig.isNewUnitApplied()) {
                    adUnit.log { "new unit override on $adUnit" }
                    createRequest().getAdRequest()?.let { request ->
                        loadAd(getAdUnitName(false, hijacked = false, newUnit = true), request, callBack2)
                    }
                } else if (checkHijack(interstitialConfig.hijack)) {
                    adUnit.log { "hijack override on $adUnit" }
                    createRequest(hijacked = true).getAdRequest()?.let { request ->
                        loadAd(getAdUnitName(false, hijacked = true, newUnit = false), request, callBack2)
                    }
                } else {
                    loadOW(pubID, profile, owAdUnitId, configListener, callBack1, callBack2, listener)
                }
            } else {
                loadOW(pubID, profile, owAdUnitId, configListener, callBack1, callBack2, listener)
            }
        }
    }

    private fun loadOW(pubID: String, profile: Int, owAdUnitId: String, configListener: DFPInterstitialEventHandler.DFPConfigListener?,
                       callBack1: (interstitialAd: POBInterstitial?) -> Unit, callBack2: (interstitialAd: AdManagerInterstitialAd?, error: RTBError?) -> Unit, listener: POBInterstitial.POBInterstitialListener) {
        adUnit.log { "loading $adUnit by Pubmatic with pub id : $pubID, profile : $profile, owUnit : $owAdUnitId" }
        val eventHandler = DFPInterstitialEventHandler(context, adUnit)
        configListener?.let { eventHandler.setConfigListener(it) }
        val interstitial = POBInterstitial(context, pubID, profile, owAdUnitId, eventHandler)
        interstitial.setListener(object : POBInterstitial.POBInterstitialListener() {
            override fun onAdFailedToShow(p0: POBInterstitial, p1: POBError) {
                listener.onAdFailedToShow(p0, p1)
            }

            override fun onAppLeaving(p0: POBInterstitial) {
                listener.onAppLeaving(p0)
            }

            override fun onAdOpened(p0: POBInterstitial) {
                listener.onAdOpened(p0)
            }

            override fun onAdClosed(p0: POBInterstitial) {
                listener.onAdClosed(p0)
            }

            override fun onAdClicked(p0: POBInterstitial) {
                listener.onAdClicked(p0)
            }

            override fun onAdExpired(p0: POBInterstitial) {
                listener.onAdExpired(p0)
            }

            override fun onAdReceived(p0: POBInterstitial) {
                adUnit.log { "loaded $adUnit by pubmatic" }
                interstitialConfig.retryConfig = sdkConfig?.retryConfig
                addGeoEdge(AdSdk.PUBMATIC, p0, otherUnit)
                callBack1(p0)
                listener.onAdReceived(p0)
                firstLook = false
            }

            override fun onAdFailedToLoad(p0: POBInterstitial, p1: POBError) {
                adUnit.log { "loading $adUnit failed by pubmatic with error : ${p1.errorMessage}" }
                val tempStatus = firstLook
                if (firstLook) {
                    firstLook = false
                }
                val RTBError = RTBError(p1.errorCode, p1.errorMessage)
                try {
                    adFailedToLoad(tempStatus, callBack2, RTBError)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    callBack1(null)
                }
            }
        })
        interstitial.adRequest?.apply {
            owTestMode?.let { b -> enableTestMode(b) }
            owBidSummary?.let { b -> enableBidSummary(b) }
            owDebugState?.let { b -> enableDebugState(b) }
        }
        interstitial.loadAd()
    }

    fun load(adRequest: AdRequest, callBack: (interstitialAd: AdManagerInterstitialAd?, error: RTBError?) -> Unit) {
        var adManagerAdRequest = adRequest.getAdRequest()
        if (adManagerAdRequest == null) {
            callBack(null, null)
            return
        }
        shouldSetConfig {
            if (it) {
                setConfig()
                if (interstitialConfig.isNewUnitApplied()) {
                    adUnit.log { "new unit override on $adUnit" }
                    createRequest().getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(getAdUnitName(false, hijacked = false, newUnit = true), request, callBack)
                    }
                } else if (checkHijack(interstitialConfig.hijack)) {
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

    private fun getRegionalHijackPercentage(): Int {
        var percentage = 0
        if (sdkConfig?.countryStatus?.active == 1 && countryData != null) {
            interstitialConfig.hijack?.regionalPercentage?.firstOrNull { region ->
                region.getCities().any { it.equals(countryData?.city, true) }
                        || region.getStates().any { it.equals(countryData?.state, true) }
                        || region.getCountries().any { it.equals(countryData?.countryCode, true) }
                        || region.getCountries().any { it.equals("default", true) }
            }?.let {
                percentage = it.percentage ?: 0
            }
        }
        return percentage
    }

    private fun checkHijack(hijackConfig: SDKConfig.LoadConfig?): Boolean {
        if (hijackConfig?.regionWise == 1) {
            return if (countryData == null) {
                false
            } else {
                if (hijackConfig.status == 1) {
                    val per = getRegionalHijackPercentage()
                    val number = (1..100).random()
                    number in 1..per
                } else {
                    false
                }

            }
        } else {
            return if (hijackConfig?.status == 1) {
                val number = (1..100).random()
                number in 1..(hijackConfig.per ?: 0)
            } else {
                false
            }
        }
    }

    private fun loadAd(adUnit: String, adRequest: AdManagerAdRequest, callBack: (interstitialAd: AdManagerInterstitialAd?, error: RTBError?) -> Unit, previousError: RTBError? = null) {
        otherUnit = adUnit != this.adUnit
        this.adUnit.log { "loading $adUnit by GAM" }
        fetchDemand(adRequest) { finalRequest ->
            AdManagerInterstitialAd.load(context, adUnit, finalRequest, object : AdManagerInterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: AdManagerInterstitialAd) {
                    this@InterstitialAdManager.adUnit.log { "loaded $adUnit by GAM" }
                    interstitialConfig.retryConfig = sdkConfig?.retryConfig
                    addGeoEdge(AdSdk.GAM, interstitialAd, otherUnit)
                    callBack(interstitialAd, null)
                    firstLook = false
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    this@InterstitialAdManager.adUnit.log { "loading $adUnit failed by GAM with error : ${adError.message}" }
                    val tempStatus = firstLook
                    if (firstLook) {
                        firstLook = false
                    }
                    val RTBError = previousError ?: RTBError(adError.code, adError.message)
                    try {
                        adFailedToLoad(tempStatus, callBack, RTBError)
                    } catch (_: Throwable) {
                        callBack(null, RTBError)
                    }
                }
            })
        }
    }

    private fun addGeoEdge(sdk: AdSdk, interstitialAd: Any, otherUnit: Boolean) {
        try {
            val number = (1..100).random()
            if ((!otherUnit && (number in 1..(sdkConfig?.geoEdge?.firstLook ?: 0))) || (otherUnit && (number in 1..(sdkConfig?.geoEdge?.other ?: 0)))) {
                AppHarbr.addInterstitial(sdk, interstitialAd, object : AHIncident {
                    override fun onAdBlocked(p0: Any?, p1: String?, p2: AdFormat, reasons: Array<out AdBlockReason>) {
                        adUnit.log { "Interstitial : onAdBlocked : ${Gson().toJson(reasons.asList().map { it.reason })}" }
                    }

                    override fun onAdIncident(p0: Any?, p1: String?, p2: AdSdk?, p3: String?, p4: AdFormat, p5: Array<out AdBlockReason>, reportReasons: Array<out AdBlockReason>) {
                        adUnit.log { "Interstitial: onAdIncident : ${Gson().toJson(reportReasons.asList().map { it.reason })}" }
                    }
                })
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun adFailedToLoad(firstLook: Boolean, callBack: (interstitialAd: AdManagerInterstitialAd?, error: RTBError?) -> Unit, RTBError: RTBError) {
        adUnit.log { "Failed with Unfilled Config: ${Gson().toJson(interstitialConfig.unFilled)} && Retry config : ${Gson().toJson(interstitialConfig.retryConfig)}" }
        fun requestAd() {
            interstitialConfig.isNewUnit = false
            createRequest(unfilled = true).getAdRequest()?.let {
                loadAd(getAdUnitName(unfilled = true, hijacked = false, newUnit = false), it, callBack, RTBError)
            }
        }
        if (shouldBeActive) {
            if (interstitialConfig.unFilled?.status == 1) {
                if (firstLook && !interstitialConfig.isNewUnitApplied()) {
                    requestAd()
                } else {
                    if ((interstitialConfig.retryConfig?.retries ?: 0) > 0) {
                        interstitialConfig.retryConfig?.retries = (interstitialConfig.retryConfig?.retries ?: 0) - 1
                        Handler(Looper.getMainLooper()).postDelayed({
                            interstitialConfig.retryConfig?.adUnits?.firstOrNull()?.let {
                                interstitialConfig.retryConfig?.adUnits?.removeAt(0)
                                overridingUnit = it
                                requestAd()
                            } ?: kotlin.run {
                                overridingUnit = null
                                callBack(null, RTBError)
                            }
                        }, (interstitialConfig.retryConfig?.retryInterval ?: 0).toLong() * 1000)
                    } else {
                        overridingUnit = null
                        callBack(null, RTBError)
                    }
                }
            } else {
                callBack(null, RTBError)
            }

        } else {
            callBack(null, RTBError)
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
        val validConfig = sdkConfig?.refreshConfig?.firstOrNull { config -> config.specific?.equals(adUnit, true) == true || config.type == AdTypes.INTERSTITIAL || config.type.equals("all", true) }
        if (validConfig == null) {
            shouldBeActive = false
            return
        }
        val networkName = if (sdkConfig?.networkCode.isNullOrEmpty()) sdkConfig?.networkId else String.format("%s,%s", sdkConfig?.networkId, sdkConfig?.networkCode)
        interstitialConfig.apply {
            customUnitName = String.format("/%s/%s-%s", networkName, sdkConfig?.affiliatedId.toString(), validConfig.nameType ?: "")
            position = validConfig.position ?: 0
            isNewUnit = adUnit.contains(sdkConfig?.networkId ?: "")
            placement = validConfig.placement
            newUnit = sdkConfig?.hijackConfig?.newUnit
            retryConfig = sdkConfig?.retryConfig
            hijack = sdkConfig?.hijackConfig?.inter ?: sdkConfig?.hijackConfig?.other
            unFilled = sdkConfig?.unfilledConfig?.inter ?: sdkConfig?.unfilledConfig?.other
            format = validConfig.format
        }
        adUnit.log { "setConfig :$interstitialConfig" }
    }

    private fun getAdUnitName(unfilled: Boolean, hijacked: Boolean, newUnit: Boolean): String {
        return overridingUnit ?: String.format(Locale.ENGLISH, "%s-%d", interstitialConfig.customUnitName, if (unfilled) interstitialConfig.unFilled?.number else if (newUnit) interstitialConfig.newUnit?.number else if (hijacked) interstitialConfig.hijack?.number else interstitialConfig.position)
    }

    private fun createRequest(unfilled: Boolean = false, hijacked: Boolean = false) = AdRequest().Builder().apply {
        addCustomTargeting("adunit", adUnit)
        addCustomTargeting("hb_format", sdkConfig?.hbFormat ?: "amp")
        if (unfilled) addCustomTargeting("retry", "1")
        if (hijacked) addCustomTargeting("hijack", "1")
    }.build()

    private fun fetchDemand(adRequest: AdManagerAdRequest, callback: (AdManagerAdRequest) -> Unit) {
        var prebidAvailable = (interstitialConfig.isNewUnitApplied() && sdkConfig?.prebid?.other == 1) ||
                (otherUnit && !interstitialConfig.isNewUnitApplied() && sdkConfig?.prebid?.retry == 1) ||
                (!otherUnit && sdkConfig?.prebid?.firstLook == 1)

        var apsAvailable = (interstitialConfig.isNewUnitApplied() && sdkConfig?.aps?.other == 1) ||
                (otherUnit && !interstitialConfig.isNewUnitApplied() && sdkConfig?.aps?.retry == 1) ||
                (!otherUnit && sdkConfig?.aps?.firstLook == 1)

        val formatNeeded = interstitialConfig.format

        if (sdkConfig?.prebid?.whitelistedFormats != null && sdkConfig?.prebid?.whitelistedFormats?.contains(AdTypes.INTERSTITIAL) == false) {
            prebidAvailable = false
        }
        if (sdkConfig?.aps?.whitelistedFormats != null && sdkConfig?.aps?.whitelistedFormats?.contains(AdTypes.INTERSTITIAL) == false) {
            apsAvailable = false
        }

        fun prebid(apsRequestBuilder: AdManagerAdRequest.Builder? = null) {
            val adUnit = if (formatNeeded.isNullOrEmpty() || (formatNeeded.contains("html", true) && !formatNeeded.contains("video", true))) {
                InterstitialAdUnit((if (otherUnit) interstitialConfig.placement?.other ?: 0 else interstitialConfig.placement?.firstLook ?: 0).toString(), 80, 60)
            } else if (formatNeeded.contains("video", true) && !formatNeeded.contains("html", true)) {
                InterstitialAdUnit((if (otherUnit) interstitialConfig.placement?.other ?: 0 else interstitialConfig.placement?.firstLook ?: 0).toString(), EnumSet.of(AdUnitFormat.VIDEO)).apply {
                    videoParameters = configureVideoParameters()
                }
            } else {
                InterstitialAdUnit((if (otherUnit) interstitialConfig.placement?.other ?: 0 else interstitialConfig.placement?.firstLook ?: 0).toString(), EnumSet.of(AdUnitFormat.BANNER, AdUnitFormat.VIDEO)).apply {
                    setMinSizePercentage(80, 60)
                    videoParameters = configureVideoParameters()
                }
            }
            val finalRequest = apsRequestBuilder?.let {
                adRequest.customTargeting.keySet().forEach { key ->
                    it.addCustomTargeting(key, adRequest.customTargeting.getString(key, ""))
                }
                it.build()
            } ?: adRequest
            adUnit.fetchDemand(finalRequest) { callback(finalRequest) }
        }

        fun aps(wait: Boolean) {
            var actionTaken = false
            val matchingSlots = arrayListOf<DTBAdSize>()
            sdkConfig?.aps?.slots?.firstOrNull { slot -> slot.height == "inter" && slot.width == "inter" }?.let {
                matchingSlots.add(DTBAdSize.DTBInterstitialAdSize(it.slotId ?: ""))
            }
            if (formatNeeded?.contains("video", true) == true) {
                sdkConfig?.aps?.slots?.firstOrNull { slot -> slot.height?.toIntOrNull() == 480 && slot.width?.toIntOrNull() == 320 }?.let {
                    matchingSlots.add(DTBAdSize.DTBVideo(320, 480, it.slotId ?: ""))
                }
            }
            val loader = DTBAdRequest()
            val apsCallback = object : DTBAdCallback {
                override fun onFailure(adError: AdError) {
                    if (actionTaken) return
                    actionTaken = true
                    if (wait) {
                        prebid(null)
                    } else {
                        callback(adRequest)
                    }
                }

                override fun onSuccess(dtbAdResponse: DTBAdResponse) {
                    if (actionTaken) return
                    actionTaken = true
                    val apsRequest = DTBAdUtil.INSTANCE.createAdManagerAdRequestBuilder(dtbAdResponse)
                    if (formatNeeded?.contains("video", true) == true) {
                        dtbAdResponse.defaultVideoAdsRequestCustomParams.forEach {
                            apsRequest.addCustomTargeting(it.key, it.value)
                        }
                    }
                    if (wait) {
                        prebid(apsRequest)
                    } else {
                        adRequest.customTargeting.keySet().forEach { key ->
                            apsRequest.addCustomTargeting(key, adRequest.customTargeting.getString(key, ""))
                        }
                        callback(apsRequest.build())
                    }
                }
            }
            if (matchingSlots.isEmpty() || !AdRegistration.isInitialized()) {
                apsCallback.onFailure(AdError(AdError.ErrorCode.NO_FILL, "error"))
                return
            }
            loader.setSizes(*matchingSlots.toTypedArray())
            loader.loadAd(apsCallback)
            sdkConfig?.aps?.timeout?.let {
                Handler(Looper.getMainLooper()).postDelayed({
                    apsCallback.onFailure(AdError(AdError.ErrorCode.NO_FILL, "error"))
                }, it.toLongOrNull() ?: 1000)
            }
        }

        adUnit.log { "Fetch Demand with aps : $apsAvailable and with prebid : $prebidAvailable" }
        if (apsAvailable && prebidAvailable) {
            aps(true)
        } else if (apsAvailable) {
            aps(false)
        } else if (prebidAvailable) {
            prebid()
        } else {
            callback(adRequest)
        }

    }

    private fun configureVideoParameters(): VideoParameters {
        return VideoParameters(listOf("video/x-flv", "video/mp4")).apply {
            placement = Signals.Placement.Interstitial
            api = listOf(Signals.Api.VPAID_1, Signals.Api.VPAID_2)
            maxBitrate = 1500
            minBitrate = 300
            maxDuration = 30
            minDuration = 5
            playbackMethod = listOf(Signals.PlaybackMethod.AutoPlaySoundOn)
            protocols = listOf(Signals.Protocols.VAST_2_0)
        }
    }
}