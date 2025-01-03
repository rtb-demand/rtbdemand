package com.rtb.rtbdemand.nativeformat

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.gson.Gson
import com.pubmatic.sdk.common.POBError
import com.pubmatic.sdk.nativead.POBNativeAd
import com.pubmatic.sdk.nativead.POBNativeAdLoader
import com.pubmatic.sdk.nativead.POBNativeAdLoaderListener
import com.pubmatic.sdk.nativead.datatype.POBNativeTemplateType
import com.pubmatic.sdk.openwrap.eventhandler.dfp.GAMNativeEventHandler
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.intersitial.InterstitialConfig
import com.rtb.rtbdemand.sdk.ConfigFetchWorker
import com.rtb.rtbdemand.sdk.ConfigProvider
import com.rtb.rtbdemand.sdk.RTBDemand
import com.rtb.rtbdemand.sdk.SDKConfig
import com.rtb.rtbdemand.sdk.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.prebid.mobile.NativeAdUnit
import org.prebid.mobile.NativeDataAsset
import org.prebid.mobile.NativeEventTracker
import org.prebid.mobile.NativeImageAsset
import org.prebid.mobile.NativeTitleAsset
import java.util.Locale

class NativeAdManager(private val context: Context, private val adUnit: String) {

    private var sdkConfig: SDKConfig? = null
    private var nativeConfig: InterstitialConfig = InterstitialConfig()
    private var shouldBeActive: Boolean = false
    private var firstLook: Boolean = true
    private var overridingUnit: String? = null
    private var otherUnit = false
    private var adListener: AdListener? = null
    private var adOptions = NativeAdOptions.Builder().build()
    private var loadCount: Int = 0
    private lateinit var adLoader: AdLoader
    var owBidSummary: Boolean? = null
    var owDebugState: Boolean? = null
    var owTestMode: Boolean? = null

    init {
        RTBDemand.registerActivity(context)
        sdkConfig = ConfigProvider.getConfig(context)
        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
    }

    fun setAdListener(adListener: AdListener) {
        this.adListener = adListener
    }

    fun setNativeAdOptions(adOptions: NativeAdOptions) {
        this.adOptions = adOptions
    }

    fun setLoadCount(count: Int) {
        this.loadCount = count
    }

    fun isLoading(): Boolean {
        return this::adLoader.isInitialized && adLoader.isLoading
    }

    fun enableTestMode(enabled: Boolean) {
        owTestMode = enabled
    }

    fun enableDebugState(enabled: Boolean) {
        owDebugState = enabled
    }

    fun enableBidSummary(enabled: Boolean) {
        owBidSummary = enabled
    }

    fun loadWithOW(pubID: String, profile: Int, owAdUnitId: String, templateType: POBNativeTemplateType,
                   eventHandler: GAMNativeEventHandler? = null, fallback: (fallbackAd: NativeAd?) -> Unit,
                   callBack: (nativeAdLoader: POBNativeAdLoader?, pobNativeAd: POBNativeAd?) -> Unit) {
        shouldSetConfig {
            if (it) {
                setConfig()
                if (nativeConfig.isNewUnitApplied()) {
                    adUnit.log { "new unit override on ow adunit : $adUnit" }
                    createRequest().getAdRequest()?.let { request ->
                        loadAd(getAdUnitName(false, hijacked = false, newUnit = true), request, fallback)
                    }
                } else if (checkHijack(nativeConfig.hijack)) {
                    adUnit.log { "hijack override on ow adunit : $adUnit" }
                    createRequest(hijacked = true).getAdRequest()?.let { request ->
                        loadAd(getAdUnitName(false, hijacked = true, newUnit = false), request, fallback)
                    }
                } else {
                    loadOW(pubID, profile, owAdUnitId, templateType, eventHandler, fallback, callBack)
                }
            } else {
                loadOW(pubID, profile, owAdUnitId, templateType, eventHandler, fallback, callBack)
            }
        }
    }

