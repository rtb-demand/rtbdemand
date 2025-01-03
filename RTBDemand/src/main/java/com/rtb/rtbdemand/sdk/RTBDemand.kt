package com.rtb.rtbdemand.sdk

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.work.WorkManager
import com.amazon.device.ads.AdRegistration
import com.amazon.device.ads.DTBAdNetwork
import com.amazon.device.ads.DTBAdNetworkInfo
import com.amazon.device.ads.MRAIDPolicy
import com.appharbr.sdk.configuration.AHSdkConfiguration
import com.appharbr.sdk.engine.AppHarbr
import com.appharbr.sdk.engine.InitializationFailureReason
import com.appharbr.sdk.engine.listeners.OnAppHarbrInitializationCompleteListener
import com.github.anrwatchdog.ANRWatchDog
import com.google.android.gms.ads.MobileAds
import com.pubmatic.sdk.common.OpenWrapSDK
import com.pubmatic.sdk.common.models.POBApplicationInfo
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.intersitial.SilentInterstitial
import com.rtb.rtbdemand.intersitial.SilentInterstitialConfig
import com.rtb.rtbdemand.sdk.EventHelper.attachEventHandler
import com.rtb.rtbdemand.sdk.EventHelper.attachSentry
import com.rtb.rtbdemand.sdk.EventHelper.shouldHandle
import com.rtb.rtbdemand.sdk.SDKManager.initializePrebid
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prebid.mobile.Host
import org.prebid.mobile.PrebidMobile
import org.prebid.mobile.TargetingParams
import org.prebid.mobile.rendering.models.openrtb.bidRequests.Ext
import java.net.MalformedURLException
import java.net.URL


object RTBDemand {
    private var storeService: StoreService? = null
    private var workManager: WorkManager? = null
    internal var logEnabled = false
    internal var specialTag: String? = null
    private var silentInterstitial: SilentInterstitial? = null
    internal var networkManager: NetworkManager? = null

    fun initialize(context: Context, logsEnabled: Boolean = false) = CoroutineScope(Dispatchers.IO).launch {
        log("ABM Version ${BuildConfig.ADAPTER_VERSION} initialized.")
        attachEventHandler(context)
        EventHelper.attachAnrWatchDog()
        this@RTBDemand.logEnabled = logsEnabled
        if (networkManager == null) {
            networkManager = NetworkManager()
        }
        networkManager?.register(context)
        ConfigProvider.fetchConfig(context)
    }

    @Synchronized
    internal fun getStoreService(context: Context): StoreService {
        if (storeService == null) {
            storeService = StoreService(context.getSharedPreferences(this.toString().substringBefore("@"), Context.MODE_PRIVATE))
        }
        return storeService as StoreService
    }

    @Synchronized
    internal fun getWorkManager(context: Context): WorkManager {
        if (workManager == null) {
            workManager = WorkManager.getInstance(context)
        }
        return workManager as WorkManager
    }

    internal fun connectionAvailable(): Boolean {
        return networkManager?.isInternetAvailable == true
    }

    internal suspend fun configFetched(context: Context, config: SDKConfig?) = withContext(Dispatchers.IO) {
        specialTag = config?.infoConfig?.specialTag
        logEnabled = (logEnabled || config?.infoConfig?.normalInfo == 1)
        attachSentry(context, config?.events)
        SDKManager.initialize(context, config)
        initPrebid()
    }

