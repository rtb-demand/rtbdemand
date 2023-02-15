package com.rtb.rtbdemand.intersitial

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.admanager.AdManagerInterstitialAd
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.TAG

class InterstitialAd(private val context: Activity, private val adUnit: String) {
    private var interstitialAdManager = InterstitialAdManager(context, adUnit)
    private var mAdManagerInterstitialAd: AdManagerInterstitialAd? = null

    fun load(adRequest: AdRequest, callBack: (loaded: Boolean) -> Unit) {
        interstitialAdManager.load(adRequest) {
            mAdManagerInterstitialAd = it
            callBack(mAdManagerInterstitialAd != null)
        }
    }

    fun show() {
        if (mAdManagerInterstitialAd != null) {
            mAdManagerInterstitialAd?.show(context)
        } else {
            Log.e(TAG, "The interstitial ad wasn't ready yet.")
        }
    }
}