    private fun loadOW(pubID: String, profile: Int, owAdUnitId: String, templateType: POBNativeTemplateType,
                       eventHandler: GAMNativeEventHandler? = null, fallback: (fallbackAd: NativeAd?) -> Unit,
                       callBack: (nativeAdLoader: POBNativeAdLoader?, pobNativeAd: POBNativeAd?) -> Unit) {
        adUnit.log { "loading $adUnit by Pubmatic with pub id : $pubID, profile : $profile, owUnit : $owAdUnitId" }
        val nativeAdLoader = eventHandler?.let {
            POBNativeAdLoader(context, pubID, profile, owAdUnitId, templateType, it)
        } ?: POBNativeAdLoader(context, pubID, profile, owAdUnitId, templateType)
        nativeAdLoader.setAdLoaderListener(object : POBNativeAdLoaderListener {
            override fun onAdReceived(p0: POBNativeAdLoader, p1: POBNativeAd) {
                adUnit.log { "loaded $adUnit by pubmatic" }
                nativeConfig.retryConfig = sdkConfig?.retryConfig
                fallback(null)
                callBack(p0, p1)
                adListener?.onAdLoaded()
                firstLook = false
            }

            override fun onFailedToLoad(p0: POBNativeAdLoader, p1: POBError) {
                adUnit.log { "loading $adUnit failed by pubmatic with error : ${p1.errorMessage}" }
                val tempStatus = firstLook
                if (firstLook) {
                    firstLook = false
                }
                val adError = LoadAdError(p1.errorCode, p1.errorMessage, "", null, null)
                try {
                    callBack(null, null)
                    adFailedToLoad(tempStatus, fallback, adError)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    fallback(null)
                    callBack(null, null)
                    adListener?.onAdFailedToLoad(adError)
                }
            }
        })

        nativeAdLoader.adRequest?.apply {
            owTestMode?.let { b -> enableTestMode(b) }
            owBidSummary?.let { b -> enableBidSummary(b) }
            owDebugState?.let { b -> enableDebugState(b) }
        }
        nativeAdLoader.loadAd()
    }

