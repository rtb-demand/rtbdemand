package com.rtb.rtbdemand.rewardedinterstitial

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.TAG

class RewardedInterstitialAd(private val context: Activity, private val adUnit: String) {
    private var rewardedInterstitialAdManager = RewardedInterstitialAdManager(context, adUnit)
    private var mAdManagerInterstitialAd: RewardedInterstitialAd? = null

    fun load(adRequest: AdRequest, callBack: (loaded: Boolean) -> Unit) {
        rewardedInterstitialAdManager.load(adRequest) {
            mAdManagerInterstitialAd = it
            callBack(mAdManagerInterstitialAd != null)
        }
    }

    fun show(callBack: (reward: Reward?) -> Unit) {
        if (mAdManagerInterstitialAd != null) {
            mAdManagerInterstitialAd?.show(context) { callBack(Reward(it.amount, it.type)) }
        } else {
            Log.e(TAG, "The rewarded interstitial ad wasn't ready yet.")
            callBack(null)
        }
    }
}