    internal fun initPrebid() {
        silentInterstitial?.findContext()?.let { activity ->
            try {
                if (!(activity.isDestroyed && activity.isFinishing)) {
                    val config = ConfigProvider.getConfig(activity)
                    if (config != null && config.switch == 1) {
                        initializePrebid(activity, config.prebid)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    internal fun registerActivity(context: Context) = CoroutineScope(Dispatchers.IO).launch {
        if (silentInterstitial == null) {
            silentInterstitial = SilentInterstitial()
        }
        (context as? Activity)?.let {
            silentInterstitial?.registerActivity(it)
        }
    }

    internal fun checkForSilentInterstitial(context: Context, silentInterstitialConfig: SilentInterstitialConfig?, countryConfig: CountryModel?) {
        if (silentInterstitial == null) {
            silentInterstitial = SilentInterstitial()
        }
        if (silentInterstitialConfig == null) {
            silentInterstitial?.destroy()
            return
        }
        val shouldStart: Boolean
        val regionConfig = silentInterstitialConfig.regionConfig
        shouldStart = if (regionConfig == null || (regionConfig.getCities().isEmpty() && regionConfig.getStates().isEmpty() && regionConfig.getCountries().isEmpty())) {
            true
        } else {
            if ((regionConfig.mode ?: "allow").contains("allow", true)) {
                regionConfig.getCities().any { it.equals(countryConfig?.city, true) }
                        || regionConfig.getStates().any { it.equals(countryConfig?.state, true) }
                        || regionConfig.getCountries().any { it.equals(countryConfig?.countryCode, true) }
            } else {
                regionConfig.getCities().none { it.equals(countryConfig?.city, true) }
                        && regionConfig.getStates().none { it.equals(countryConfig?.state, true) }
                        && regionConfig.getCountries().none { it.equals(countryConfig?.countryCode, true) }
            }
        }
        val number = (1..100).random()
        if (shouldStart && number in 1..(silentInterstitialConfig.activePercentage ?: 0)) {
            silentInterstitial?.init(context)
        }
    }
}

internal object EventHelper {

    suspend fun attachEventHandler(context: Context) = withContext(Dispatchers.IO) {
        Thread.setDefaultUncaughtExceptionHandler(EventHandler(context, Thread.getDefaultUncaughtExceptionHandler()))
    }

    suspend fun attachAnrWatchDog() = withContext(Dispatchers.IO) {
        ANRWatchDog(7000).start()
    }

    suspend fun attachSentry(context: Context, events: SDKConfig.Events?) = withContext(Dispatchers.IO) {
        val sentryInitPercentage = events?.sentry ?: 100
        if (shouldHandle(sentryInitPercentage) && !Sentry.isEnabled()) {
            SentryAndroid.init(context) { options ->
                options.environment = context.packageName
                options.dsn = "https://9bf82b481805d3068675828513d59d68@o4505753409421312.ingest.sentry.io/4505753410732032"
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> getProcessedEvent(events, event) }
            }
        }
    }

    private fun getProcessedEvent(events: SDKConfig.Events?, event: SentryEvent): SentryEvent? {
        val sentEvent = if ((event.throwable?.stackTraceToString()?.contains(BuildConfig.LIBRARY_PACKAGE_NAME, true) == true
                        && shouldHandle(events?.self ?: 100)) || (event.throwable?.stackTraceToString()?.contains("OutOfMemoryError", true) == true
                        && shouldHandle(events?.oom ?: 100))) {
            event
        } else {
            if (shouldHandle(events?.other ?: 0)) {
                event
            } else {
                null
            }
        }
        sentEvent?.setExtra("RAW_TRACE", event.throwable?.stackTraceToString() ?: "")
        sentEvent?.dist = BuildConfig.ADAPTER_VERSION
        sentEvent?.tags = hashMapOf<String?, String?>().apply {
            if (event.throwable?.stackTraceToString()?.contains(BuildConfig.LIBRARY_PACKAGE_NAME, true) == true) {
                put("SDK_ISSUE", "yes")
            } else {
                put("PUBLISHER_ISSUE", "Yes")
            }
        }

        return sentEvent
    }

    fun shouldHandle(max: Int): Boolean {
        return try {
            val number = (1..100).random()
            number in 1..max
        } catch (_: Throwable) {
            false
        }

    }
}


@Suppress("UNNECESSARY_SAFE_CALL")
internal object SDKManager {

    suspend fun initialize(context: Context, config: SDKConfig?) {
        initializeGAM(context)
        if (config == null || config.switch != 1) return
        initializeGeoEdge(context, config.geoEdge?.apiKey)
        initializeAPS(context, config.aps)
        initializeOpenWrap(config.openWrapConfig)
    }

    fun initializePrebid(context: Activity, prebid: SDKConfig.Prebid?) = CoroutineScope(Dispatchers.Main).launch {
        if (PrebidMobile.isSdkInitialized()) return@launch
        try {
            PrebidMobile.setPbsDebug(prebid?.debug == 1)
            PrebidMobile.setPrebidServerHost(Host.createCustomHost(prebid?.host ?: ""))
            PrebidMobile.setPrebidServerAccountId(prebid?.accountId ?: "")
            PrebidMobile.setTimeoutMillis(prebid?.timeout?.toIntOrNull() ?: 1000)
            PrebidMobile.initializeSdk(context) { Logger.INFO.log(msg = "Prebid Initialization Completed") }
            PrebidMobile.setShareGeoLocation(prebid?.location == null || prebid.location == 1)
            prebid?.gdpr?.let { TargetingParams.setSubjectToGDPR(it == 1) }
            if (TargetingParams.isSubjectToGDPR() == true) {
                TargetingParams.setGDPRConsentString(TargetingParams.getGDPRConsentString())
            }
            if (!prebid?.bundleName.isNullOrEmpty()) {
                TargetingParams.setBundleName(prebid?.bundleName)
            }
            if (!prebid?.domain.isNullOrEmpty()) {
                TargetingParams.setDomain(prebid?.domain)
            }
            if (!prebid?.storeURL.isNullOrEmpty()) {
                TargetingParams.setStoreUrl(prebid?.storeURL)
            }
            if (!prebid?.omidPartnerName.isNullOrEmpty()) {
                TargetingParams.setOmidPartnerName(prebid?.omidPartnerName)
            }
            if (!prebid?.omidPartnerVersion.isNullOrEmpty()) {
                TargetingParams.setOmidPartnerVersion(prebid?.omidPartnerVersion)
            }
            if (!prebid?.extParams.isNullOrEmpty()) {
                TargetingParams.setUserExt(Ext().apply {
                    prebid?.extParams?.forEach { put(it.key ?: "", it.value ?: "") }
                })
            }
        } catch (_: Throwable) {
        }
    }

    private suspend fun initializeGAM(context: Context) = withContext(Dispatchers.IO) {
        MobileAds.initialize(context) {
            Logger.INFO.log(msg = "GAM Initialization complete.")
        }
    }

    private suspend fun initializeGeoEdge(context: Context, apiKey: String?) = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrEmpty()) return@withContext
        val configuration = AHSdkConfiguration.Builder(apiKey).build()
        AppHarbr.initialize(context, configuration, object : OnAppHarbrInitializationCompleteListener {
            override fun onSuccess() {
                Logger.INFO.log(msg = "AppHarbr SDK Initialized Successfully")
            }

            override fun onFailure(reason: InitializationFailureReason) {
                Logger.ERROR.log(msg = "AppHarbr SDK Initialization Failed: ${reason.readableHumanReason}")
            }

        })
    }

    private suspend fun initializeAPS(context: Context, aps: SDKConfig.Aps?) = withContext(Dispatchers.IO) {
        if (aps?.appKey.isNullOrEmpty()) return@withContext
        fun init() {
            AdRegistration.getInstance(aps?.appKey ?: "", context)
            AdRegistration.setAdNetworkInfo(DTBAdNetworkInfo(DTBAdNetwork.GOOGLE_AD_MANAGER))
            AdRegistration.useGeoLocation(aps?.location == null || aps.location == 1)
            AdRegistration.setMRAIDPolicy(MRAIDPolicy.CUSTOM)
            aps?.mRaidSupportedVersions?.let {
                AdRegistration.setMRAIDSupportedVersions(it.toTypedArray())
            }
            aps?.omidPartnerName?.let {
                AdRegistration.addCustomAttribute("omidPartnerName", it)
            }
            aps?.omidPartnerVersion?.let {
                AdRegistration.addCustomAttribute("omidPartnerVersion", it)
            }
        }
        if (aps?.delay == null || aps.delay.toIntOrNull() == 0) {
            init()
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ init() }, aps.delay.toLongOrNull() ?: 1L)
        }
    }

