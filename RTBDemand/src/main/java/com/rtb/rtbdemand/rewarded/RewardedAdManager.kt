package com.rtb.rtbdemand.rewarded

import android.app.Activity
import android.util.Log
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.common.TAG
import com.rtb.rtbdemand.intersitial.InterstitialConfig
import com.rtb.rtbdemand.sdk.ConfigSetWorker
import com.rtb.rtbdemand.sdk.SDKConfig
import com.rtb.rtbdemand.sdk.StoreService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.prebid.mobile.RewardedVideoAdUnit

internal class RewardedAdManager(private val context: Activity, private val adUnit: String) : KoinComponent {

    private var sdkConfig: SDKConfig? = null
    private var config: InterstitialConfig = InterstitialConfig()
    private var shouldBeActive: Boolean = false
    private val storeService: StoreService by inject()
    private var firstLook: Boolean = true

    init {
        sdkConfig = storeService.config
        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
    }

    fun load(adRequest: AdRequest, callBack: (interstitialAd: RewardedAd?) -> Unit) {
        var adManagerAdRequest = adRequest.getAdRequest()
        if (adManagerAdRequest == null) {
            callBack(null)
            return
        }
        shouldSetConfig {
            if (it) {
                setConfig()
                if (config.isNewUnit) {
                    createRequest().getAdRequest()?.let { request ->
                        adManagerAdRequest = request
                        loadAd(getAdUnitName(false, hijacked = false, newUnit = true), request, callBack)
                    }
                } else if (config.hijack?.status == 1) {
                    createRequest().getAdRequest()?.let { request ->
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

    private fun loadAd(adUnit: String, adRequest: AdManagerAdRequest, callBack: (interstitialAd: RewardedAd?) -> Unit) {
        fetchDemand(adRequest) {
            RewardedAd.load(context, adUnit, adRequest, object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    firstLook = false
                    callBack(ad)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, adError.message)
                    if (firstLook) {
                        firstLook = false
                        val request = createRequest().getAdRequest()
                        if (config.unFilled?.status == 1 && request != null) {
                            loadAd(getAdUnitName(unfilled = true, hijacked = false, newUnit = false), request, callBack)
                        }
                    } else {
                        callBack(null)
                    }
                }
            })
        }
    }

    private fun shouldSetConfig(callback: (Boolean) -> Unit) {
        val workManager: WorkManager by inject()
        val workers = workManager.getWorkInfosForUniqueWork(ConfigSetWorker::class.java.simpleName).get()
        if (workers.isNullOrEmpty()) {
            callback(false)
        } else {
            val workerData = workManager.getWorkInfoByIdLiveData(workers[0].id)
            workerData.observeForever(object : Observer<WorkInfo> {
                override fun onChanged(workInfo: WorkInfo?) {
                    if (workInfo == null || (workInfo.state != WorkInfo.State.RUNNING && workInfo.state != WorkInfo.State.ENQUEUED)) {
                        workerData.removeObserver(this)
                        sdkConfig = storeService.config
                        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
                        callback(shouldBeActive)
                    }
                }
            })
        }
    }

    private fun setConfig() {
        if (!shouldBeActive) return
        if (sdkConfig?.getBlockList()?.contains(adUnit) == true) {
            shouldBeActive = false
            return
        }
        val validConfig = sdkConfig?.refreshConfig?.firstOrNull { config -> config.specific?.equals(adUnit, true) == true || config.type == AdTypes.REWARDV || config.type == "all" }
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
            hijack = sdkConfig?.hijackConfig?.rewardVideos ?: sdkConfig?.hijackConfig?.other
            unFilled = sdkConfig?.unfilledConfig?.rewardVideos ?: sdkConfig?.unfilledConfig?.other
        }
    }

    private fun getAdUnitName(unfilled: Boolean, hijacked: Boolean, newUnit: Boolean): String {
        return String.format("%s-%d", config.customUnitName, if (unfilled) config.unFilled?.number else if (newUnit) config.newUnit?.number else if (hijacked) config.hijack?.number else config.position)
    }

    private fun createRequest() = AdRequest().Builder().apply {
        addCustomTargeting("adunit", adUnit)
        addCustomTargeting("hb_format", "video")
    }.build()

    private fun fetchDemand(adRequest: AdManagerAdRequest, callback: () -> Unit) {
        if (sdkConfig?.prebid?.other != 1) {
            callback()
        } else {
            val adUnit = RewardedVideoAdUnit((config.placement?.other ?: 0).toString())
            adUnit.fetchDemand(adRequest) { callback() }
        }
    }
}