    fun load(adRequest: AdRequest, callBack: (nativeAd: NativeAd?) -> Unit) {
        var adManagerAdRequest = adRequest.getAdRequest()
        if (adManagerAdRequest == null) {
            callBack(null)
            return
        }
        shouldSetConfig {
            if (it) {
                setConfig()
                if (nativeConfig.isNewUnitApplied()) {
                    adUnit.log { "new unit override on $adUnit" }
                    createRequest().getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(getAdUnitName(false, hijacked = false, newUnit = true), request, callBack)
                    }
                } else if (checkHijack(nativeConfig.hijack)) {
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

    private fun loadAd(adUnit: String, adRequest: AdManagerAdRequest, callBack: (nativeAd: NativeAd?) -> Unit) {
        otherUnit = adUnit != this.adUnit
        this.adUnit.log { "loading $adUnit by Native GAM" }
        fetchDemand(adRequest) {
            adLoader = AdLoader.Builder(context, adUnit)
                    .forNativeAd { nativeAd: NativeAd ->
                        nativeConfig.retryConfig = sdkConfig?.retryConfig
                        callBack(nativeAd)
                        firstLook = false
                    }
                    .withAdListener(object : AdListener() {
                        override fun onAdClicked() {
                            adListener?.onAdClicked()
                        }

                        override fun onAdClosed() {
                            adListener?.onAdClosed()
                        }

                        override fun onAdImpression() {
                            adListener?.onAdImpression()
                        }

                        override fun onAdLoaded() {
                            adListener?.onAdLoaded()
                        }

                        override fun onAdOpened() {
                            adListener?.onAdOpened()
                        }

                        override fun onAdSwipeGestureClicked() {
                            adListener?.onAdSwipeGestureClicked()
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            this@NativeAdManager.adUnit.log { "loading $adUnit failed by GAM with error : ${adError.message}" }
                            val tempStatus = firstLook
                            if (firstLook) {
                                firstLook = false
                            }
                            try {
                                adFailedToLoad(tempStatus, callBack, adError)
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                callBack(null)
                                adListener?.onAdFailedToLoad(adError)
                            }
                        }
                    })
                    .withNativeAdOptions(adOptions)
                    .build()
            if (loadCount == 0) {
                adLoader.loadAd(adRequest)
            } else {
                adLoader.loadAds(adRequest, loadCount)
            }
        }
    }

    private fun adFailedToLoad(firstLook: Boolean, callBack: (nativeAd: NativeAd?) -> Unit, adError: LoadAdError) {
        adUnit.log {
            "Failed with Unfilled Config: ${Gson().toJson(nativeConfig.unFilled)} && Retry config : ${Gson().toJson(nativeConfig.retryConfig)} " +
                    "&& isFirstLook : $firstLook"
        }
        fun requestAd() {
            nativeConfig.isNewUnit = false
            createRequest(unfilled = true).getAdRequest()?.let {
                loadAd(getAdUnitName(unfilled = true, hijacked = false, newUnit = false), it, callBack)
            }
        }
        if (shouldBeActive) {
            if (nativeConfig.unFilled?.status == 1) {
                if (firstLook && !nativeConfig.isNewUnitApplied()) {
                    requestAd()
                } else {
                    if ((nativeConfig.retryConfig?.retries ?: 0) > 0) {
                        nativeConfig.retryConfig?.retries = (nativeConfig.retryConfig?.retries ?: 0) - 1
                        Handler(Looper.getMainLooper()).postDelayed({
                            nativeConfig.retryConfig?.adUnits?.firstOrNull()?.let {
                                nativeConfig.retryConfig?.adUnits?.removeAt(0)
                                overridingUnit = it
                                requestAd()
                            } ?: kotlin.run {
                                overridingUnit = null
                                callBack(null)
                                adListener?.onAdFailedToLoad(adError)
                            }
                        }, (nativeConfig.retryConfig?.retryInterval ?: 0).toLong() * 1000)
                    } else {
                        overridingUnit = null
                        callBack(null)
                        adListener?.onAdFailedToLoad(adError)
                    }
                }
            } else {
                callBack(null)
                adListener?.onAdFailedToLoad(adError)
            }
        } else {
            callBack(null)
            adListener?.onAdFailedToLoad(adError)
        }
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun shouldSetConfig(callback: (Boolean) -> Unit) = CoroutineScope(Dispatchers.Main).launch {
        val workManager = RTBDemand.getWorkManager(context)
        val workers = workManager.getWorkInfosForUniqueWork(ConfigFetchWorker::class.java.simpleName).get()
        if (workers.isNullOrEmpty()) {
            callback(false)
        } else {
            try {
                val workerData = workManager.getWorkInfoByIdLiveData(workers[0].id)
                workerData?.observeForever(object : Observer<WorkInfo?> {
                    override fun onChanged(value: WorkInfo?) {
                        if (value?.state != WorkInfo.State.RUNNING && value?.state != WorkInfo.State.ENQUEUED) {
                            workerData.removeObserver(this)
                            sdkConfig = ConfigProvider.getConfig(context)
                            shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
                            callback(shouldBeActive)
                        }
                    }
                })
            } catch (e: Throwable) {
                callback(false)
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
        val validConfig = sdkConfig?.refreshConfig?.firstOrNull { config -> config.specific?.equals(adUnit, true) == true || config.type == AdTypes.NATIVE || config.type.equals("all", true) }
        if (validConfig == null) {
            shouldBeActive = false
            return
        }
        val networkName = if (sdkConfig?.networkCode.isNullOrEmpty()) sdkConfig?.networkId else String.format("%s,%s", sdkConfig?.networkId, sdkConfig?.networkCode)
        nativeConfig.apply {
            customUnitName = String.format("/%s/%s-%s", networkName, sdkConfig?.affiliatedId.toString(), validConfig.nameType ?: "")
            position = validConfig.position ?: 0
            isNewUnit = adUnit.contains(sdkConfig?.networkId ?: "")
            placement = validConfig.placement
            newUnit = sdkConfig?.hijackConfig?.newUnit
            retryConfig = sdkConfig?.retryConfig
            hijack = sdkConfig?.hijackConfig?.native ?: sdkConfig?.hijackConfig?.other
            unFilled = sdkConfig?.unfilledConfig?.native ?: sdkConfig?.unfilledConfig?.other
        }
        adUnit.log { "setConfig :$nativeConfig" }
    }

    private fun getAdUnitName(unfilled: Boolean, hijacked: Boolean, newUnit: Boolean): String {
        return overridingUnit ?: String.format(Locale.ENGLISH, "%s-%d", nativeConfig.customUnitName, if (unfilled) nativeConfig.unFilled?.number else if (newUnit) nativeConfig.newUnit?.number else if (hijacked) nativeConfig.hijack?.number else nativeConfig.position)
    }

    private fun createRequest(unfilled: Boolean = false, hijacked: Boolean = false) = AdRequest().Builder().apply {
        addCustomTargeting("adunit", adUnit)
        addCustomTargeting("hb_format", sdkConfig?.hbFormat ?: "amp")
        if (unfilled) addCustomTargeting("retry", "1")
        if (hijacked) addCustomTargeting("hijack", "1")
    }.build()

    private fun fetchDemand(adRequest: AdManagerAdRequest, callback: () -> Unit) {
        if (sdkConfig?.prebid?.whitelistedFormats != null && sdkConfig?.prebid?.whitelistedFormats?.contains(AdTypes.NATIVE) == false) {
            callback()
            return
        }

        if ((nativeConfig.isNewUnitApplied() && sdkConfig?.prebid?.other == 1) ||
                (otherUnit && !nativeConfig.isNewUnitApplied() && sdkConfig?.prebid?.retry == 1) ||
                (!otherUnit && sdkConfig?.prebid?.firstLook == 1)
        ) {
            adUnit.log { "Fetch Demand with prebid" }
            val adUnit = NativeAdUnit((if (otherUnit) nativeConfig.placement?.other ?: 0 else nativeConfig.placement?.firstLook ?: 0).toString())
            adUnit.setContextType(NativeAdUnit.CONTEXT_TYPE.SOCIAL_CENTRIC)
            adUnit.setPlacementType(NativeAdUnit.PLACEMENTTYPE.CONTENT_FEED)
            adUnit.setContextSubType(NativeAdUnit.CONTEXTSUBTYPE.GENERAL_SOCIAL)
            addNativeAssets(adUnit)
            adUnit.fetchDemand(adRequest) { callback() }
        } else {
            callback()
        }
    }

    private fun addNativeAssets(adUnit: NativeAdUnit?) {
        // ADD NATIVE ASSETS

        val title = NativeTitleAsset()
        title.setLength(90)
        title.isRequired = true
        adUnit?.addAsset(title)

        val icon = NativeImageAsset(20, 20, 20, 20)
        icon.imageType = NativeImageAsset.IMAGE_TYPE.ICON
        icon.isRequired = true
        adUnit?.addAsset(icon)

        val image = NativeImageAsset(200, 200, 200, 200)
        image.imageType = NativeImageAsset.IMAGE_TYPE.MAIN
        image.isRequired = true
        adUnit?.addAsset(image)

        val data = NativeDataAsset()
        data.len = 90
        data.dataType = NativeDataAsset.DATA_TYPE.SPONSORED
        data.isRequired = true
        adUnit?.addAsset(data)

        val body = NativeDataAsset()
        body.isRequired = true
        body.dataType = NativeDataAsset.DATA_TYPE.DESC
        adUnit?.addAsset(body)

        val cta = NativeDataAsset()
        cta.isRequired = true
        cta.dataType = NativeDataAsset.DATA_TYPE.CTATEXT
        adUnit?.addAsset(cta)

        // ADD NATIVE EVENT TRACKERS
        val methods = ArrayList<NativeEventTracker.EVENT_TRACKING_METHOD>()
        methods.add(NativeEventTracker.EVENT_TRACKING_METHOD.IMAGE)
        methods.add(NativeEventTracker.EVENT_TRACKING_METHOD.JS)
        try {
            val tracker = NativeEventTracker(NativeEventTracker.EVENT_TYPE.IMPRESSION, methods)
            adUnit?.addEventTracker(tracker)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

}