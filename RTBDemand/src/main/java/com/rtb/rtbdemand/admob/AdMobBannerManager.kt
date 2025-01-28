package com.rtb.rtbdemand.admob

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.location.Address
import android.location.Location
import android.net.ConnectivityManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.View
import android.webkit.WebView
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import com.amazon.device.ads.AdError
import com.amazon.device.ads.DTBAdCallback
import com.amazon.device.ads.DTBAdRequest
import com.amazon.device.ads.DTBAdResponse
import com.amazon.device.ads.DTBAdSize
import com.amazon.device.ads.DTBAdUtil
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdapterResponseInfo
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.admanager.AdManagerAdRequest
import com.google.gson.Gson
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.banners.BannerAdSize
import com.rtb.rtbdemand.banners.BannerConfig
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.common.getAddress
import com.rtb.rtbdemand.common.getCountry
import com.rtb.rtbdemand.common.getHardwareDeviceId
import com.rtb.rtbdemand.common.getLocation
import com.rtb.rtbdemand.common.getUniqueId
import com.rtb.rtbdemand.sdk.BannerManagerListener
import com.rtb.rtbdemand.sdk.ConfigFetchWorker
import com.rtb.rtbdemand.sdk.ConfigProvider
import com.rtb.rtbdemand.sdk.CountryDetectionWorker
import com.rtb.rtbdemand.sdk.CountryModel
import com.rtb.rtbdemand.sdk.Fallback
import com.rtb.rtbdemand.sdk.RTBDemand
import com.rtb.rtbdemand.sdk.SDKConfig
import com.rtb.rtbdemand.sdk.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import org.prebid.mobile.BannerAdUnit
import org.prebid.mobile.BannerParameters
import org.prebid.mobile.Signals
import org.prebid.mobile.Signals.Api
import org.prebid.mobile.VideoParameters
import org.prebid.mobile.api.data.AdUnitFormat
import java.io.IOException
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.collections.contains
import kotlin.collections.forEach
import kotlin.collections.isNullOrEmpty
import kotlin.math.ceil

internal class AdMobBannerManager(private val context: Context, private val bannerListener: BannerManagerListener, private val view: View? = null) {

    private var activeTimeCounter: CountDownTimer? = null
    private var passiveTimeCounter: CountDownTimer? = null
    private var unfilledRefreshCounter: CountDownTimer? = null
    private var bannerConfig = BannerConfig()
    private var sdkConfig: SDKConfig? = null
    private var shouldBeActive: Boolean = false
    private var wasFirstLook = true
    private var isForegroundRefresh = 1
    private var overridingUnit: String? = null
    private var refreshBlocked = false
    internal var isInter = false
    private var adType: String = ""
    private var section: String = ""
    private var pubAdSizes: ArrayList<AdSize> = arrayListOf()
    private var countrySetup = Triple<Boolean, Boolean, CountryModel?>(false, false, null) //fetched, applied, config
    private var userLocation = Pair<Location?, Address?>(null, null)
    var pendingImpression = false

    init {
        sdkConfig = ConfigProvider.getConfig(context)
        shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
        getCountryConfig()
    }

    private fun shouldBeActive() = shouldBeActive

    fun isSeemLessRefreshActive(): Boolean {
        return !(sdkConfig == null || !shouldBeActive || (sdkConfig?.seemlessRefresh ?: 0) == 0)
    }

    fun convertStringSizesToAdSizes(adSizes: String): ArrayList<AdSize> {
        fun getAdSizeObj(adSize: String) = when (adSize) {
            "FLUID" -> AdSize.FLUID
            "BANNER" -> AdSize.BANNER
            "LARGE_BANNER" -> AdSize.LARGE_BANNER
            "MEDIUM_RECTANGLE" -> AdSize.MEDIUM_RECTANGLE
            "FULL_BANNER" -> AdSize.FULL_BANNER
            "LEADERBOARD" -> AdSize.LEADERBOARD
            else -> {
                val w = adSize.replace(" ", "").substring(0, adSize.indexOf("x")).toIntOrNull() ?: 0
                val h = adSize.replace(" ", "").substring(adSize.indexOf("x") + 1, adSize.length).toIntOrNull() ?: 0
                AdSize(w, h)
            }
        }

        return ArrayList<AdSize>().apply {
            for (adSize in adSizes.replace(" ", "").split(",")) {
                add(getAdSizeObj(adSize))
            }
        }
    }