    private suspend fun initializeOpenWrap(owConfig: SDKConfig.OpenWrapConfig?) = withContext(Dispatchers.IO) {
        if (owConfig?.playStoreUrl.isNullOrEmpty()) return@withContext
        val appInfo = POBApplicationInfo()
        try {
            appInfo.storeURL = URL(owConfig?.playStoreUrl ?: "")
        } catch (_: MalformedURLException) {
        }
        OpenWrapSDK.setApplicationInfo(appInfo)
    }
}

internal class StoreService(private val prefs: SharedPreferences) {

    var lastInterstitial: Long
        get() = prefs.getLong("INTER_TIME", 0L)
        set(value) = prefs.edit().putLong("INTER_TIME", value).apply()
}

internal class EventHandler(private val context: Context, private val defaultHandler: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, exception: Throwable) {
        ConfigProvider.getConfig(context).let { config ->
            if (exception.stackTraceToString().contains(BuildConfig.LIBRARY_PACKAGE_NAME, true) && shouldHandle(config?.events?.self ?: 100)) {
                Sentry.captureException(exception)
                Process.killProcess(Process.myPid())
            } else if (exception.stackTraceToString().contains("OutOfMemoryError", true) && shouldHandle(config?.events?.oom ?: 100)) {
                Sentry.captureException(exception)
                Process.killProcess(Process.myPid())
            } else {
                if (shouldHandle(config?.events?.other ?: 0)) {
                    Sentry.captureException(exception)
                    Process.killProcess(Process.myPid())
                } else {
                    defaultHandler?.uncaughtException(thread, exception)
                }
            }
        }
    }

}