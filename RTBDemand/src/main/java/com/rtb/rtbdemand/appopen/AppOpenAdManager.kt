package com.rtb.rtbdemand.appopen

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.gson.Gson
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.sdk.AdLoadCallback
import com.rtb.rtbdemand.sdk.ConfigFetch
import com.rtb.rtbdemand.sdk.ConfigProvider
import com.rtb.rtbdemand.sdk.Logger
import com.rtb.rtbdemand.sdk.OnShowAdCompleteListener
import com.rtb.rtbdemand.sdk.RTBDemand
import com.rtb.rtbdemand.sdk.RTBError
import com.rtb.rtbdemand.sdk.SDKConfig
import com.rtb.rtbdemand.sdk.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class AppOpenAdManager(private val context: Context, private val adUnit: String?) {
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var loadTime: Long = 0
    private var sdkConfig: SDKConfig? = null
    private var appOpenConfig = AppOpenConfig()
    private var shouldBeActive: Boolean = false
    private var firstLook: Boolean = true
    private var overridingUnit: String? = null
    private var loadingAdUnit: String? = adUnit
    var fullScreenContentCallback: com.rtb.rtbdemand.sdk.FullScreenContentCallback? = null
    var isShowingAd = false

    init {
        RTBDemand.registerActivity(context)
        sdkConfig = ConfigProvider.getConfig(context)
        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
    }

    private fun load(context: Context, adLoadCallback: AdLoadCallback? = null) {
        if (isLoadingAd || isAdAvailable() || loadingAdUnit == null) {
            return
        }
        var adManagerAdRequest = createRequest().getAdRequest() ?: return
        shouldSetConfig {
            if (it) {
                setConfig()
                if (appOpenConfig.isNewUnitApplied()) {
                    adUnit.log { "new unit override on $adUnit" }
                    createRequest().getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(context, getAdUnitName(false, hijacked = false, newUnit = true), adManagerAdRequest, adLoadCallback)
                    }
                } else if (checkHijack(appOpenConfig.hijack)) {
                    adUnit.log { "hijack override on $adUnit" }
                    createRequest(hijacked = true).getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(context, getAdUnitName(false, hijacked = true, newUnit = false), adManagerAdRequest, adLoadCallback)
                    }
                } else {
                    loadAd(context, loadingAdUnit!!, adManagerAdRequest, adLoadCallback)
                }
            } else {
                loadAd(context, loadingAdUnit!!, adManagerAdRequest, adLoadCallback)
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

    private fun loadAd(context: Context, adUnit: String, adRequest: AdManagerAdRequest, adLoadCallback: AdLoadCallback?) {
        isLoadingAd = true
        this.adUnit.log { "loading $adUnit by App open" }
        AppOpenAd.load(context, adUnit, adRequest, object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                this@AppOpenAdManager.adUnit.log { "loaded $adUnit by App open" }
                appOpenAd = ad
                isLoadingAd = false
                loadTime = Date().time
                appOpenConfig.retryConfig = sdkConfig?.retryConfig
                adLoadCallback?.onAdLoaded()
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                this@AppOpenAdManager.adUnit.log { "loading $adUnit failed by App open with error : ${loadAdError.message}" }
                isLoadingAd = false
                val tempStatus = firstLook
                if (firstLook) {
                    firstLook = false
                }
                try {
                    adFailedToLoad(context, tempStatus, adLoadCallback)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    adLoadCallback?.onAdFailedToLoad(RTBError(loadAdError.code, loadAdError.message, loadAdError.domain))
                }
            }
        })
    }

    private fun adFailedToLoad(context: Context, firstLook: Boolean, adLoadCallback: AdLoadCallback?) {
        adUnit.log { "Failed with Unfilled Config: ${Gson().toJson(appOpenConfig.unFilled)} && Retry config : ${Gson().toJson(appOpenConfig.retryConfig)}" }

        fun requestAd() {
            appOpenConfig.isNewUnit = false
            createRequest(unfilled = true).getAdRequest()?.let {
                loadAd(context, getAdUnitName(unfilled = true, hijacked = false, newUnit = false), it, adLoadCallback)
            }
        }
        if (shouldBeActive) {
            if (appOpenConfig.unFilled?.status == 1) {
                if (firstLook && !appOpenConfig.isNewUnitApplied()) {
                    requestAd()
                } else {
                    adLoadCallback?.onAdFailedToLoad(RTBError(10))
                    if ((appOpenConfig.retryConfig?.retries ?: 0) > 0) {
                        appOpenConfig.retryConfig?.retries = (appOpenConfig.retryConfig?.retries ?: 0) - 1
                        Handler(Looper.getMainLooper()).postDelayed({
                            appOpenConfig.retryConfig?.adUnits?.firstOrNull()?.let {
                                appOpenConfig.retryConfig?.adUnits?.removeAt(0)
                                overridingUnit = it
                                requestAd()
                            } ?: kotlin.run {
                                overridingUnit = null
                            }
                        }, (appOpenConfig.retryConfig?.retryInterval ?: 0).toLong() * 1000)
                    } else {
                        overridingUnit = null
                    }
                }
            } else {
                adLoadCallback?.onAdFailedToLoad(RTBError(10))
            }
        } else {
            adLoadCallback?.onAdFailedToLoad(RTBError(10))
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
        if (sdkConfig?.getBlockList()?.contains(loadingAdUnit) == true) {
            shouldBeActive = false
            return
        }
        val validConfig = sdkConfig?.refreshConfig?.firstOrNull { config ->
            config.specific?.equals(loadingAdUnit, true) == true || config.type == AdTypes.APPOPEN || config.type.equals("all", true)
        }
        if (validConfig == null) {
            shouldBeActive = false
            return
        }
        val networkName = if (sdkConfig?.networkCode.isNullOrEmpty()) sdkConfig?.networkId else String.format("%s,%s", sdkConfig?.networkId, sdkConfig?.networkCode)
        appOpenConfig.apply {
            customUnitName = String.format("/%s/%s-%s", networkName, sdkConfig?.affiliatedId.toString(), validConfig.nameType ?: "")
            position = validConfig.position ?: 0
            isNewUnit = loadingAdUnit?.contains(sdkConfig?.networkId ?: "") ?: false
            retryConfig = sdkConfig?.retryConfig
            newUnit = sdkConfig?.hijackConfig?.newUnit
            hijack = sdkConfig?.hijackConfig?.appOpen ?: sdkConfig?.hijackConfig?.other
            unFilled = sdkConfig?.unfilledConfig?.appOpen ?: sdkConfig?.unfilledConfig?.other
            expriry = validConfig.expiry ?: 0
        }
    }

    private fun isAdAvailable(): Boolean {
        return if (appOpenConfig.expriry != 0) {
            appOpenAd != null && wasLoadTimeLessThanNHoursAgo(appOpenConfig.expriry.toLong())
        } else {
            appOpenAd != null
        }
    }

    private fun getAdUnitName(unfilled: Boolean, hijacked: Boolean, newUnit: Boolean): String {
        return overridingUnit ?: String.format(Locale.ENGLISH, "%s-%d", appOpenConfig.customUnitName,
                if (unfilled) appOpenConfig.unFilled?.number else if (newUnit) appOpenConfig.newUnit?.number else if (hijacked) appOpenConfig.hijack?.number else appOpenConfig.position)
    }

    private fun createRequest(unfilled: Boolean = false, hijacked: Boolean = false) = AdRequest().Builder().apply {
        addCustomTargeting("adunit", loadingAdUnit ?: "")
        addCustomTargeting("hb_format", sdkConfig?.hbFormat ?: "amp")
        if (unfilled) addCustomTargeting("retry", "1")
        if (hijacked) addCustomTargeting("hijack", "1")
    }.build()

    private fun wasLoadTimeLessThanNHoursAgo(numHours: Long): Boolean {
        val dateDifference: Long = Date().time - loadTime
        val numMilliSecondsPerHour: Long = 3600000
        return dateDifference < numMilliSecondsPerHour * numHours
    }


    fun showAdIfAvailable(activity: Activity, onShowAdCompleteListener: OnShowAdCompleteListener) {
        if (isShowingAd) {
            Logger.INFO.log(msg = "The app open ad is already showing.")
            return
        }
        if (!isAdAvailable()) {
            Logger.ERROR.log(msg = "The app open ad is not ready yet.")
            onShowAdCompleteListener.onShowAdComplete()
            load(activity)
            return
        }
        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                fullScreenContentCallback?.onAdDismissedFullScreenContent()
                appOpenAd = null
                isShowingAd = false

                onShowAdCompleteListener.onShowAdComplete()
                load(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                fullScreenContentCallback?.onAdFailedToShowFullScreenContent(RTBError(adError.code, adError.message))
                Logger.ERROR.log(msg = adError.message)
                appOpenAd = null
                isShowingAd = false
                onShowAdCompleteListener.onShowAdComplete()
                load(activity)
            }

            override fun onAdShowedFullScreenContent() {
                fullScreenContentCallback?.onAdShowedFullScreenContent()
            }

            override fun onAdClicked() {
                fullScreenContentCallback?.onAdClicked()
            }

            override fun onAdImpression() {
                fullScreenContentCallback?.onAdImpression()
            }
        }
        isShowingAd = true
        appOpenAd?.show(activity)
    }

    fun prepareAd(adUnit: String?, adLoadCallback: AdLoadCallback) {
        adUnit?.let { loadingAdUnit = it }
        if (context is Activity) {
            load(context, adLoadCallback)
        } else {
            adLoadCallback.onAdFailedToLoad(RTBError(10))
        }
    }
}