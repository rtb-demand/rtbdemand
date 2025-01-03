package com.rtb.rtbdemand.intersitial

import android.app.Activity
import android.content.Context
import android.os.CountDownTimer
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.rtb.rtbdemand.BuildConfig
import com.rtb.rtbdemand.R
import com.rtb.rtbdemand.banners.BannerAdSize
import com.rtb.rtbdemand.banners.BannerAdView
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.rewardedinterstitial.RewardedInterstitialAd
import com.rtb.rtbdemand.sdk.BannerAdListener
import com.rtb.rtbdemand.sdk.ConfigProvider
import com.rtb.rtbdemand.sdk.RTBDemand
import com.rtb.rtbdemand.sdk.RTBError
import com.rtb.rtbdemand.sdk.StoreService
import com.rtb.rtbdemand.sdk.log
import java.util.Date

internal class SilentInterstitial {

    private var activities: ArrayList<Activity> = arrayListOf()
    private var storeService: StoreService? = null
    private var interstitialConfig: SilentInterstitialConfig = SilentInterstitialConfig()
    private var activeTimeCounter: CountDownTimer? = null
    private var closeDelayTimer: CountDownTimer? = null
    private var started: Boolean = false
    private var banner: BannerAdView? = null
    private var timerSeconds = 0
    private var dialog: AppCompatDialog? = null
    private val tag: String
        get() = this.javaClass.simpleName