    fun convertVaragsToAdSizes(vararg adSizes: BannerAdSize): ArrayList<AdSize> {
        val adSizeList = arrayListOf<AdSize>()
        adSizes.toList().forEach {
            adSizeList.add(it.adSize)
        }
        return adSizeList
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun getCountryConfig() = CoroutineScope(Dispatchers.Main).launch {
        val workManager = RTBDemand.getWorkManager(context)
        val workers = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(CountryDetectionWorker::class.java.simpleName).get()
        }
        if (workers.isNotEmpty()) {
            try {
                val workerData = workManager.getWorkInfoByIdLiveData(workers[0].id)
                workerData?.observeForever(object : Observer<WorkInfo?> {
                    override fun onChanged(value: WorkInfo?) {
                        if (value?.state != WorkInfo.State.RUNNING && value?.state != WorkInfo.State.ENQUEUED) {
                            workerData.removeObserver(this)
                            countrySetup = Triple(true, false, ConfigProvider.getDetectedCountry(context))
                        }
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    fun shouldSetConfig(callback: (Boolean) -> Unit) = CoroutineScope(Dispatchers.Main).launch {
        var actualCallback: ((Boolean) -> Unit)? = callback
        val workManager = RTBDemand.getWorkManager(context)
        val workers = workManager.getWorkInfosForUniqueWork(ConfigFetchWorker::class.java.simpleName).get()
        if (workers.isNullOrEmpty()) {
            actualCallback?.invoke(false)
            actualCallback = null
        } else {
            try {
                val workerData = workManager.getWorkInfoByIdLiveData(workers[0].id)
                workerData?.observeForever(object : Observer<WorkInfo?> {
                    override fun onChanged(value: WorkInfo?) {
                        if (value?.state != WorkInfo.State.RUNNING && value?.state != WorkInfo.State.ENQUEUED) {
                            workerData.removeObserver(this)
                            sdkConfig = ConfigProvider.getConfig(context)
                            shouldBeActive = !(sdkConfig == null || sdkConfig?.switch != 1)
                            actualCallback?.invoke(shouldBeActive)
                            actualCallback = null
                        }
                    }
                })
            } catch (_: Throwable) {
                actualCallback?.invoke(false)
                actualCallback = null
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            actualCallback?.invoke(false)
            actualCallback = null
        }, 4000)
    }

    fun setSudoConfig(sdkConfig: SDKConfig?) {
        this.sdkConfig = sdkConfig
        shouldBeActive = !(sdkConfig == null || sdkConfig.switch != 1)
    }

    fun setConfig(pubAdUnit: String, adSizes: ArrayList<AdSize>, adType: String, section: String) {
        view.log { String.format("%s:%s- Version:%s", "setConfig", "entry", BuildConfig.ADAPTER_VERSION) }
        if (!shouldBeActive()) return
        if (sdkConfig?.getBlockList()?.any { pubAdUnit.contains(it, true) } == true) {
            shouldBeActive = false
            view.log { "Complete shutdown due to block" }
            return
        }
        val validConfig = sdkConfig?.refreshConfig?.firstOrNull { config -> config.specific?.equals(pubAdUnit, true) == true || config.type == adType || config.type.equals("all", true) }
        if (validConfig == null) {
            shouldBeActive = false
            return
        }
        this.adType = adType
        this.section = section
        this.pubAdSizes = adSizes
        bannerConfig.apply {
            instantRefresh = sdkConfig?.instantRefresh
            customUnitName = String.format("/%s/%s-%s", getNetworkName(), sdkConfig?.affiliatedId.toString(), getUnitNameType(validConfig.nameType ?: "", sdkConfig?.supportedSizes, adSizes))
            isNewUnit = pubAdUnit.contains(sdkConfig?.networkId ?: "")
            publisherAdUnit = pubAdUnit
            position = validConfig.position ?: 0
            placement = validConfig.placement
            newUnit = sdkConfig?.hijackConfig?.newUnit
            retryConfig = getRetryConfig()
            hijack = getValidLoadConfig(adType, true, sdkConfig?.hijackConfig, sdkConfig?.unfilledConfig)
            unFilled = getValidLoadConfig(adType, false, sdkConfig?.hijackConfig, sdkConfig?.unfilledConfig)
            difference = sdkConfig?.difference ?: 0
            activeRefreshInterval = sdkConfig?.activeRefreshInterval ?: 0
            passiveRefreshInterval = sdkConfig?.passiveRefreshInterval ?: 0
            factor = sdkConfig?.factor ?: 1
            visibleFactor = sdkConfig?.visibleFactor ?: 1
            minView = sdkConfig?.minView ?: 0
            minViewRtb = sdkConfig?.minViewRtb ?: 0
            format = validConfig.format
            fallback = sdkConfig?.fallback
            geoEdge = sdkConfig?.geoEdge
            nativeFallback = sdkConfig?.nativeFallback
            this.adSizes = if (validConfig.follow == 1 && !validConfig.sizes.isNullOrEmpty()) {
                getCustomSizes(adSizes, validConfig.sizes)
            } else {
                adSizes
            }
        }
        view.log { "setConfig :$bannerConfig" }
        setCountryConfig()
    }

    private fun getUnitNameType(type: String, supportedSizes: List<SDKConfig.Size>?, pubSizes: List<AdSize>): String {
        if (supportedSizes.isNullOrEmpty()) return type
        else {
            val matchedSizes = arrayListOf<SDKConfig.Size>()
            pubSizes.forEach { pubsize ->
                supportedSizes.firstOrNull { (it.width?.toIntOrNull() ?: 0) == pubsize.width && (it.height?.toIntOrNull() ?: 0) == pubsize.height }?.let { matchedSize ->
                    matchedSizes.add(matchedSize)
                }
            }
            var biggestSize: SDKConfig.Size? = null
            var maxArea = 0
            matchedSizes.forEach {
                if (maxArea < ((it.width?.toIntOrNull() ?: 0) * (it.height?.toIntOrNull() ?: 0))) {
                    biggestSize = it
                    maxArea = (it.width?.toIntOrNull() ?: 0) * (it.height?.toIntOrNull() ?: 0)
                }
            }
            return biggestSize?.let { String.format("%s-%s", it.width, it.height) } ?: type
        }
    }

    private fun getNetworkName(): String? {
        return if (sdkConfig?.networkCode.isNullOrEmpty()) sdkConfig?.networkId else String.format("%s,%s", sdkConfig?.networkId, sdkConfig?.networkCode)
    }

    private fun getValidLoadConfig(adType: String, forHijack: Boolean, hijackConfig: SDKConfig.LoadConfigs?, unfilledConfig: SDKConfig.LoadConfigs?): SDKConfig.LoadConfig? {
        var validConfig = when {
            adType.equals(AdTypes.BANNER, true) -> if (forHijack) hijackConfig?.banner else unfilledConfig?.banner
            adType.equals(AdTypes.INLINE, true) -> if (forHijack) hijackConfig?.inline else unfilledConfig?.inline
            adType.equals(AdTypes.ADAPTIVE, true) -> if (forHijack) hijackConfig?.adaptive else unfilledConfig?.adaptive
            adType.equals(AdTypes.INREAD, true) -> if (forHijack) hijackConfig?.inread else unfilledConfig?.inread
            adType.equals(AdTypes.STICKY, true) -> if (forHijack) hijackConfig?.sticky else unfilledConfig?.sticky
            else -> if (forHijack) hijackConfig?.other else unfilledConfig?.other
        }
        if (validConfig == null) {
            validConfig = if (forHijack) hijackConfig?.other else unfilledConfig?.other
        }
        return validConfig
    }

    private fun getCustomSizes(adSizes: ArrayList<AdSize>, sizeOptions: List<SDKConfig.Size>): ArrayList<AdSize> {
        val sizes = ArrayList<AdSize>()
        adSizes.forEach {
            val lookingWidth = if (it.width != 0) it.width.toString() else "ALL"
            val lookingHeight = if (it.height != 0) it.height.toString() else "ALL"
            sizeOptions.firstOrNull { size -> size.height == lookingHeight && size.width == lookingWidth }?.sizes?.forEach { selectedSize ->
                if (selectedSize.width == "ALL" || selectedSize.height == "ALL") {
                    sizes.add(it)
                } else if (sizes.none { size -> size.width == (selectedSize.width?.toIntOrNull() ?: 0) && size.height == (selectedSize.height?.toIntOrNull() ?: 0) }) {
                    sizes.add(AdSize((selectedSize.width?.toIntOrNull() ?: 0), (selectedSize.height?.toIntOrNull() ?: 0)))
                }
            }
        }
        return sizes
    }

    private fun getRetryConfig() = SDKConfig.RetryConfig(
            sdkConfig?.retryConfig?.retries,
            sdkConfig?.retryConfig?.retryInterval,
            arrayListOf<String>().apply { addAll(sdkConfig?.retryConfig?.adUnits ?: arrayListOf()) }
    )

    private fun setCountryConfig() {
        if (sdkConfig?.countryStatus?.active != 1) {
            if (((sdkConfig?.openRTb?.percentage ?: 0) != 0 || (sdkConfig?.openRTb?.interPercentage ?: 0) != 0) && (userLocation.first == null || (sdkConfig?.openRTb?.geoCode == 1 && userLocation.second == null))) {
                context.getLocation()?.let {
                    userLocation = userLocation.copy(first = it)
                    if (sdkConfig?.openRTb?.geoCode == 1 && userLocation.second == null) {
                        context.getAddress(it) { a ->
                            userLocation = userLocation.copy(second = a)
                        }
                    }
                }
            }
            return
        }
        if (!countrySetup.first || countrySetup.second || countrySetup.third == null || countrySetup.third?.countryCode.isNullOrEmpty() || sdkConfig?.homeCountry?.contains(countrySetup.third?.countryCode ?: "IN", true) == true) return
        bannerConfig = bannerConfig.apply {
            instantRefresh = sdkConfig?.instantRefresh
            val currentCountry = countrySetup.third?.countryCode ?: "IN"
            val validCountryConfig = sdkConfig?.countryConfigs?.firstOrNull { config -> config.name?.contains(currentCountry, true) == true || config.name?.contains("other") == true }
                    ?: return@apply
            val validRefreshConfig = validCountryConfig.refreshConfig?.firstOrNull { config ->
                config.specific?.equals(this.publisherAdUnit, true) == true
                        || config.type == adType || config.type.equals("all", true)
            }
            validRefreshConfig?.let {
                customUnitName = String.format("/%s/%s-%s", getNetworkName(), sdkConfig?.affiliatedId.toString(), getUnitNameType(it.nameType ?: "", validCountryConfig.supportedSizes, pubAdSizes))
                position = it.position ?: 0
                placement = it.placement
                format = it.format
                this.adSizes = if (it.follow == 1 && !it.sizes.isNullOrEmpty()) {
                    getCustomSizes(pubAdSizes, it.sizes)
                } else {
                    pubAdSizes
                }
            }
            validCountryConfig.hijackConfig?.newUnit?.let { newUnit = it }
            validCountryConfig.hijackConfig?.let { hijack = getValidLoadConfig(adType, true, it, null) }
            validCountryConfig.unfilledConfig?.let { unFilled = getValidLoadConfig(adType, false, null, it) }
            validCountryConfig.diff?.let { difference = it }
            validCountryConfig.activeRefreshInterval?.let { activeRefreshInterval = it }
            validCountryConfig.passiveRefreshInterval?.let { passiveRefreshInterval = it }
            validCountryConfig.factor?.let { factor = it }
            validCountryConfig.visibleFactor?.let { visibleFactor = it }
            validCountryConfig.minView?.let { minView = it }
            validCountryConfig.minViewRtb?.let { minViewRtb = it }
            validCountryConfig.fallback?.let { fallback = it }
            validCountryConfig.geoEdge?.let { geoEdge = it }
            validCountryConfig.nativeFallback?.let { nativeFallback = it }
        }
        countrySetup = Triple(countrySetup.first, true, countrySetup.third)
        view.log { "set CountryWise Config: $bannerConfig" }
    }

    @Suppress("KotlinConstantConditions")
    fun saveVisibility(visible: Boolean) {
        if (visible == bannerConfig.isVisible) return
        var tryInstantRefresh = false
        bannerConfig.isVisible?.let { savedVisibility ->
            tryInstantRefresh = !savedVisibility && visible
        }
        bannerConfig.isVisible = visible
        if (tryInstantRefresh && bannerConfig.instantRefresh == 1) {
            refresh(0, unfilled = false, instantRefresh = true)
        }
    }

    fun checkDetachDetect(): Boolean {
        return shouldBeActive && (sdkConfig?.detectDetach ?: 1) == 1
    }

    fun adReported(creativeId: String?, reportReasons: List<String>) {
        if (bannerConfig.geoEdge?.creativeIds?.replace(" ", "")?.split(",")?.contains(creativeId) == true) {
            refreshBlocked = true
        }
        val configReasons = bannerConfig.geoEdge?.reasons?.replace(" ", "")?.split(",")
        configReasons?.forEach { reason ->
            if (reportReasons.any { reason.contains(it) }) {
                refreshBlocked = true
            }
        }
        if (refreshBlocked) {
            activeTimeCounter?.cancel()
            passiveTimeCounter?.cancel()
            unfilledRefreshCounter?.cancel()
        }
    }

    fun adFailedToLoad(isPublisherLoad: Boolean, recalled: Boolean = false): Boolean {
        if (!recalled) {
            setCountryConfig()
            view.log { "Failed with Unfilled Config: ${Gson().toJson(bannerConfig.unFilled)} && Retry config : ${Gson().toJson(bannerConfig.retryConfig)} && isPubload : $isPublisherLoad" }
        }

        if (shouldBeActive) {
            if (isPublisherLoad && !bannerConfig.isNewUnitApplied()) {
                return if (bannerConfig.unFilled?.status == 1) {
                    if (bannerConfig.unFilled?.regionWise == 1 && (countrySetup.third == null || isRegionBlocked() || ifUnitOnRegionalHold(bannerConfig.publisherAdUnit) || ifSectionOnRegionalHold(section))) {
                        false
                    } else {
                        refresh(unfilled = true)
                        true
                    }
                } else {
                    false
                }
            } else {
                if ((bannerConfig.retryConfig?.retries ?: 0) > 0) {
                    bannerConfig.retryConfig?.retries = (bannerConfig.retryConfig?.retries ?: 0) - 1
                    Handler(Looper.getMainLooper()).postDelayed({
                        bannerConfig.retryConfig?.adUnits?.firstOrNull()?.let {
                            bannerConfig.retryConfig?.adUnits?.removeAt(0)
                            overridingUnit = it
                            refresh(unfilled = true)
                        } ?: kotlin.run {
                            overridingUnit = null
                        }
                    }, (bannerConfig.retryConfig?.retryInterval ?: 0).toLong() * 1000)
                    return true
                } else {
                    overridingUnit = null
                    return false
                }
            }
        } else {
            return false
        }
    }

    fun adLoaded(firstLook: Boolean, loadedUnit: String, loadedAdapter: AdapterResponseInfo?) {
        adImpressed()
        setCountryConfig()
        if (sdkConfig?.switch == 1 && !refreshBlocked && !isInter && shouldBeActive) {
            overridingUnit = null
            bannerConfig.retryConfig = getRetryConfig()
            unfilledRefreshCounter?.cancel()
            val blockedTerms = sdkConfig?.networkBlock?.replace(" ", "")?.split(",") ?: listOf()
            var isNetworkBlocked = false
            blockedTerms.forEach {
                if (it.isNotEmpty() && loadedAdapter?.adapterClassName?.contains(it, true) == true) {
                    isNetworkBlocked = true
                }
            }


            if (!isNetworkBlocked && !isRegionBlocked()
                    && !(!loadedAdapter?.adSourceId.isNullOrEmpty() && blockedTerms.contains(loadedAdapter?.adSourceId))
                    && !(!loadedAdapter?.adSourceName.isNullOrEmpty() && blockedTerms.contains(loadedAdapter?.adSourceName))
                    && !(!loadedAdapter?.adSourceInstanceId.isNullOrEmpty() && blockedTerms.contains(loadedAdapter?.adSourceInstanceId))
                    && !(!loadedAdapter?.adSourceInstanceName.isNullOrEmpty() && blockedTerms.contains(loadedAdapter?.adSourceInstanceName))
                    && !ifUnitOnHold(loadedUnit) && !ifUnitOnRegionalHold(loadedUnit) && !ifSectionOnRegionalHold(section)) {
                startRefreshing(resetVisibleTime = true, isPublisherLoad = firstLook)
            } else {
                refreshBlocked = true
                view.log { "Refresh blocked" }
                passiveTimeCounter?.cancel()
                activeTimeCounter?.cancel()
            }
        }
    }

    fun adImpressed() {
        val currentTimeStamp = Date().time
        bannerConfig.lastRefreshAt = currentTimeStamp
    }

    private fun startRefreshing(resetVisibleTime: Boolean = false, isPublisherLoad: Boolean = false, timers: Int? = null) {
        view.log { "startRefreshing: resetVisibleTime: $resetVisibleTime isPublisherLoad: $isPublisherLoad timers: $timers passive : ${bannerConfig.passiveRefreshInterval} active: ${bannerConfig.activeRefreshInterval}" }
        if (resetVisibleTime) {
            bannerConfig.isVisibleFor = 0
        }
        this.wasFirstLook = isPublisherLoad
        bannerConfig.let {
            timers?.let { active ->
                when (active) {
                    0 -> startPassiveCounter(it.passiveRefreshInterval.toLong())
                    1 -> startActiveCounter(it.activeRefreshInterval.toLong())
                    2 -> startUnfilledRefreshCounter()
                }
            } ?: kotlin.run {
                startPassiveCounter(it.passiveRefreshInterval.toLong())
                startActiveCounter(it.activeRefreshInterval.toLong())
            }
        }
    }

    private fun startActiveCounter(seconds: Long) {
        if (seconds <= 0) return
        activeTimeCounter?.cancel()
        activeTimeCounter = object : CountDownTimer(seconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                CoroutineScope(Dispatchers.IO).launch {
                    if (bannerConfig.isVisible == true) {
                        bannerConfig.isVisibleFor++
                    }
                    bannerConfig.activeRefreshInterval--
                }
            }

            override fun onFinish() {
                CoroutineScope(Dispatchers.IO).launch {
                    bannerConfig.activeRefreshInterval = sdkConfig?.activeRefreshInterval ?: 0
                }
                refresh(1)
            }
        }
        activeTimeCounter?.start()
    }

    private fun startPassiveCounter(seconds: Long) {
        if (seconds <= 0) return
        passiveTimeCounter?.cancel()
        passiveTimeCounter = object : CountDownTimer(seconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                CoroutineScope(Dispatchers.IO).launch {
                    bannerConfig.passiveRefreshInterval--
                }
            }

            override fun onFinish() {
                CoroutineScope(Dispatchers.IO).launch {
                    bannerConfig.passiveRefreshInterval = sdkConfig?.passiveRefreshInterval ?: 0
                }
                refresh(0)
            }
        }
        passiveTimeCounter?.start()
    }

    fun startUnfilledRefreshCounter() {
        if (isInter) return
        activeTimeCounter?.cancel()
        passiveTimeCounter?.cancel()
        val time = sdkConfig?.unfilledTimerConfig?.time?.toLong() ?: 0L
        if (time <= 0) return
        view.log { "Unfilled timer started with time :$time" }
        unfilledRefreshCounter?.cancel()
        unfilledRefreshCounter = object : CountDownTimer(time * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {

            }

            override fun onFinish() {
                refresh(0, true, fixedUnit = sdkConfig?.unfilledTimerConfig?.unit)
            }
        }
        unfilledRefreshCounter?.start()
    }

    fun refresh(active: Int = 1, unfilled: Boolean = false, instantRefresh: Boolean = false, fixedUnit: String? = null) {
        if (!shouldBeActive || refreshBlocked || bannerConfig.adSizes.isEmpty()) return
        view.log { "Trying opportunity: active = $active, retrying = $unfilled, instant = $instantRefresh" }
        val currentTimeStamp = Date().time
        fun refreshAd() {
            bannerConfig.lastRefreshAt = currentTimeStamp
            view.log { "Opportunity Taken: active = $active, retrying = $unfilled, instant = $instantRefresh" }
            bannerListener.attachAdView(fixedUnit ?: getAdUnitName(unfilled, false), bannerConfig.adSizes.apply {
                if (ifNativePossible() && !this.contains(AdSize.FLUID)) {
                    add(AdSize.FLUID)
                }
            }, false)
            loadAd(active, unfilled, instantRefresh)
        }

        val differenceOfLastRefresh = ceil((currentTimeStamp - bannerConfig.lastRefreshAt).toDouble() / 1000.00).toInt()
        var timers = if (active == 0 && unfilled) {
            2
        } else {
            active
        }
        if (instantRefresh) {
            timers = 3
        }
        var takeOpportunity = false
        if (active == 1) {
            var pickOpportunity = false
            if (bannerConfig.isVisible == true) {
                pickOpportunity = true
            } else {
                if (bannerConfig.visibleFactor < 0) {
                    pickOpportunity = false
                } else {
                    if (ceil((currentTimeStamp - bannerConfig.lastActiveOpportunity).toDouble() / 1000.00).toInt() >= bannerConfig.visibleFactor * bannerConfig.activeRefreshInterval) {
                        pickOpportunity = true
                    }
                }
            }
            if (pickOpportunity) {
                bannerConfig.lastActiveOpportunity = currentTimeStamp
                if (differenceOfLastRefresh >= bannerConfig.difference && (bannerConfig.isVisibleFor >= (if (!wasFirstLook || bannerConfig.isNewUnitApplied()) bannerConfig.minViewRtb else bannerConfig.minView))) {
                    takeOpportunity = true
                }
            }
        } else if (active == 0) {
            var pickOpportunity = false
            if (isForegroundRefresh == 1) {
                if (bannerConfig.isVisible == true) {
                    pickOpportunity = true
                } else {
                    if (bannerConfig.factor < 0) {
                        pickOpportunity = false
                    } else {
                        if (ceil((currentTimeStamp - bannerConfig.lastPassiveOpportunity).toDouble() / 1000.00).toInt() >= bannerConfig.factor * bannerConfig.passiveRefreshInterval) {
                            pickOpportunity = true
                        }
                    }
                }
            } else {
                if (bannerConfig.factor < 0) {
                    pickOpportunity = false
                } else {
                    if (ceil((currentTimeStamp - bannerConfig.lastPassiveOpportunity).toDouble() / 1000.00).toInt() >= bannerConfig.factor * bannerConfig.passiveRefreshInterval) {
                        pickOpportunity = true
                    }
                }
            }
            if (pickOpportunity) {
                bannerConfig.lastPassiveOpportunity = currentTimeStamp
                if (differenceOfLastRefresh >= bannerConfig.difference && (bannerConfig.isVisibleFor >= (if (!wasFirstLook || bannerConfig.isNewUnitApplied()) bannerConfig.minViewRtb else bannerConfig.minView))) {
                    takeOpportunity = true
                }
            }
        }

        if (RTBDemand.connectionAvailable() == true && isForegroundRefresh == 1 && (unfilled || takeOpportunity) && canRefresh()) {
            refreshAd()
        } else {
            startRefreshing(timers = timers)
        }
    }

    private fun canRefresh(): Boolean {
        return if (sdkConfig?.forceImpression != 1) {
            true
        } else !pendingImpression
    }

    private fun createRequest(active: Int,
                              unfilled: Boolean = false,
                              hijacked: Boolean = false,
                              instant: Boolean = false,
                              newUnit: Boolean = false) = AdRequest().Builder().apply {
        addCustomTargeting("adunit", bannerConfig.publisherAdUnit)
        addCustomTargeting("active", active.toString())
        addCustomTargeting("refresh", bannerConfig.refreshCount.toString())
        addCustomTargeting("hb_format", sdkConfig?.hbFormat ?: "amp")
        addCustomTargeting("visible", isForegroundRefresh.toString())
        addCustomTargeting("min_view", (if (bannerConfig.isVisibleFor > 10) 10 else bannerConfig.isVisibleFor).toString())
        addCustomTargeting("sdk_version", BuildConfig.ADAPTER_VERSION)
        if (unfilled) addCustomTargeting("retry", "1")
        if (hijacked) addCustomTargeting("hijack", "1")
        if (isInter) addCustomTargeting("instl", "1")
        if (instant) addCustomTargeting("instant", "1")
        if (newUnit) addCustomTargeting("new_unit", "1")
    }.build()

    private fun loadAd(active: Int, unfilled: Boolean, instant: Boolean) {
        if (bannerConfig.refreshCount < 10) {
            bannerConfig.refreshCount++
        } else {
            bannerConfig.refreshCount = 10
        }
        bannerListener.loadAd(createRequest(active = active, unfilled = unfilled, instant = instant))
    }

    fun checkOverride(): AdManagerAdRequest? {
        if (bannerConfig.isNewUnitApplied()) {
            view.log { "checkOverride on ${bannerConfig.publisherAdUnit}, status : new unit" }
            bannerListener.attachAdView(getAdUnitName(unfilled = false, hijacked = false, newUnit = true), bannerConfig.adSizes.apply {
                if (ifNativePossible() && !this.contains(AdSize.FLUID)) {
                    add(AdSize.FLUID)
                }
            }, true)
            return createRequest(active = 1, newUnit = true).getAdRequest()
        } else if (checkHijack(bannerConfig.hijack)) {
            view.log { "checkOverride on ${bannerConfig.publisherAdUnit}, status : hijack" }
            bannerListener.attachAdView(getAdUnitName(unfilled = false, hijacked = true, newUnit = false), bannerConfig.adSizes.apply {
                if (ifNativePossible() && !this.contains(AdSize.FLUID)) {
                    add(AdSize.FLUID)
                }
            }, true)
            return createRequest(active = 1, hijacked = true).getAdRequest()
        }
        return null
    }

    private fun checkHijack(hijackConfig: SDKConfig.LoadConfig?): Boolean {
        if (hijackConfig?.regionWise == 1) {
            return if (countrySetup.third == null) {
                false
            } else {
                if (hijackConfig.status == 1) {
                    if (ifUnitOnRegionalHold(bannerConfig.publisherAdUnit) || isRegionBlocked() || ifSectionOnRegionalHold(section)) {
                        false
                    } else {
                        val per = getRegionalHijackPercentage()
                        val number = (1..100).random()
                        number in 1..per
                    }
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

    fun checkGeoEdge(firstLook: Boolean, callback: () -> Unit) {
        val number = (1..100).random()
        var firstLookPer = 0
        var otherPer = 0
        if (sdkConfig?.countryStatus?.active == 1 &&
                (!sdkConfig?.geoEdge?.whitelistedRegions?.getCities().isNullOrEmpty()
                        || !sdkConfig?.geoEdge?.whitelistedRegions?.getStates().isNullOrEmpty()
                        || !sdkConfig?.geoEdge?.whitelistedRegions?.getCountries().isNullOrEmpty())
        ) {
            if (sdkConfig?.geoEdge?.whitelistedRegions?.getCities()?.any { it.equals(countrySetup.third?.city, true) } == true ||
                    (sdkConfig?.geoEdge?.whitelistedRegions?.getStates()?.any { it.equals(countrySetup.third?.state, true) } == true) ||
                    (sdkConfig?.geoEdge?.whitelistedRegions?.getCountries()?.any { it.equals(countrySetup.third?.countryCode, true) } == true)) {
                firstLookPer = bannerConfig.geoEdge?.firstLook ?: 0
                otherPer = bannerConfig.geoEdge?.other ?: 0
            }
        } else {
            firstLookPer = bannerConfig.geoEdge?.firstLook ?: 0
            otherPer = bannerConfig.geoEdge?.other ?: 0
        }

        if ((firstLook && (number in 1..firstLookPer)) || (!firstLook && (number in 1..otherPer))) {
            callback()
        }
    }

    fun fetchDemand(firstLook: Boolean, adRequest: AdManagerAdRequest, callback: (AdManagerAdRequest) -> Unit) {
        RTBDemand.initPrebid()
        var prebidAvailable = if (
                (firstLook && !bannerConfig.isNewUnitApplied() && sdkConfig?.prebid?.firstLook == 1) ||
                (firstLook && bannerConfig.isNewUnitApplied() && sdkConfig?.prebid?.other == 1) ||
                (!firstLook && sdkConfig?.prebid?.other == 1)
        ) {
            bannerConfig.placement != null && bannerConfig.adSizes.isNotEmpty()
        } else {
            false
        }

        var apsAvailable = (firstLook && !bannerConfig.isNewUnitApplied() && sdkConfig?.aps?.firstLook == 1) ||
                (firstLook && bannerConfig.isNewUnitApplied() && sdkConfig?.aps?.other == 1) ||
                (!firstLook && sdkConfig?.aps?.other == 1)

        if (adRequest.customTargeting.getString("retry") == "1") {
            if ((sdkConfig?.prebid?.retry ?: 0) == 0) {
                prebidAvailable = false
            }
            if ((sdkConfig?.aps?.retry ?: 0) == 0) {
                apsAvailable = false
            }
        }
        if (sdkConfig?.prebid?.whitelistedFormats != null && sdkConfig?.prebid?.whitelistedFormats?.contains(AdTypes.BANNER) == false) {
            prebidAvailable = false
        }
        if (sdkConfig?.aps?.whitelistedFormats != null && sdkConfig?.aps?.whitelistedFormats?.contains(AdTypes.BANNER) == false) {
            apsAvailable = false
        }

        if (!shouldBeActive) {
            prebidAvailable = false
            apsAvailable = false
        }

        fun prebid(apsRequestBuilder: AdManagerAdRequest.Builder? = null) = bannerConfig.placement?.let {
            val totalSizes = bannerConfig.adSizes
            val firstAdSize = totalSizes[0]
            val formatNeeded = bannerConfig.format
            val adUnit = if (formatNeeded.isNullOrEmpty() || (formatNeeded.contains("html", true) && !formatNeeded.contains("video", true))) {
                BannerAdUnit(if (firstLook) it.firstLook ?: "" else it.other ?: "", firstAdSize.width, firstAdSize.height)
            } else if (formatNeeded.contains("video", true) && !formatNeeded.contains("html", true)) {
                BannerAdUnit(if (firstLook) it.firstLook ?: "" else it.other ?: "", firstAdSize.width, firstAdSize.height, EnumSet.of(AdUnitFormat.VIDEO)).apply {
                    videoParameters = configureVideoParameters()
                }
            } else {
                BannerAdUnit(if (firstLook) it.firstLook ?: "" else it.other ?: "", firstAdSize.width, firstAdSize.height, EnumSet.of(AdUnitFormat.VIDEO, AdUnitFormat.BANNER)).apply {
                    videoParameters = configureVideoParameters()
                }
            }
            if (!sdkConfig?.prebid?.bannerAPIParameters.isNullOrEmpty()) {
                adUnit.bannerParameters = BannerParameters().apply {
                    api = sdkConfig?.prebid?.bannerAPIParameters?.map { number -> Api(number) }
                }
            }
            totalSizes.forEach { adSize -> adUnit.addAdditionalSize(adSize.width, adSize.height) }
            val finalRequest = apsRequestBuilder?.let { apsRequestBuilder ->
                adRequest.customTargeting.keySet().forEach { key ->
                    apsRequestBuilder.addCustomTargeting(key, adRequest.customTargeting.getString(key, ""))
                }
                apsRequestBuilder.build()
            } ?: adRequest

            adUnit.fetchDemand(finalRequest) {
                view.log { "Demand fetched aps : ${apsRequestBuilder != null} && prebid : completed" }
                callback(finalRequest)
            }
        }

        fun aps(wait: Boolean) {
            var actionTaken = false
            val matchingSlots = arrayListOf<DTBAdSize>()
            bannerConfig.adSizes.forEach { size ->
                sdkConfig?.aps?.slots?.filter { slot -> slot.height?.toIntOrNull() == size.height && slot.width?.toIntOrNull() == size.width }?.forEach {
                    matchingSlots.add(DTBAdSize(it.width?.toIntOrNull() ?: 0, it.height?.toIntOrNull() ?: 0, it.slotId ?: ""))
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
                    if (wait) {
                        prebid(DTBAdUtil.INSTANCE.createAdManagerAdRequestBuilder(dtbAdResponse))
                    } else {
                        val apsRequest = DTBAdUtil.INSTANCE.createAdManagerAdRequestBuilder(dtbAdResponse)
                        adRequest.customTargeting.keySet().forEach { key ->
                            apsRequest.addCustomTargeting(key, adRequest.customTargeting.getString(key, ""))
                        }
                        callback(apsRequest.build())
                    }
                }
            }

            if (matchingSlots.isEmpty()) {
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
        view.log { "Fetch Demand with aps : $apsAvailable and with prebid : $prebidAvailable" }
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
            api = listOf(Api.VPAID_1, Api.VPAID_2)
            maxBitrate = 1500
            minBitrate = 300
            maxDuration = 30
            minDuration = 5
            playbackMethod = listOf(Signals.PlaybackMethod.AutoPlaySoundOn)
            protocols = listOf(Signals.Protocols.VAST_2_0)
        }
    }


    private fun getAdUnitName(unfilled: Boolean, hijacked: Boolean, newUnit: Boolean = false): String {
        return overridingUnit ?: String.format(Locale.ENGLISH, "%s-%d", bannerConfig.customUnitName,
                if (unfilled) bannerConfig.unFilled?.number else if (newUnit) bannerConfig.newUnit?.number else if (hijacked) bannerConfig.hijack?.number else bannerConfig.position)
    }

    fun adPaused() {
        isForegroundRefresh = 0
        activeTimeCounter?.cancel()
    }

    fun adResumed() {
        isForegroundRefresh = 1
        if (bannerConfig.adSizes.isNotEmpty()) {
            startActiveCounter(bannerConfig.activeRefreshInterval.toLong())
        }
    }

    fun adDestroyed() {
        activeTimeCounter?.cancel()
        passiveTimeCounter?.cancel()
        unfilledRefreshCounter?.cancel()
    }

    fun allowCallback(refreshLoad: Boolean): Boolean {
        return !refreshLoad || sdkConfig?.infoConfig?.refreshCallbacks == 1
    }

    fun checkFallback(refreshLoad: Boolean, errorCode: Int, adEverLoaded: Boolean): Int {
        bannerConfig.retryConfig = getRetryConfig()
        if (isInter) {
            return if (errorCode == -1) 0 else -1
        }
        if (sdkConfig?.seemlessRefresh == 1 && sdkConfig?.seemlessRefreshFallback != 1 && adEverLoaded) {
            return 0
        }
        if ((!refreshLoad && bannerConfig.fallback?.firstlook == 1) && bannerConfig.unFilled?.regionWise == 1
                && (countrySetup.third == null || isRegionBlocked() || ifUnitOnRegionalHold(bannerConfig.publisherAdUnit) || ifSectionOnRegionalHold(section))) {
            return 0
        }
        if ((!refreshLoad && bannerConfig.fallback?.firstlook == 1) || (refreshLoad && bannerConfig.fallback?.other == 1)) {
            val matchedBanners = arrayListOf<Fallback.Banner>()
            pubAdSizes.forEach { pubSize ->
                bannerConfig.fallback?.banners?.firstOrNull { (it.width?.toIntOrNull() ?: 0) == pubSize.width && (it.height?.toIntOrNull() ?: 0) == pubSize.height }?.let { matchedSize ->
                    matchedBanners.add(matchedSize)
                }
            }
            var biggestBanner: Fallback.Banner? = null
            var maxArea = 0
            matchedBanners.forEach {
                if (maxArea < ((it.width?.toIntOrNull() ?: 0) * (it.height?.toIntOrNull() ?: 0))) {
                    biggestBanner = it
                    maxArea = (it.width?.toIntOrNull() ?: 0) * (it.height?.toIntOrNull() ?: 0)
                }
            }

            if (biggestBanner == null) {
                var biggestPubSize: AdSize? = null
                maxArea = 0
                pubAdSizes.forEach {
                    if (maxArea < (it.width * it.height)) {
                        biggestPubSize = it
                        maxArea = (it.width * it.height)
                    }
                }
                biggestBanner = bannerConfig.fallback?.banners?.firstOrNull { it.height.equals("all", true) && it.width.equals("all", true) }?.apply {
                    height = biggestPubSize?.height.toString()
                    width = biggestPubSize?.width.toString()
                }
            }

            biggestBanner?.let { bannerListener.attachFallback(it) }
            return if (biggestBanner != null && ((biggestBanner?.height?.toIntOrNull() ?: 0) != 0 && (biggestBanner?.width?.toIntOrNull() ?: 0) != 0)) 1 else 0
        } else {
            return 0
        }
    }

    private fun ifNativePossible(): Boolean {
        return if (bannerConfig.nativeFallback != 1) {
            false
        } else {
            var maxArea: Int
            var biggestPubSize: AdSize? = null
            maxArea = 0
            pubAdSizes.forEach {
                if (maxArea < (it.width * it.height)) {
                    biggestPubSize = it
                    maxArea = (it.width * it.height)
                }
            }
            (biggestPubSize != null && biggestPubSize!!.height > 120 && biggestPubSize!!.width > 120)
        }
    }

    private fun ifUnitOnHold(adUnit: String): Boolean {
        val hold = sdkConfig?.heldUnits?.any { adUnit.contains(it, true) } == true || sdkConfig?.heldUnits?.any { it.contains("all", true) } == true
        if (hold) {
            view.log { "Blocking refresh on : $adUnit" }
        }
        return hold
    }

    private fun isRegionBlocked(): Boolean {
        var isRegionBlocked = false
        if (sdkConfig?.countryStatus?.active == 1 &&
                (sdkConfig?.blockedRegions?.getCities()?.any { it.equals(countrySetup.third?.city, true) } == true ||
                        (sdkConfig?.blockedRegions?.getStates()?.any { it.equals(countrySetup.third?.state, true) } == true) ||
                        (sdkConfig?.blockedRegions?.getCountries()?.any { it.equals(countrySetup.third?.countryCode, true) } == true))
        ) {
            isRegionBlocked = true
        }
        return isRegionBlocked
    }

    private fun ifUnitOnRegionalHold(adUnit: String): Boolean {
        var hold = false
        if (sdkConfig?.countryStatus?.active == 1) {
            sdkConfig?.regionalHalts?.forEach { region ->
                if ((region.getCities().any { it.equals(countrySetup.third?.city, true) }
                                || region.getStates().any { it.equals(countrySetup.third?.state, true) }
                                || region.getCountries().any { it.equals(countrySetup.third?.countryCode, true) })
                        && (region.units?.any { adUnit.contains(it, true) } == true)) {
                    hold = true
                }
            }
        }
        if (hold) {
            view.log { "Regional blocking refresh on unit : $adUnit" }
        }
        return hold
    }

    private fun ifSectionOnRegionalHold(section: String): Boolean {
        var hold = false
        if (sdkConfig?.countryStatus?.active == 1) {
            sdkConfig?.sectionRegionalHalt?.forEach { region ->
                if ((region.getCities().any { it.equals(countrySetup.third?.city, true) }
                                || region.getStates().any { it.equals(countrySetup.third?.state, true) }
                                || region.getCountries().any { it.equals(countrySetup.third?.countryCode, true) })
                        && (region.sections?.any { section.contains(it, true) } == true)) {
                    hold = true
                }
            }
        }
        if (hold) {
            view.log { "Regional blocking refresh on section : $section" }
        }
        return hold
    }

    private fun getRegionalHijackPercentage(): Int {
        var percentage = 0
        if (sdkConfig?.countryStatus?.active == 1 && countrySetup.third != null) {
            bannerConfig.hijack?.regionalPercentage?.firstOrNull { region ->
                region.getCities().any { it.equals(countrySetup.third?.city, true) }
                        || region.getStates().any { it.equals(countrySetup.third?.state, true) }
                        || region.getCountries().any { it.equals(countrySetup.third?.countryCode, true) }
                        || region.getCountries().any { it.equals("default", true) }
            }?.let {
                percentage = it.percentage ?: 0
            }
        }
        return percentage
    }

    fun initiateOpenRTB(adSize: AdSize, onResponse: (Pair<String, String>) -> Unit, onFailure: () -> Unit): Boolean {
        if (sdkConfig?.openRTb == null || sdkConfig?.openRTb?.url.isNullOrEmpty() || sdkConfig?.openRTb?.request.isNullOrEmpty()) return false
        val percentage = (if (isInter) sdkConfig?.openRTb?.interPercentage else sdkConfig?.openRTb?.percentage) ?: 0
        if ((1..100).random() !in 1..percentage) return false
        val urlBuilder = sdkConfig?.openRTb?.url?.toHttpUrlOrNull() ?: return false
        view.log { "Will call open rtb for : ${adSize.width}*${adSize.height}" }
        val openRTB = sdkConfig?.openRTb!!
        val requestBody = prepareRequestBody(openRTB, adSize)
        val loggingInterceptor = HttpLoggingInterceptor().setLevel(if (RTBDemand.specialTag.isNullOrEmpty()) HttpLoggingInterceptor.Level.NONE else HttpLoggingInterceptor.Level.BODY)
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(loggingInterceptor)
                .connectTimeout((openRTB.timeout ?: 1000).toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout((openRTB.timeout ?: 1000).toLong(), TimeUnit.MILLISECONDS)
                .readTimeout((openRTB.timeout ?: 1000).toLong(), TimeUnit.MILLISECONDS).build()
        val request: Request = Request.Builder().url(urlBuilder).apply {
            openRTB.headers?.forEach { addHeader(it.key ?: "", it.value ?: "") }
        }.method("POST", requestBody).build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                CoroutineScope(Dispatchers.Main).launch { onFailure() }
            }

            override fun onResponse(call: Call, response: Response) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val responseData = JSONObject(response.body.string())
                        val seatBids = responseData.optJSONArray("seatbid")
                        if (seatBids?.isNull(0) == false) {
                            val firstBidList = seatBids[0] as? JSONObject
                            val bids = firstBidList?.optJSONArray("bid")
                            if (bids?.isNull(0) == false) {
                                val firstBid = bids[0] as JSONObject
                                val imageUrl = firstBid.optString("iurl")
                                val scriptUrl = firstBid.optString("adm")
                                onResponse(Pair(imageUrl, scriptUrl))
                            } else {
                                onFailure()
                            }
                        } else {
                            onFailure()
                        }
                    } catch (t: Throwable) {
                        onFailure()
                        view.log { "Could not parse openRTB response because : ${t.localizedMessage}" }
                    }
                }
            }

        })
        return true
    }

    private fun prepareRequestBody(openRTBConfig: SDKConfig.OpenRTBConfig, adSize: AdSize): RequestBody {
        val demoRequest = openRTBConfig.request
        if (demoRequest.isNullOrEmpty()) return "".toRequestBody()
        val uniqueId = getUniqueId()
        val heightWidth = try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                (context as? Activity)?.windowManager?.defaultDisplay
            }
            val size = Point()
            display?.getSize(size)
            val width = size.x
            val height = size.y
            Pair(width, height)
        } catch (e: Throwable) {
            Pair(0, 0)
        }

        val simInfo = try {
            val telephonyManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(TelephonyManager::class.java)
            } else {
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            }
            var mcc_mnc = ""
            if (telephonyManager.networkOperator.isNotEmpty()) {
                val operator = telephonyManager.networkOperator
                if (operator.length >= 3) {
                    mcc_mnc = operator.substring(0, 3)
                }
                if (operator.length > 3) {
                    mcc_mnc = "${mcc_mnc}-${operator.substring(3)}"
                }
            }
            Pair(mcc_mnc, telephonyManager.simOperatorName)
        } catch (e: Throwable) {
            Pair("", "")
        }
        val connectionType = try {
            val connectivityManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(ConnectivityManager::class.java)
            } else {
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            }
            if (connectivityManager.isActiveNetworkMetered) "1" else "2"
        } catch (e: Throwable) {
            ""
        }
        val ext = hashMapOf<String, String>()
        var schain = ""
        sdkConfig?.prebid?.extParams?.forEach {
            ext[it.key ?: ""] = it.value ?: ""
            if (it.key == "schain") {
                schain = it.value ?: ""
            }
        }
        val geo = HashMap<String, Any>()
        countrySetup.third?.let {
            geo["lat"] = it.latitude ?: 0.0
            geo["lon"] = it.longitude ?: 0.0
            geo["type"] = 2
            geo["country"] = getCountry(it.countryCode ?: "")
            geo["city"] = it.city ?: ""
            geo["region"] = it.state ?: ""
            geo["zip"] = it.zip ?: ""
            geo["ipservice"] = 4
        } ?: userLocation.first?.let {
            geo["lat"] = it.latitude
            geo["lon"] = it.longitude
            geo["city"] = userLocation.second?.locality ?: ""
            geo["zip"] = userLocation.second?.postalCode ?: ""
            geo["type"] = 1
            geo["country"] = getCountry(userLocation.second?.countryCode ?: "")
        }
        val request = demoRequest.replace("{id}", uniqueId)
                .replace("{name}", context.packageName.replace(".", ""))
                .replace("{bundle}", context.packageName)
                .replace("{domain}", sdkConfig?.prebid?.domain ?: "")
                .replace("{storeurl}", sdkConfig?.prebid?.storeURL ?: "")
                .replace("{version}", BuildConfig.ADAPTER_VERSION)
                .replace("{sizes}", Gson().toJson(arrayListOf(hashMapOf("w" to adSize.width, "h" to adSize.height))))
                .replace("{bh}", adSize.height.toString())
                .replace("{bw}", adSize.width.toString())
                .replace("{sdkver}", MobileAds.getVersion().toString())
                .replace("{os}", "Android")
                .replace("{osv}", Build.VERSION.RELEASE)
                .replace("{ifa}", context.getHardwareDeviceId())
                .replace("{make}", Build.MANUFACTURER)
                .replace("{model}", Build.MODEL)
                .replace("{ua}", WebView(context).settings.userAgentString)
                .replace("{w}", heightWidth.first.toString())
                .replace("{h}", heightWidth.second.toString())
                .replace("{pxratio}", (if (heightWidth.first > 0) heightWidth.second.toFloat() / heightWidth.first.toFloat() else 0).toString())
                .replace("{mccmnc}", simInfo.first)
                .replace("{carier}", simInfo.second)
                .replace("{type}", connectionType)
                .replace("{ext}", Gson().toJson(ext))
                .replace("{geo}", Gson().toJson(geo))
                .replace("{ip}", countrySetup.third?.ip ?: "")
                .replace("{tagid}", openRTBConfig.tagId ?: "")
                .replace("{pubid}", openRTBConfig.pubId ?: "")
                .replace("{schain}", schain)
                .replace("{instl}", (if (isInter) 1 else 0).toString())
        return request.toRequestBody()
    }

