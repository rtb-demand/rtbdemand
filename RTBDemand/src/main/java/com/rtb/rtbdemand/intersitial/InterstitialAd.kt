package com.rtb.rtbdemand.intersitial

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.admanager.AdManagerInterstitialAd
import com.pubmatic.sdk.common.POBError
import com.pubmatic.sdk.openwrap.eventhandler.dfp.DFPInterstitialEventHandler
import com.pubmatic.sdk.openwrap.interstitial.POBInterstitial
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.sdk.FullScreenContentCallback
import com.rtb.rtbdemand.sdk.InterstitialAdListener
import com.rtb.rtbdemand.sdk.Logger
import com.rtb.rtbdemand.sdk.RTBError
import com.rtb.rtbdemand.sdk.log

class InterstitialAd(private val context: Activity, private val adUnit: String) {
    private var interstitialAdManager = InterstitialAdManager(context, adUnit)
    private var mInterstitialAd: AdManagerInterstitialAd? = null
    private var pobInterstitial: POBInterstitial? = null
    private var listener: InterstitialAdListener? = null

    fun load(adRequest: AdRequest, callBack: (loaded: Boolean) -> Unit) {
        interstitialAdManager.load(adRequest) { ad, error ->
            mInterstitialAd = ad
            callBack(mInterstitialAd != null)
            if (ad != null) {
                listener?.onAdReceived(this@InterstitialAd)
            } else {
                listener?.onAdFailedToLoad(this@InterstitialAd, error ?: RTBError(3, "No Fill"))
            }
        }
    }

    fun setListener(listener: InterstitialAdListener) {
        this.listener = listener
    }

    fun enableTestMode(enabled: Boolean) {
        interstitialAdManager.owTestMode = enabled
    }

    fun enableDebugState(enabled: Boolean) {
        interstitialAdManager.owDebugState = enabled
    }

    fun enableBidSummary(enabled: Boolean) {
        interstitialAdManager.owBidSummary = enabled
    }

    fun loadWithOW(pubID: String, profile: Int, owAdUnitId: String, configListener: DFPInterstitialEventHandler.DFPConfigListener? = null) {
        interstitialAdManager.loadWithOW(pubID, profile, owAdUnitId, configListener, {
            pobInterstitial = it
        }, { ad, error ->
            mInterstitialAd = ad
            if (mInterstitialAd != null) {
                listener?.onAdReceived(this)
            } else {
                listener?.onAdFailedToLoad(this, error ?: RTBError(3, "No Fill"))
            }
        }, object : POBInterstitial.POBInterstitialListener() {
            override fun onAdReceived(p0: POBInterstitial) {
                listener?.onAdReceived(this@InterstitialAd)
            }

            override fun onAdFailedToLoad(p0: POBInterstitial, p1: POBError) {
                listener?.onAdFailedToLoad(this@InterstitialAd, RTBError(p1.errorCode, p1.errorMessage))
            }

            override fun onAdFailedToShow(p0: POBInterstitial, p1: POBError) {
                listener?.onAdFailedToShow(this@InterstitialAd, RTBError(p1.errorCode, p1.errorMessage))
            }

            override fun onAppLeaving(p0: POBInterstitial) {
                listener?.onAppLeaving(this@InterstitialAd)
            }

            override fun onAdOpened(p0: POBInterstitial) {
                listener?.onAdOpened(this@InterstitialAd)
            }

            override fun onAdClosed(p0: POBInterstitial) {
                listener?.onAdClosed(this@InterstitialAd)
            }

            override fun onAdClicked(p0: POBInterstitial) {
                listener?.onAdClicked(this@InterstitialAd)
            }

            override fun onAdExpired(p0: POBInterstitial) {
                listener?.onAdExpired(this@InterstitialAd)
            }
        })
    }

    fun show() {
        if (pobInterstitial != null && pobInterstitial?.isReady == true) {
            pobInterstitial?.show()
        } else if (mInterstitialAd != null) {
            mInterstitialAd?.show(context)
        } else {
            Logger.ERROR.log(msg = "The interstitial ad wasn't ready yet.")
        }
    }

    fun setContentCallback(callback: FullScreenContentCallback) {
        mInterstitialAd?.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdClicked() {
                super.onAdClicked()
                callback.onAdClicked()
            }

            override fun onAdDismissedFullScreenContent() {
                super.onAdDismissedFullScreenContent()
                mInterstitialAd = null
                callback.onAdDismissedFullScreenContent()
            }

            override fun onAdFailedToShowFullScreenContent(p0: AdError) {
                super.onAdFailedToShowFullScreenContent(p0)
                mInterstitialAd = null
                callback.onAdFailedToShowFullScreenContent(RTBError(p0.code, p0.message))
            }

            override fun onAdImpression() {
                super.onAdImpression()
                callback.onAdImpression()
            }

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                callback.onAdShowedFullScreenContent()
            }
        }
    }
}