    @Suppress("SENSELESS_COMPARISON")
    fun registerActivity(activity: Activity) {
        try {
            if (activities.isEmpty() || started) {
                activities = activities.filter { it != null && !it.isDestroyed && !it.isFinishing } as ArrayList<Activity>
                if (activities.none { it.localClassName == activity.localClassName }) {
                    tag.log { activity.localClassName }
                    activities.add(activity)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun init(context: Context) {
        if (started) return
        tag.log { String.format("%s:%s- Version:%s", "setConfig", "entry", BuildConfig.ADAPTER_VERSION) }
        storeService = RTBDemand.getStoreService(context)
        ConfigProvider.getConfig(context).let { sdkConfig ->
            val shouldBeActive = !(sdkConfig == null || sdkConfig.switch != 1)
            if (!shouldBeActive) return@let
            interstitialConfig = sdkConfig?.silentInterstitialConfig ?: SilentInterstitialConfig()
            ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifeCycleHandler())
            started = true
            timerSeconds = interstitialConfig.timer ?: 0
            tag.log { "setConfig :$interstitialConfig" }
            resumeCounter()
        }
    }

    fun destroy() {
        started = false
        activeTimeCounter?.cancel()
        closeDelayTimer?.cancel()
    }

    private fun resumeCounter() {
        if (started) {
            startActiveCounter(timerSeconds.toLong())
        }
    }

    private fun pauseCounter() {
        activeTimeCounter?.cancel()
    }

    private fun startActiveCounter(seconds: Long) {
        if (seconds <= 0) return
        activeTimeCounter?.cancel()
        activeTimeCounter = object : CountDownTimer(seconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timerSeconds--
            }

            override fun onFinish() {
                timerSeconds = interstitialConfig.timer ?: 0
                loadAd()
            }
        }
        activeTimeCounter?.start()
    }

    private inner class AppLifeCycleHandler : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeCounter()
            }
            if (event == Lifecycle.Event.ON_PAUSE) {
                pauseCounter()
            }

            if (event == Lifecycle.Event.ON_DESTROY) {
                destroy()
                banner?.destroyAd()
                dialog?.dismiss()
            }
        }
    }

    private fun loadAd() {
        val lastInterShown = storeService?.lastInterstitial ?: 0L
        if (Date().time - lastInterShown >= (interstitialConfig.loadFrequency ?: 0) * 1000) {
            if (interstitialConfig.rewarded == 1) {
                loadRewarded()
            } else if (interstitialConfig.custom == 1) {
                loadCustomInterstitial()
            } else {
                loadInterstitial()
            }
        } else {
            tag.log { "Frequency condition did not met." }
            resumeCounter()
        }
    }

    private fun loadRewarded() = findContext()?.let { activity ->
        tag.log { "Loading rewarded interstitial with unit ${interstitialConfig.adunit}" }
        val rewardedInterstitialAd = RewardedInterstitialAd(activity, interstitialConfig.adunit ?: "")
        rewardedInterstitialAd.load(AdRequest().Builder().build()) { loaded ->
            if (loaded) {
                tag.log { "Rewarded interstitial ad has loaded and it should show now" }
                storeService?.lastInterstitial = Date().time
                rewardedInterstitialAd.show {}
                resumeCounter()
            } else {
                tag.log { "Rewarded interstitial ad has failed and trying custom now" }
                loadCustomInterstitial()
            }
        }
    } ?: kotlin.run {
        tag.log { "Foreground context is not present for GAM  Rewarded Load" }
        resumeCounter()
    }

    private fun loadInterstitial() = findContext()?.let { activity ->
        tag.log { "Loading interstitial with unit ${interstitialConfig.adunit}" }
        val interstitialAd = InterstitialAd(activity, interstitialConfig.adunit ?: "")
        interstitialAd.load(AdRequest().Builder().build()) { loaded ->
            if (loaded) {
                tag.log { "Interstitial ad has loaded and it should show now" }
                storeService?.lastInterstitial = Date().time
                interstitialAd.show()
                resumeCounter()
            } else {
                tag.log { "Interstitial ad has failed and trying custom now" }
                loadCustomInterstitial()
            }
        }
    } ?: kotlin.run {
        tag.log { "Foreground context is not present for GAM load" }
        resumeCounter()
    }

    internal fun findContext(): Activity? {
        if (activities.isEmpty()) return null
        return try {
            activities = activities.filter { !it.isDestroyed && !it.isFinishing } as ArrayList<Activity>
            val current = activities.firstOrNull { ((it as? AppCompatActivity) ?: (it as? ComponentActivity))?.lifecycle?.currentState == Lifecycle.State.RESUMED }
            return if (dialog?.isShowing == true) {
                null
            } else current
        } catch (e: Throwable) {
            tag.log { e.localizedMessage ?: "" }
            null
        }
    }

    private fun loadCustomInterstitial() = findContext()?.let { activity ->
        tag.log { "Loading banner with unit ${interstitialConfig.adunit}" }
        banner?.destroyAd()
        banner = BannerAdView(activity).also { it.makeInter() }
        banner?.setAdSizes(*getBannerSizes().toTypedArray())
        banner?.setAdUnitID(interstitialConfig.adunit ?: "")
        banner?.setAdListener(object : BannerAdListener {
            override fun onAdClicked(bannerAdView: BannerAdView) {
            }

            override fun onAdClosed(bannerAdView: BannerAdView) {
            }

            override fun onAdFailedToLoad(bannerAdView: BannerAdView, error: RTBError, retrying: Boolean) {
                tag.log { "Custom ad has failed to load with retry:$retrying" }
                if (!retrying) {
                    resumeCounter()
                }
            }

            override fun onAdImpression(bannerAdView: BannerAdView) {}

            override fun onAdLoaded(bannerAdView: BannerAdView) {
                resumeCounter()
                showCustomAd(bannerAdView, activity)
            }

            override fun onAdOpened(bannerAdView: BannerAdView) {
            }
        })
        banner?.loadAd(AdRequest().Builder().build())
    } ?: kotlin.run {
        tag.log { "Foreground context not present for custom load" }
        resumeCounter()
    }

    private fun showCustomAd(ad: BannerAdView, activity: Activity) {
        if (activity.isDestroyed || activity.isFinishing || dialog?.isShowing == true) return
        tag.log { "Custom ad has loaded and it should show now" }
        try {
            dialog?.cancel()
            dialog = AppCompatDialog(activity, android.R.style.Theme_Light)
            dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog?.setContentView(R.layout.custom_inter_layout)
            dialog?.create()
            val rootLayout = dialog?.findViewById<LinearLayout>(R.id.root_layout)
            rootLayout?.removeAllViews()
            rootLayout?.addView(ad)
            dialog?.setOnDismissListener { ad.destroyAd() }
            dialog?.findViewById<ImageButton>(R.id.close_ad)?.setOnClickListener {
                dialog?.dismiss()
            }
            closeDelayTimer?.cancel()
            closeDelayTimer = object : CountDownTimer((interstitialConfig.closeDelay ?: 0).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    dialog?.findViewById<ImageButton>(R.id.close_ad)?.visibility = View.VISIBLE
                }
            }
            closeDelayTimer?.start()
            dialog?.show()
            storeService?.lastInterstitial = Date().time
        } catch (e: Throwable) {
            tag.log { "Custom ad could not show because : ${e.localizedMessage}" }
        }
    }

    private fun getBannerSizes(): List<BannerAdSize> {
        val temp = arrayListOf<BannerAdSize>()
        interstitialConfig.bannerSizes?.forEach {
            if (it.height.equals("fluid", true) || it.width.equals("fluid", true)) {
                temp.add(BannerAdSize.FLUID)
            }
            if (it.height?.toIntOrNull() != null && it.width?.toIntOrNull() != null) {
                temp.add(BannerAdSize(width = it.width.toIntOrNull() ?: 300, height = it.height.toIntOrNull() ?: 250))
            }
        }

        if (temp.isEmpty()) {
            temp.add(BannerAdSize.MEDIUM_RECTANGLE)
        }
        return temp
    }

}