    fun attachScript(currentAdUnit: String, loadedSize: AdSize?): String? {
        val number = (1..100).random()
        if (number !in 1..(sdkConfig?.trackingConfig?.percentage ?: 0)) {
            return null
        }
        return if (loadedSize == null || currentAdUnit == bannerConfig.publisherAdUnit || sdkConfig?.trackingConfig?.getScript().isNullOrEmpty()) {
            null
        } else {
            try {
                sdkConfig?.trackingConfig?.getScript()
                        ?.replace("%%ADUNIT%%", currentAdUnit)
                        ?.replace("%%HEIGHT%%", loadedSize.height.toString())
                        ?.replace("%%WIDTH%%", loadedSize.width.toString())
                        ?.replace("[CACHEBUSTING]", (0..Int.MAX_VALUE).random().toString())
                        ?.replace("\$ADLOOX_WEBSITE", sdkConfig?.prebid?.domain ?: context.packageName)
                        ?.replace("%%SITE%%", context.packageName)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun getBiggestSize(): AdSize {
        var biggestPubSize: AdSize? = null
        var maxArea = 0
        pubAdSizes.filter { it != AdSize.FLUID }.forEach {
            if (maxArea < (it.width * it.height)) {
                biggestPubSize = it
                maxArea = (it.width * it.height)
            }
        }
        return biggestPubSize ?: AdSize.MEDIUM_RECTANGLE
    }
}